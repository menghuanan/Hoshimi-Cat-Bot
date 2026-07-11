package top.bilibili.service

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.bilibili.BiliConfigManager
import top.bilibili.SubData
import top.bilibili.BiliDataWrapper
import top.bilibili.core.BiliDataRuntimeCoordinator
import top.bilibili.api.follow
import top.bilibili.api.groupAddUser
import top.bilibili.api.isFollow
import top.bilibili.api.unfollow
import top.bilibili.api.userInfo
import top.bilibili.core.BiliBiliBot
import top.bilibili.utils.containsEquivalentSubject
import top.bilibili.utils.normalizeContactSubject
import top.bilibili.utils.parseContactId
import top.bilibili.utils.parsePlatformContact

/**
 * 统一维护动态订阅的来源、联系人展开和关联清理，避免订阅状态在多处失配。
 */
object DynamicService {
    private val mutex = Mutex()
    private val hexColorRegex = Regex("^#[0-9A-Fa-f]{6}$")

    // 容量保护
    private const val MAX_SUBSCRIPTIONS = 50000
    private const val MAX_CONTACTS_PER_UID = 1000

    // sourceRefs 统一订阅来源:
    // - direct:<contact>
    // - groupRef:<groupName>
    private const val DIRECT_PREFIX = "direct:"
    private const val GROUP_REF_PREFIX = "groupRef:"

    private suspend fun followUser(uid: Long): String? {
        if (uid == BiliBiliBot.uid) return null

        val attr = client.isFollow(uid)?.attribute
        if (attr == 0) {
            if (!BiliConfigManager.config.accountConfig.autoFollow) return "未关注此用户"
            val res = client.follow(uid)
            if (res.code != 0) return "关注失败: ${res.message}"
            if (BiliConfigManager.config.accountConfig.followGroup.isNotEmpty()) {
                val res1 = client.groupAddUser(uid, BiliBiliBot.tagid)
                if (res1.code != 0) logger.error("移动分组失败: ${res1.message}")
            }
            actionNotify("通知: 账号关注 $uid")
        } else if (attr == 128) {
            return "此账号已被拉黑"
        }
        return null
    }

    private fun normalizeSubject(subject: String): String? {
        return normalizeContactSubject(subject)
    }

    private fun directSourceRef(subject: String): String = "$DIRECT_PREFIX$subject"
    private fun groupSourceRef(groupName: String): String = "$GROUP_REF_PREFIX$groupName"

    private fun parseDirectSourceRef(sourceRef: String): String? {
        if (!sourceRef.startsWith(DIRECT_PREFIX)) return null
        return normalizeSubject(sourceRef.removePrefix(DIRECT_PREFIX))
    }

    private fun parseGroupSourceRef(sourceRef: String): String? {
        if (!sourceRef.startsWith(GROUP_REF_PREFIX)) return null
        return sourceRef.removePrefix(GROUP_REF_PREFIX).takeIf { it.isNotBlank() }
    }

    private fun rebuildContactsFromSources(subData: SubData, groups: Map<String, top.bilibili.Group>): Set<String> {
        val normalizedSourceRefs = mutableSetOf<String>()
        val resolvedContacts = mutableSetOf<String>()

        subData.sourceRefs.toList().forEach { sourceRef ->
            val directContact = parseDirectSourceRef(sourceRef)
            if (directContact != null) {
                normalizedSourceRefs.add(directSourceRef(directContact))
                resolvedContacts.add(directContact)
                return@forEach
            }

            val groupName = parseGroupSourceRef(sourceRef)
            if (groupName != null) {
                normalizedSourceRefs.add(groupSourceRef(groupName))
                val contactsInGroup = groups[groupName]?.contacts.orEmpty()
                contactsInGroup.mapNotNullTo(resolvedContacts) { normalizeSubject(it) }
                return@forEach
            }

            // 兼容历史脏数据: 直接将旧 contact 视为 direct 来源
            val normalizedLegacyContact = normalizeSubject(sourceRef)
            if (normalizedLegacyContact != null) {
                normalizedSourceRefs.add(directSourceRef(normalizedLegacyContact))
                resolvedContacts.add(normalizedLegacyContact)
            }
        }

        subData.sourceRefs.clear()
        subData.sourceRefs.addAll(normalizedSourceRefs)

        subData.contacts.clear()
        subData.contacts.addAll(resolvedContacts)
        return resolvedContacts
    }

