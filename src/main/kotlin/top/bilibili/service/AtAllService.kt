package top.bilibili.service

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.bilibili.AtAllType
import top.bilibili.BiliData
import top.bilibili.data.BiliMessage
import top.bilibili.data.DynamicMessage
import top.bilibili.data.DynamicType
import top.bilibili.data.LiveCloseMessage
import top.bilibili.data.LiveMessage
import top.bilibili.connector.PlatformChatType
import top.bilibili.core.BiliDataRuntimeCoordinator
import top.bilibili.utils.normalizeContactSubject
import top.bilibili.utils.parsePlatformContact

/**
 * 集中维护按会话和 UID 生效的 @全体 策略，避免推送路径直接操作原始存储。
 */
object AtAllService {
    private const val AT_ALL_COOLDOWN_MS = 2 * 60 * 60 * 1000L
    private val mutex = Mutex()

    private fun toAtAllType(type: String) =
        when (type.lowercase()) {
            "全部", "all", "a" -> AtAllType.ALL
            "全部动态", "dynamic", "d" -> AtAllType.DYNAMIC
            "直播", "live", "l" -> AtAllType.LIVE
            "视频", "video", "v" -> AtAllType.VIDEO
            "音乐", "music", "m" -> AtAllType.MUSIC
            "专栏", "article" -> AtAllType.ARTICLE
            else -> null
        }

    /**
     * 统一暴露 @全体 类型词判断，避免命令解析和策略写入维护两份映射表。
     */
    internal fun supportsType(type: String): Boolean = toAtAllType(type) != null

    /**
     * 写入 @全体 策略时顺带做联系人和 UID 范围校验，避免保存无效配置。
     */
    suspend fun addAtAll(type: String, uid: Long = 0L, subject: String): String = mutex.withLock {
        val atAllType = toAtAllType(type) ?: return@withLock "没有这个类型哦 [$type]"
        val normalizedSubject = normalizeContactSubject(subject) ?: return@withLock "联系人格式错误: $subject"
        val contact = parsePlatformContact(normalizedSubject) ?: return@withLock "联系人格式错误: $subject"
        if (contact.type != PlatformChatType.GROUP) return@withLock "仅群聊支持 @全体 策略"
        validateUidScope(uid, normalizedSubject)?.let { return@withLock it }

        val result = BiliDataRuntimeCoordinator.mutateAndPersist { candidate ->
            val list = candidate.atAll.getOrPut(normalizedSubject) { mutableMapOf() }.getOrPut(uid) { mutableSetOf() }
            when (atAllType) {
                AtAllType.ALL -> { list.clear(); list.add(atAllType) }
                AtAllType.DYNAMIC -> {
                    list.removeAll(listOf(AtAllType.ALL, AtAllType.VIDEO, AtAllType.MUSIC, AtAllType.ARTICLE)); list.add(atAllType)
                }
                AtAllType.LIVE -> { list.remove(AtAllType.ALL); list.add(atAllType) }
                else -> { list.remove(AtAllType.ALL); list.remove(AtAllType.DYNAMIC); list.add(atAllType) }
            }
        }
        if (result.committed) "添加成功" else "保存失败，设置未生效，请稍后重试"
    }

    /**
     * 删除指定作用域下的 @全体 策略，并在空桶时回收冗余节点。
     */
    suspend fun delAtAll(type: String, uid: Long = 0L, subject: String): String = mutex.withLock {
        val atAllType = toAtAllType(type) ?: return@withLock "没有这个类型哦 [$type]"
        val normalizedSubject = normalizeContactSubject(subject) ?: return@withLock "联系人格式错误: $subject"
        validateUidScope(uid, normalizedSubject)?.let { return@withLock it }

        val subjectMap = BiliDataRuntimeCoordinator.snapshot().atAll[normalizedSubject] ?: return@withLock "删除失败"
        val uidMap = subjectMap[uid] ?: return@withLock "删除失败"
        if (atAllType !in uidMap) return@withLock "删除失败"
        val result = BiliDataRuntimeCoordinator.mutateAndPersist { candidate ->
            val candidateSubject = candidate.atAll[normalizedSubject] ?: return@mutateAndPersist
            candidateSubject[uid]?.remove(atAllType)
            if (candidateSubject[uid].isNullOrEmpty()) candidateSubject.remove(uid)
            if (candidateSubject.isEmpty()) candidate.atAll.remove(normalizedSubject)
        }
        if (result.committed) "删除成功" else "保存失败，设置未变更，请稍后重试"
    }

