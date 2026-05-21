package top.bilibili.webui.service

import top.bilibili.BiliConfigManager
import top.bilibili.BiliData
import top.bilibili.Group
import top.bilibili.connector.PlatformChatType
import top.bilibili.connector.PlatformContact
import top.bilibili.connector.PlatformType
import top.bilibili.service.DynamicService
import top.bilibili.service.PgcService
import top.bilibili.service.TemplateRuntimeCoordinator
import top.bilibili.utils.normalizeContactSubject
import top.bilibili.utils.toSubject
import top.bilibili.webui.model.WebUiSubscriptionCreateRequestDto
import top.bilibili.webui.model.WebUiSubscriptionMutationResultDto

/**
 * WebUI 订阅管理 facade 复用业务服务写入运行态，再通过 BiliConfigManager 统一持久化业务数据。
 */
class WebUiSubscriptionManagementFacade(
    private val saveDataAction: () -> Boolean = { BiliConfigManager.saveData() },
    private val addDynamicAction: suspend (Long, String) -> String = { uid, subject ->
        DynamicService.addDirectSubscribe(uid, subject, isSelf = false)
    },
    private val addGroupDynamicAction: suspend (Long, String) -> String = { uid, groupName ->
        DynamicService.addGroupSubscribe(uid, groupName)
    },
    private val followPgcAction: suspend (String, String) -> String = { id, subject ->
        PgcService.followPgc(id, subject)
    },
) {
    /**
     * 按页面选择的类型分发新增订阅请求，并保持各类型的校验口径和页面文案一致。
     */
    suspend fun createSubscription(request: WebUiSubscriptionCreateRequestDto): WebUiSubscriptionMutationResultDto {
        return when (request.type.trim().lowercase()) {
            "dynamic", "subscription" -> createDynamicSubscription(request)
            "group" -> createGroupSubscription(request)
            "bangumi" -> createBangumiSubscription(request)
            else -> validationFailure("添加类型无效")
        }
    }

    /**
     * 按卡片 ID 删除订阅聚合对象，并同步清理该对象下的附属过滤、模板、@全体和主题色。
     */
    suspend fun deleteSubscription(itemId: String): WebUiSubscriptionMutationResultDto {
        val parts = itemId.split(":", limit = 2)
        if (parts.size != 2 || parts[1].isBlank()) {
            return validationFailure("订阅标识无效")
        }
        val result = when (parts[0]) {
            "dynamic" -> deleteDynamicSubscription(parts[1])
            "group" -> deleteGroupSubscription(parts[1])
            "bangumi" -> deleteBangumiSubscription(parts[1])
            else -> validationFailure("订阅类型无效")
        }
        if (!result.success) {
            return result
        }
        return persistMutation(result)
    }

    /**
     * 普通订阅保存前必须具备 UID 和推送群组，并依赖关注链路返回成功文案确认生效。
     */
    private suspend fun createDynamicSubscription(
        request: WebUiSubscriptionCreateRequestDto,
    ): WebUiSubscriptionMutationResultDto {
        val uid = request.uid.trim().toLongOrNull()
        val subject = normalizeGroupTarget(request.targetGroup)
        val errors = mutableListOf<String>()
        if (uid == null || uid <= 0L) errors += "订阅UID必须填写"
        if (subject == null) errors += "推送群组必须填写"
        if (errors.isNotEmpty()) return validationFailure(errors)

        val message = addDynamicAction(uid!!, subject!!)
        if (!isSuccessMessage(message)) {
            return validationFailure("UID或群号错误：$message")
        }
        return persistMutation(success("dynamic:$uid", message))
    }

    /**
     * 分组新增只强制要求分组名；UID 和群组字段以最佳努力方式绑定，不阻断分组创建。
     */
    private suspend fun createGroupSubscription(
        request: WebUiSubscriptionCreateRequestDto,
    ): WebUiSubscriptionMutationResultDto {
        val groupName = request.groupName.trim()
        if (groupName.isBlank()) {
            return validationFailure("分组名必须填写")
        }

        val group = BiliData.group.getOrPut(groupName) {
            Group(
                name = groupName,
                creator = 0L,
                admin = mutableSetOf(),
                creatorContact = "",
                adminContacts = mutableSetOf(),
                contacts = mutableSetOf(),
            )
        }
        normalizeGroupTarget(request.targetGroup)?.let { subject ->
            group.contacts.add(subject)
        }

        val uid = request.uid.trim().toLongOrNull()
        val bindMessage = if (uid != null && uid > 0L && group.contacts.isNotEmpty()) {
            addGroupDynamicAction(uid, groupName)
        } else {
            null
        }
        val message = bindMessage ?: "分组 $groupName 已保存"
        return persistMutation(success("group:$groupName", message))
    }

    /**
     * 番剧订阅只允许 ep 或 ss 前缀，避免 WebUI 写入命令层尚未要求支持的 md 入口。
     */
    private suspend fun createBangumiSubscription(
        request: WebUiSubscriptionCreateRequestDto,
    ): WebUiSubscriptionMutationResultDto {
        val bangumiId = request.bangumiId.trim().lowercase()
        val subject = normalizeGroupTarget(request.targetGroup)
        val errors = mutableListOf<String>()
        if (!Regex("^(ep|ss)\\d{4,10}$").matches(bangumiId)) errors += "番剧号必须以 ep 或 ss 开头"
        if (subject == null) errors += "推送群组必须填写"
        if (errors.isNotEmpty()) return validationFailure(errors)

        val message = followPgcAction(bangumiId, subject!!)
        if (!isSuccessMessage(message)) {
            return validationFailure("番剧号或群号错误：$message")
        }
        return persistMutation(success("bangumi:$bangumiId", message))
    }

    /**
     * 删除动态卡片时按 UID 清理所有关联业务数据，保证卡片消失后不留下附属策略。
     */
    private fun deleteDynamicSubscription(rawUid: String): WebUiSubscriptionMutationResultDto {
        val uid = rawUid.toLongOrNull() ?: return validationFailure("订阅UID无效")
        if (!BiliData.dynamic.containsKey(uid)) {
            return validationFailure("订阅不存在")
        }
        removeUidPayload(uid)
        return success("dynamic:$uid", "订阅已删除")
    }

    /**
     * 删除分组卡片时把分组绑定的 UID 视为该卡片归属，一并清理 UID 下属策略和分组本体。
     */
    private fun deleteGroupSubscription(groupName: String): WebUiSubscriptionMutationResultDto {
        if (!BiliData.group.containsKey(groupName)) {
            return validationFailure("分组不存在")
        }
        val groupRef = "groupRef:$groupName"
        val linkedUids = BiliData.dynamic
            .filter { (_, subscription) -> groupRef in subscription.sourceRefs }
            .keys
            .toList()
        linkedUids.forEach(::removeUidPayload)
        BiliData.group.remove(groupName)
        removeScopedPayload(groupRef)
        return success("group:$groupName", "分组已删除")
    }

    /**
     * 删除番剧卡片时移除整条番剧订阅记录，番剧主题色随记录一起回收。
     */
    private fun deleteBangumiSubscription(rawSeasonId: String): WebUiSubscriptionMutationResultDto {
        val seasonId = rawSeasonId.toLongOrNull() ?: return validationFailure("番剧号无效")
        if (BiliData.bangumi.remove(seasonId) == null) {
            return validationFailure("番剧订阅不存在")
        }
        return success("bangumi:$seasonId", "番剧订阅已删除")
    }

    /**
     * UID 级删除同时覆盖过滤器、模板策略、@全体、冷却状态和主题色，避免附属配置孤儿化。
     */
    private fun removeUidPayload(uid: Long) {
        BiliData.dynamic.remove(uid)
        BiliData.filter.entries.removeIf { (_, filtersByUid) ->
            filtersByUid.remove(uid)
            filtersByUid.isEmpty()
        }
        TemplateRuntimeCoordinator.removeUidAcrossTypes(uid)
        BiliData.dynamicColorByUid.entries.removeIf { (_, colorsByUid) ->
            colorsByUid.remove(uid)
            colorsByUid.isEmpty()
        }
        BiliData.atAll.entries.removeIf { (_, atAllByUid) ->
            atAllByUid.remove(uid)
            atAllByUid.isEmpty()
        }
        BiliData.atAllCooldownUntil.keys.removeIf { key ->
            key.contains("|$uid|") || key.contains(".$uid.")
        }
    }

    /**
     * scope 级删除覆盖分组引用残留，特别是没有绑定 UID 时仍可能存在的空壳策略。
     */
    private fun removeScopedPayload(scope: String) {
        BiliData.filter.remove(scope)
        listOf("dynamic", "live", "liveClose").forEach { type ->
            TemplateRuntimeCoordinator.removeScope(type, scope)
        }
        BiliData.dynamicColorByUid.remove(scope)
        BiliData.atAll.remove(scope)
        BiliData.atAllCooldownUntil.keys.removeIf { key -> key.startsWith("$scope|") || key.startsWith("$scope.") }
    }

    /**
     * 页面群号输入默认按 OneBot11 群聊处理，完整 subject 和历史 group: 格式则先归一化。
     */
    private fun normalizeGroupTarget(raw: String): String? {
        val text = raw.trim()
        if (text.isBlank()) return null
        return normalizeContactSubject(text)
            ?: PlatformContact(PlatformType.ONEBOT11, PlatformChatType.GROUP, text).toSubject()
    }

    /**
     * 业务服务当前以中文文案表示结果，这里只把明确成功文案作为 WebUI 成功反馈。
     */
    private fun isSuccessMessage(message: String): Boolean {
        return message.contains("成功")
    }

    /**
     * 变更成功后统一落盘，持久化失败时返回可见错误而不是让前端误判已生效。
     */
    private fun persistMutation(result: WebUiSubscriptionMutationResultDto): WebUiSubscriptionMutationResultDto {
        val saved = runCatching { saveDataAction() }.getOrDefault(false)
        return if (saved) {
            result
        } else {
            WebUiSubscriptionMutationResultDto(
                success = false,
                message = "订阅已修改但持久化失败",
                itemId = result.itemId,
                validationErrors = listOf("BiliData save failed"),
            )
        }
    }

    /**
     * 成功结果保留 itemId，方便前端刷新后定位刚刚变更的卡片。
     */
    private fun success(itemId: String, message: String): WebUiSubscriptionMutationResultDto {
        return WebUiSubscriptionMutationResultDto(
            success = true,
            message = message,
            itemId = itemId,
        )
    }

    /**
     * 校验失败统一保留错误列表，前端可以直接展示第一条或完整列表。
     */
    private fun validationFailure(error: String): WebUiSubscriptionMutationResultDto {
        return validationFailure(listOf(error))
    }

    /**
     * 多字段校验失败保持同一响应结构，避免 route 层再拼接错误文案。
     */
    private fun validationFailure(errors: List<String>): WebUiSubscriptionMutationResultDto {
        return WebUiSubscriptionMutationResultDto(
            success = false,
            message = errors.joinToString("；"),
            validationErrors = errors,
        )
    }
}