    private fun cleanupRemovedContactFilters(
        filters: MutableMap<String, MutableMap<Long, top.bilibili.DynamicFilter>>,
        uid: Long,
        removedContacts: Set<String>,
    ) {
        removedContacts.forEach { contact ->
            if (filters[contact]?.run {
                    remove(uid)
                    isEmpty()
                } == true
            ) {
                filters.remove(contact)
            }
        }
    }

    private fun removeUidCompletely(candidate: BiliDataWrapper, uid: Long) {
        candidate.dynamic.remove(uid)

        // 同步清理与 UID 绑定的数据
        candidate.filter.forEach { (_, filterMap) ->
            filterMap.remove(uid)
        }

        // 彻底移除 UID 时同步清理新模板策略，避免失效 UID 仍残留在 direct/groupRef scope 中。
        candidate.dynamicTemplatePolicyByScope.values.forEach { it.remove(uid) }
        candidate.liveTemplatePolicyByScope.values.forEach { it.remove(uid) }
        candidate.liveCloseTemplatePolicyByScope.values.forEach { it.remove(uid) }
        candidate.dynamicTemplatePolicyByScope.entries.removeIf { it.value.isEmpty() }
        candidate.liveTemplatePolicyByScope.entries.removeIf { it.value.isEmpty() }
        candidate.liveCloseTemplatePolicyByScope.entries.removeIf { it.value.isEmpty() }
        candidate.dynamicColorByUid.entries.removeIf { (_, colors) -> colors.remove(uid); colors.isEmpty() }
        candidate.atAll.entries.removeIf { (_, values) -> values.remove(uid); values.isEmpty() }
        candidate.atAllCooldownUntil.keys.removeIf { key -> key.contains("|$uid|") || key.contains(".$uid.") }
        candidate.subscriptionCardUpdatedAt.remove("dynamic:$uid")

        logger.info("已完全移除 UID $uid 的订阅数据（无订阅来源）")
    }

    /**
     * WebUI 卡片删除是 UID 级完整退订，必须无视 direct/groupRef 来源并触发账号侧取消关注。
     */
    suspend fun removeUidForWebUi(uid: Long) = mutex.withLock {
        val user = BiliDataRuntimeCoordinator.snapshot().dynamic[uid] ?: return@withLock "还未订阅此人哦"
        val result = BiliDataRuntimeCoordinator.mutateAndPersist { candidate -> removeUidCompletely(candidate, uid) }
        if (result.committed) {
            unfollowUser(uid)
            "取消订阅 ${user.name} 成功"
        } else "保存失败，订阅未变更，请稍后重试"
    }

    /**
     * 从全部模板策略表中移除指定 UID。
     * 清理时同时回收空 scope，避免策略树在长期运行中残留无效壳层。
     */
    private fun removeUidTemplatePolicies(uid: Long) {
        TemplateRuntimeCoordinator.removeUidAcrossTypes(uid)
    }

    /**
     * 在事务外完成远程关注与昵称查询，只返回候选构建所需数据，不修改 live BiliData。
     */
    private suspend fun resolveNewSubscriptionName(uid: Long): Pair<String?, String?> {
        val userName = if (uid == BiliBiliBot.uid) {
            client.userInfo(uid)?.name
        } else {
            val followError = followUser(uid)
            if (followError != null) return null to followError
            if (uid == 11783021L) "哔哩哔哩番剧出差" else client.userInfo(uid)?.name
        }

        if (userName.isNullOrBlank()) {
            return null to "获取UP主信息失败，请稍后重试"
        }
        return userName to null
    }