    /**
     * 按会话或 UID 汇总 @全体 策略，方便命令层直接回显当前配置。
     */
    suspend fun listAtAll(uid: Long = 0L, subject: String): String = mutex.withLock {
        val normalizedSubject = normalizeContactSubject(subject) ?: return@withLock "联系人格式错误: $subject"
        if (uid == 0L) {
            val all = BiliData.atAll[normalizedSubject]
            if (all.isNullOrEmpty()) return@withLock "没有At全体项哦"
            return@withLock buildString {
                all.toSortedMap().forEach { (scopeUid, items) ->
                    appendLine("UID($scopeUid): ${items.joinToString(",") { it.value }}")
                }
            }.trim()
        }

        validateUidScope(uid, normalizedSubject)?.let { return@withLock it }
        val list = BiliData.atAll[normalizedSubject]?.get(uid)
        if (list.isNullOrEmpty()) return@withLock "没有At全体项哦"
        buildString { list.forEach { appendLine(it.value) } }.trim()
    }

    /**
     * 在推送前统一判断是否需要 @全体，避免消息链路各自重复实现类型映射逻辑。
     */
    suspend fun shouldAtAll(subject: String, uid: Long, message: BiliMessage): Boolean {
        return shouldAtAll(subject, uid, message, System.currentTimeMillis())
    }

    /**
     * 在推送前统一判断是否需要 @全体，并在同群同 UID 同实际类型命中冷却时直接降级。
     */
    suspend fun shouldAtAll(subject: String, uid: Long, message: BiliMessage, now: Long): Boolean = mutex.withLock {
        val normalizedSubject = normalizeContactSubject(subject) ?: return@withLock false
        val list = BiliData.atAll[normalizedSubject]?.get(uid) ?: return@withLock false
        if (list.isEmpty()) return@withLock false

        val actualType = resolveActualType(message) ?: return@withLock false
        if (isCooldownActive(normalizedSubject, uid, actualType, now)) return@withLock false
        if (AtAllType.ALL in list) return@withLock true

        return@withLock when (actualType) {
            AtAllType.LIVE -> AtAllType.LIVE in list
            AtAllType.VIDEO -> AtAllType.DYNAMIC in list || AtAllType.VIDEO in list
            AtAllType.MUSIC -> AtAllType.DYNAMIC in list || AtAllType.MUSIC in list
            AtAllType.ARTICLE -> AtAllType.DYNAMIC in list || AtAllType.ARTICLE in list
            AtAllType.DYNAMIC -> AtAllType.DYNAMIC in list
            AtAllType.ALL -> false
        }
    }

    /**
     * 在带 @全体 的消息确认发送成功后记录冷却结束时间，避免失败或降级路径误占用配额。
     */
    suspend fun recordAtAllSuccess(subject: String, uid: Long, message: BiliMessage, now: Long = System.currentTimeMillis()) = mutex.withLock {
        val normalizedSubject = normalizeContactSubject(subject) ?: return@withLock
        val actualType = resolveActualType(message) ?: return@withLock
        BiliDataRuntimeCoordinator.mutateAndPersist { candidate ->
            candidate.atAllCooldownUntil[cooldownKey(normalizedSubject, uid, actualType)] = now + AT_ALL_COOLDOWN_MS
        }
    }

    /**
     * 把运行时消息模型统一映射为冷却粒度使用的实际通知类型。
     */
    internal fun resolveActualType(message: BiliMessage): AtAllType? {
        return when (message) {
            is DynamicMessage -> {
                mapDynamicTypeToAtAll(message.type)
            }
            is LiveMessage -> AtAllType.LIVE
            is LiveCloseMessage -> null
        }
    }

    private fun mapDynamicTypeToAtAll(type: DynamicType): AtAllType {
        return when (type) {
            DynamicType.DYNAMIC_TYPE_AV -> AtAllType.VIDEO
            DynamicType.DYNAMIC_TYPE_MUSIC -> AtAllType.MUSIC
            DynamicType.DYNAMIC_TYPE_ARTICLE -> AtAllType.ARTICLE
            else -> AtAllType.DYNAMIC
        }
    }

    /**
     * 冷却命中时顺手清理过期键，避免 7x24 运行下持久化表持续累积历史垃圾。
     */
    private fun isCooldownActive(subject: String, uid: Long, actualType: AtAllType, now: Long): Boolean {
        val key = cooldownKey(subject, uid, actualType)
        val cooldownUntil = BiliData.atAllCooldownUntil[key] ?: return false
        if (cooldownUntil <= now) {
            // 过期清理也走候选事务，避免读取线程直接修改共享持久化集合。
            BiliDataRuntimeCoordinator.mutateAndPersist { candidate -> candidate.atAllCooldownUntil.remove(key) }
            return false
        }
        return true
    }

    /**
     * 冷却表键由归一化联系人、订阅 UID 与实际通知类型组成，保持业务判断粒度稳定。
     */
    private fun cooldownKey(subject: String, uid: Long, actualType: AtAllType): String {
        return "$subject|$uid|${actualType.name}"
    }

    private fun validateUidScope(uid: Long, subject: String): String? {
        if (uid <= 0L) return "请指定有效 UID（必须是正整数）"
        if (!isFollow(uid, subject)) return "该群未订阅 UID: $uid"
        return null
    }
}