    private suspend fun unfollowUser(uid: Long) {
        if (uid == BiliBiliBot.uid) return

        val hasOtherSubscribers = dynamic[uid]?.contacts?.isNotEmpty() == true
        if (hasOtherSubscribers || !BiliConfigManager.config.accountConfig.autoFollow) return

        val attr = client.isFollow(uid)?.attribute
        if (attr != null && attr != 0 && attr != 128) {
            val res = client.unfollow(uid)
            if (res.code == 0) {
                actionNotify("通知: 账号取消关注 $uid")
            } else {
                logger.error("取消关注失败: ${res.message}")
            }
        }
    }

    private fun normalizeColorInput(color: String): String? {
        val segments = color.split(";", "；").map { it.trim() }
        if (segments.isEmpty() || segments.any { it.isEmpty() }) return null
        if (segments.any { !hexColorRegex.matches(it) }) return null
        return segments.joinToString(";")
    }

    /**
     * 为旧主题色命令保留兼容提示，明确引导调用方迁移到会话作用域绑定。
     */
    @Deprecated("请使用目标作用域主题色绑定")
    suspend fun setColor(uid: Long, color: String) = mutex.withLock {
        dynamic[uid] ?: return@withLock "没有订阅过 UID: $uid"
        normalizeColorInput(color)
            ?: return@withLock "格式错误，请输入16进制颜色，如: #d3edfa 或 #d3edfa;#fde8ed"
        "请在目标会话中使用 /bili color 重新设置主题色"
    }

    /**
     * 为单个联系人追加直接订阅来源，并在必要时同步创建底层订阅记录。
     */
    suspend fun addDirectSubscribe(uid: Long, subject: String, isSelf: Boolean = true) = mutex.withLock {
        val normalizedSubject = normalizeSubject(subject) ?: return@withLock "联系人格式错误: $subject"
        val sourceRef = directSourceRef(normalizedSubject)
        val initial = BiliDataRuntimeCoordinator.snapshot()
        val existing = initial.dynamic[uid]
        if (existing != null && sourceRef in existing.sourceRefs) return@withLock "之前订阅过这个人哦"
        val newName = if (existing == null) resolveNewSubscriptionName(uid).also { (_, error) ->
            if (error != null) return@withLock error
        }.first else existing.name
        var response = ""
        val result = BiliDataRuntimeCoordinator.mutateAndPersist(
            validate = { candidate -> if ((candidate.dynamic[uid]?.contacts?.size ?: 0) > MAX_CONTACTS_PER_UID) "联系人数量超限" else null },
        ) { candidate ->
            if (candidate.dynamic.size >= MAX_SUBSCRIPTIONS && uid !in candidate.dynamic) error("订阅数量已达上限 $MAX_SUBSCRIPTIONS")
            val subData = candidate.dynamic.getOrPut(uid) { SubData(requireNotNull(newName)) }
            val oldContacts = subData.contacts.toSet()
            subData.sourceRefs.add(sourceRef)
            rebuildContactsFromSources(subData, candidate.group)
            cleanupRemovedContactFilters(candidate.filter, uid, oldContacts - subData.contacts)
            response = if (isSelf) "订阅 ${subData.name} 成功!" else "为 $normalizedSubject 订阅 ${subData.name} 成功!"
        }
        if (result.committed) response else "保存失败，订阅未生效，请稍后重试"
    }

    /**
     * 移除单个联系人对应的直接订阅来源，并在来源清空时回收整条订阅数据。
     */
    suspend fun removeDirectSubscribe(uid: Long, subject: String, isSelf: Boolean = true) = mutex.withLock {
        val normalizedSubject = normalizeSubject(subject) ?: return@withLock "联系人格式错误: $subject"
        val initial = BiliDataRuntimeCoordinator.snapshot()
        val user = initial.dynamic[uid] ?: return@withLock "还未订阅此人哦"

        val sourceRef = directSourceRef(normalizedSubject)
        if (sourceRef !in user.sourceRefs) return@withLock "还未订阅此人哦"

        var removedCompletely = false
        val result = BiliDataRuntimeCoordinator.mutateAndPersist { candidate ->
            val candidateUser = candidate.dynamic[uid] ?: return@mutateAndPersist
            val oldContacts = candidateUser.contacts.toSet()
            candidateUser.sourceRefs.remove(sourceRef)
            rebuildContactsFromSources(candidateUser, candidate.group)
            cleanupRemovedContactFilters(candidate.filter, uid, oldContacts - candidateUser.contacts)
            if (candidateUser.sourceRefs.isEmpty()) {
                removeUidCompletely(candidate, uid)
                removedCompletely = true
            }
        }
        if (!result.committed) return@withLock "保存失败，订阅未变更，请稍后重试"
        if (removedCompletely) unfollowUser(uid)
        if (isSelf) "取消订阅 ${user.name} 成功" else "为 $normalizedSubject 取消订阅 ${user.name} 成功"
    }

    /**
     * 为分组追加订阅来源，并把分组联系人展开成实际推送目标。
     */
    suspend fun addGroupSubscribe(uid: Long, groupName: String) = mutex.withLock {
        val initial = BiliDataRuntimeCoordinator.snapshot()
        val targetGroup = initial.group[groupName] ?: return@withLock "分组 $groupName 不存在"
        if (targetGroup.contacts.isEmpty()) return@withLock "分组 $groupName 中没有任何联系人"

        val sourceRef = groupSourceRef(groupName)
        val existing = initial.dynamic[uid]
        if (existing != null && sourceRef in existing.sourceRefs) return@withLock "分组 $groupName 之前订阅过这个人哦"

        val newName = if (existing == null) resolveNewSubscriptionName(uid).also { (_, error) -> if (error != null) return@withLock error }.first else existing.name
        var response = ""
        val result = BiliDataRuntimeCoordinator.mutateAndPersist(
            validate = { candidate -> if ((candidate.dynamic[uid]?.contacts?.size ?: 0) > MAX_CONTACTS_PER_UID) "联系人数量超限" else null },
        ) { candidate ->
            val subData = candidate.dynamic.getOrPut(uid) { SubData(requireNotNull(newName)) }
            val oldContacts = subData.contacts.toSet()
            subData.sourceRefs.add(sourceRef)
            rebuildContactsFromSources(subData, candidate.group)
            cleanupRemovedContactFilters(candidate.filter, uid, oldContacts - subData.contacts)
            response = "分组 $groupName 订阅 ${subData.name} 成功!"
        }
        if (result.committed) response else "保存失败，订阅未生效，请稍后重试"
    }

    /**
     * 删除分组订阅来源，并在无剩余来源时同步回收整条订阅记录。
     */
    suspend fun removeGroupSubscribe(uid: Long, groupName: String) = mutex.withLock {
        val initial = BiliDataRuntimeCoordinator.snapshot()
        val user = initial.dynamic[uid] ?: return@withLock "还未订阅此人哦"
        val sourceRef = groupSourceRef(groupName)
        if (sourceRef !in user.sourceRefs) return@withLock "分组 $groupName 未订阅该UP主"

        var removedCompletely = false
        val result = BiliDataRuntimeCoordinator.mutateAndPersist { candidate ->
            val candidateUser = candidate.dynamic[uid] ?: return@mutateAndPersist
            val oldContacts = candidateUser.contacts.toSet()
            candidateUser.sourceRefs.remove(sourceRef)
            rebuildContactsFromSources(candidateUser, candidate.group)
            cleanupRemovedContactFilters(candidate.filter, uid, oldContacts - candidateUser.contacts)
            if (candidateUser.sourceRefs.isEmpty()) { removeUidCompletely(candidate, uid); removedCompletely = true }
        }
        if (!result.committed) return@withLock "保存失败，订阅未变更，请稍后重试"
        if (removedCompletely) unfollowUser(uid)
        "分组 $groupName 取消订阅 ${user.name} 成功"
    }

    /**
     * 在分组成员变更后重建所有关联 UID 的联系人展开结果。
     */
    suspend fun refreshGroupRef(groupName: String) = mutex.withLock {
        val sourceRef = groupSourceRef(groupName)
        BiliDataRuntimeCoordinator.mutateAndPersist { candidate ->
            candidate.dynamic.forEach { (uid, subData) ->
                if (sourceRef !in subData.sourceRefs) return@forEach
                val oldContacts = subData.contacts.toSet()
                rebuildContactsFromSources(subData, candidate.group)
                cleanupRemovedContactFilters(candidate.filter, uid, oldContacts - subData.contacts)
            }
        }
    }

    /**
     * 在调用方候选内重建指定分组引用，供分组增删成员与订阅展开保持单事务。
     */
    internal fun refreshGroupRefIn(candidate: BiliDataWrapper, groupName: String) {
        val sourceRef = groupSourceRef(groupName)
        candidate.dynamic.forEach { (uid, subData) ->
            if (sourceRef !in subData.sourceRefs) return@forEach
            val oldContacts = subData.contacts.toSet()
            rebuildContactsFromSources(subData, candidate.group)
            cleanupRemovedContactFilters(candidate.filter, uid, oldContacts - subData.contacts)
        }
    }

    /**
     * 在调用方候选内删除分组来源，避免分组表和订阅来源分两次提交。
     */
    internal fun deleteGroupRefIn(candidate: BiliDataWrapper, groupName: String) {
        val sourceRef = groupSourceRef(groupName)
        candidate.dynamic.toMap().forEach { (uid, subData) ->
            if (sourceRef !in subData.sourceRefs) return@forEach
            val oldContacts = subData.contacts.toSet()
            subData.sourceRefs.remove(sourceRef)
            rebuildContactsFromSources(subData, candidate.group)
            cleanupRemovedContactFilters(candidate.filter, uid, oldContacts - subData.contacts)
            if (subData.sourceRefs.isEmpty()) removeUidCompletely(candidate, uid)
        }
    }

    /**
     * 在分组删除后移除全部关联来源，避免残留引用继续参与推送。
     */
    suspend fun deleteGroupRef(groupName: String) = mutex.withLock {
        val sourceRef = groupSourceRef(groupName)
        BiliDataRuntimeCoordinator.mutateAndPersist { candidate ->
            candidate.dynamic.toMap().forEach { (uid, subData) ->
                if (sourceRef !in subData.sourceRefs) return@forEach
                val oldContacts = subData.contacts.toSet()
                subData.sourceRefs.remove(sourceRef)
                rebuildContactsFromSources(subData, candidate.group)
                cleanupRemovedContactFilters(candidate.filter, uid, oldContacts - subData.contacts)
                if (subData.sourceRefs.isEmpty()) removeUidCompletely(candidate, uid)
            }
        }
    }

    // 兼容旧调用路径: /add 和快速命令都视为 direct 来源
    /**
     * 为旧调用路径保留直接订阅入口，避免历史命令在来源模型升级后失效。
     */
    suspend fun addSubscribe(uid: Long, subject: String, isSelf: Boolean = true): String {
        return addDirectSubscribe(uid, subject, isSelf)
    }

    // 兼容旧调用路径
    /**
     * 为旧调用路径保留取消订阅入口，继续复用新的来源清理逻辑。
     */
    suspend fun removeSubscribe(uid: Long, subject: String, isSelf: Boolean = true): String {
        return removeDirectSubscribe(uid, subject, isSelf)
    }

    /**
     * 移除目标会话的全部直接订阅痕迹，并同步清理关联过滤器与模板绑定。
     */
    suspend fun removeAllSubscribe(subject: String) = mutex.withLock {
        val normalizedSubject = normalizeSubject(subject) ?: subject
        val directRef = directSourceRef(normalizedSubject)

        BiliDataRuntimeCoordinator.mutateAndPersist { candidate ->
            candidate.filter.remove(normalizedSubject)
            candidate.group.forEach { (_, group) -> group.contacts.remove(normalizedSubject) }
            candidate.dynamic.toMap().forEach { (uid, subData) ->
                if (directRef !in subData.sourceRefs && normalizedSubject !in subData.contacts) return@forEach
                val oldContacts = subData.contacts.toSet()
                subData.sourceRefs.remove(directRef)
                rebuildContactsFromSources(subData, candidate.group)
                cleanupRemovedContactFilters(candidate.filter, uid, oldContacts - subData.contacts)
                if (subData.sourceRefs.isEmpty()) removeUidCompletely(candidate, uid)
            }
        }
    }

    /**
     * 汇总目标会话下的动态与番剧订阅，保证命令层查看口径一致。
     */
    suspend fun list(subject: String) = mutex.withLock {
        buildString {
            appendLine("目标: $subject")
            appendLine()
            appendLine("UP主: ")
            val c = dynamic.count { (uid, sub) ->
                if (containsEquivalentSubject(sub.contacts, subject)) {
                    appendLine("${sub.name}@$uid")
                    true
                } else {
                    false
                }
            }
            if (c == 0) appendLine("无")
            appendLine()
            appendLine("番剧: ")
            val cc = bangumi.count { (ssid, sub) ->
                if (containsEquivalentSubject(sub.contacts, subject)) {
                    appendLine("${sub.title}@ss$ssid")
                    true
                } else {
                    false
                }
            }
            if (cc == 0) appendLine("无")
            appendLine()
            append("共 ${c + cc} 个订阅")
        }
    }

    /**
     * 列出当前全部动态订阅及联系人规模，便于全局巡检。
     */
    suspend fun listAll() = mutex.withLock {
        var count = 0
        buildString {
            appendLine("名称@UID#订阅人数")
            appendLine()
            dynamic.forEach { (uid, sub) ->
                appendLine("${sub.name}@$uid#${sub.contacts.size}")
                count++
            }
            appendLine()
            append("共 $count 个订阅")
        }
    }

    /**
     * 统计指定 UID 或全部 UID 覆盖到的联系人，方便定位推送范围。
     */
    suspend fun listUser(uid: Long? = null) = mutex.withLock {
        buildString {
            val user = mutableSetOf<String>()
            if (uid == null) {
                dynamic.forEach { (_, sub) ->
                    user.addAll(sub.contacts)
                }
            } else {
                val u = dynamic[uid] ?: return@withLock "没有这个用户哦 [$uid]"
                appendLine("${u.name}[$uid]")
                appendLine()
                user.addAll(u.contacts)
            }

            val groups = mutableListOf<String>()
            val privates = mutableListOf<String>()
            val custom = mutableListOf<String>()

            user.forEach { subject ->
                val contact = parsePlatformContact(subject)
                when (contact?.type) {
                    top.bilibili.connector.PlatformChatType.GROUP -> groups.add("群@${contact.id}")
                    top.bilibili.connector.PlatformChatType.PRIVATE -> privates.add("私聊@${contact.id}")
                    else -> custom.add(subject)
                }
            }

            appendLine("====群====")
            if (groups.isEmpty()) appendLine("无") else groups.forEach { appendLine(it) }
            appendLine("====私聊====")
            if (privates.isEmpty()) appendLine("无") else privates.forEach { appendLine(it) }
            appendLine("====分组/其他====")
            if (custom.isEmpty()) appendLine("无") else custom.forEach { appendLine(it) }
            appendLine()
            append("共 ${user.size} 个联系人")
        }
    }
}
