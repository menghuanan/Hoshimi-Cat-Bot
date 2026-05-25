package top.bilibili.webui.service

import top.bilibili.AtAllType
import top.bilibili.BiliConfig
import top.bilibili.BiliConfigManager
import top.bilibili.BiliData
import top.bilibili.DynamicFilter
import top.bilibili.DynamicFilterType
import top.bilibili.FilterMode
import top.bilibili.Group
import top.bilibili.TemplatePolicy
import top.bilibili.connector.PlatformChatType
import top.bilibili.connector.PlatformContact
import top.bilibili.connector.PlatformType
import top.bilibili.service.DynamicService
import top.bilibili.service.PgcService
import top.bilibili.service.RemoveTemplateResult
import top.bilibili.service.TemplateRuntimeCoordinator
import top.bilibili.utils.normalizeContactSubject
import top.bilibili.utils.toSubject
import top.bilibili.webui.model.WebUiSubscriptionAtAllItemDto
import top.bilibili.webui.model.WebUiSubscriptionAtAllListDto
import top.bilibili.webui.model.WebUiSubscriptionCreateRequestDto
import top.bilibili.webui.model.WebUiSubscriptionFilterItemDto
import top.bilibili.webui.model.WebUiSubscriptionFilterListDto
import top.bilibili.webui.model.WebUiSubscriptionFilterSaveRequestDto
import top.bilibili.webui.model.WebUiSubscriptionMutationResultDto
import top.bilibili.webui.model.WebUiSubscriptionTemplateItemDto
import top.bilibili.webui.model.WebUiSubscriptionTemplateListDto
import top.bilibili.webui.model.WebUiSubscriptionTemplateSaveRequestDto
import top.bilibili.webui.model.WebUiSubscriptionThemeDto

/**
 * WebUI 订阅管理 facade 复用业务服务写入运行态，再通过 BiliConfigManager 统一持久化业务数据。
 * 时钟通过构造参数注入，便于卡片管理更新时间在测试中保持确定性。
 */
class WebUiSubscriptionManagementFacade(
    private val configProvider: () -> BiliConfig = { runCatching { BiliConfigManager.config }.getOrDefault(BiliConfig()) },
    private val saveConfigAction: () -> Boolean = { BiliConfigManager.saveConfig() },
    private val saveDataAction: () -> Boolean = { BiliConfigManager.saveData() },
    private val currentTimeMillisProvider: () -> Long = { System.currentTimeMillis() },
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
    // 模板类型规格集中放在 facade 内，避免列表、保存和随机开关维护三套类型映射。
    private val templateTypeSpecs = listOf(
        TemplateTypeSpec(
            uiType = "dynamic",
            storageType = "dynamic",
            label = "动态",
            configMap = { config -> config.templateConfig.dynamicPush },
            policyMap = { BiliData.dynamicTemplatePolicyByScope },
        ),
        TemplateTypeSpec(
            uiType = "live",
            storageType = "live",
            label = "开播",
            configMap = { config -> config.templateConfig.livePush },
            policyMap = { BiliData.liveTemplatePolicyByScope },
        ),
        TemplateTypeSpec(
            uiType = "liveClose",
            storageType = "liveClose",
            label = "下播",
            configMap = { config -> config.templateConfig.liveClose },
            policyMap = { BiliData.liveCloseTemplatePolicyByScope },
        ),
    )

    // 主题色编辑框接受空值恢复默认；非空时只接受单个 HEX 颜色，避免写入渐变串。
    private val hexColorRegex = Regex("^#(?:[0-9a-fA-F]{6}|[0-9a-fA-F]{3})$")

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
     * 读取当前订阅关联的过滤规则，并按底层 t/r 索引拆成可单独编辑的行。
     */
    fun listSubscriptionFilters(itemId: String): WebUiSubscriptionFilterListDto {
        val context = resolveEditContext(itemId) ?: return WebUiSubscriptionFilterListDto(emptyList())
        val rows = mutableListOf<WebUiSubscriptionFilterItemDto>()
        context.filterScopes.forEach { scope ->
            context.uids.forEach { uid ->
                val filter = BiliData.filter[scope]?.get(uid) ?: return@forEach
                filter.typeSelect.list.forEachIndexed { index, type ->
                    val prefix = "t$index"
                    rows += WebUiSubscriptionFilterItemDto(
                        key = filterKey(scope, uid, "type", index),
                        prefix = prefix,
                        kind = "type",
                        label = "标签过滤",
                        mode = filter.typeSelect.mode.value,
                        content = type.value,
                        scope = scope,
                        summary = "$prefix 标签过滤 ${type.value}",
                    )
                }
                filter.regularSelect.list.forEachIndexed { index, regex ->
                    val prefix = "r$index"
                    rows += WebUiSubscriptionFilterItemDto(
                        key = filterKey(scope, uid, "regex", index),
                        prefix = prefix,
                        kind = "regex",
                        label = "正则过滤",
                        mode = filter.regularSelect.mode.value,
                        content = regex,
                        scope = scope,
                        summary = "$prefix 正则过滤 $regex",
                    )
                }
            }
        }
        return WebUiSubscriptionFilterListDto(rows)
    }

    /**
     * 新增或编辑过滤器；新增会展开到当前订阅的全部推送群，编辑只更新 key 指向的单条规则。
     */
    fun saveSubscriptionFilter(
        itemId: String,
        request: WebUiSubscriptionFilterSaveRequestDto,
    ): WebUiSubscriptionMutationResultDto {
        val context = resolveEditContext(itemId) ?: return validationFailure("订阅不存在或不支持过滤器")
        val kind = normalizeFilterKind(request.kind) ?: return validationFailure("过滤方式无效")
        val mode = parseFilterMode(request.mode) ?: return validationFailure("黑白名单类型无效")
        val content = request.content.trim()
        if (kind == "regex" && content.isBlank()) {
            return validationFailure("正则内容必须填写")
        }
        val type = if (kind == "type") parseDynamicFilterType(content) ?: return validationFailure("标签类型无效") else null

        if (request.key.isNotBlank()) {
            val parsedKey = parseFilterKey(request.key) ?: return validationFailure("过滤器标识无效")
            if (!context.ownsFilterKey(parsedKey) || !updateFilterRow(parsedKey, kind, mode, content, type)) {
                return validationFailure("过滤器不存在")
            }
        } else {
            val scopes = if (context.filterScopes.isNotEmpty()) context.filterScopes else context.contactScopes
            if (scopes.isEmpty() || context.uids.isEmpty()) {
                return validationFailure("当前订阅没有可写入过滤器的推送群")
            }
            scopes.forEach { scope ->
                context.uids.forEach { uid ->
                    val filter = BiliData.filter.getOrPut(scope) { mutableMapOf() }.getOrPut(uid) { DynamicFilter() }
                    appendFilterRow(filter, kind, mode, content, type)
                }
            }
        }
        markSubscriptionCardUpdated(itemId)
        return persistMutation(success(itemId, "过滤器已保存"))
    }

    /**
     * 删除 key 指向的单条过滤规则，并在空过滤器容器出现时同步回收。
     */
    fun deleteSubscriptionFilter(itemId: String, key: String): WebUiSubscriptionMutationResultDto {
        val context = resolveEditContext(itemId) ?: return validationFailure("订阅不存在或不支持过滤器")
        val parsedKey = parseFilterKey(key) ?: return validationFailure("过滤器标识无效")
        if (!context.ownsFilterKey(parsedKey)) return validationFailure("过滤器不存在")
        val filter = BiliData.filter[parsedKey.scope]?.get(parsedKey.uid) ?: return validationFailure("过滤器不存在")
        val removed = when (parsedKey.kind) {
            "type" -> filter.typeSelect.list.removeAtOrNull(parsedKey.index)
            "regex" -> filter.regularSelect.list.removeAtOrNull(parsedKey.index)
            else -> null
        } ?: return validationFailure("过滤器不存在")
        cleanupEmptyFilter(parsedKey.scope, parsedKey.uid)
        markSubscriptionCardUpdated(itemId)
        return persistMutation(success(itemId, "已删除 ${if (removed is DynamicFilterType) removed.value else removed}"))
    }

    /**
     * 读取当前订阅策略中绑定的模板，同时带回对应模板正文供编辑页回填。
     */
    fun listSubscriptionTemplates(itemId: String): WebUiSubscriptionTemplateListDto {
        val context = resolveEditContext(itemId) ?: return WebUiSubscriptionTemplateListDto(emptyList(), false)
        val config = configProvider()
        val rows = linkedMapOf<String, WebUiSubscriptionTemplateItemDto>()
        var randomEnabled = false
        templateTypeSpecs.forEach { spec ->
            context.templateScopes.forEach { scope ->
                context.uids.forEach { uid ->
                    val policy = spec.policyMap()[scope]?.get(uid) ?: return@forEach
                    randomEnabled = randomEnabled || policy.randomEnabled
                    policy.templates.forEach { name ->
                        val key = templateKey(scope, uid, spec.storageType, name)
                        rows[key] = WebUiSubscriptionTemplateItemDto(
                            key = key,
                            type = spec.uiType,
                            typeLabel = spec.label,
                            name = name,
                            content = spec.configMap(config)[name].orEmpty(),
                            scope = scope,
                        )
                    }
                }
            }
        }
        return WebUiSubscriptionTemplateListDto(rows.values.toList(), randomEnabled)
    }

    /**
     * 保存模板正文并绑定到当前订阅策略；编辑旧模板时只移除所选策略里的旧名称。
     */
    fun saveSubscriptionTemplate(
        itemId: String,
        request: WebUiSubscriptionTemplateSaveRequestDto,
    ): WebUiSubscriptionMutationResultDto {
        val context = resolveEditContext(itemId) ?: return validationFailure("订阅不存在或不支持模板")
        val spec = templateSpec(request.type) ?: return validationFailure("模板类型无效")
        val name = request.name.trim()
        if (name.isBlank()) {
            return validationFailure("模板名称必须填写")
        }
        if (context.templateScopes.isEmpty() || context.uids.isEmpty()) {
            return validationFailure("当前订阅没有可写入模板的作用域")
        }

        val config = configProvider()
        spec.configMap(config)[name] = request.content
        parseTemplateKey(request.key)?.let { key ->
            if (!context.ownsTemplateKey(key)) {
                return validationFailure("模板不存在")
            }
            if (key.type != spec.storageType || key.name != name) {
                TemplateRuntimeCoordinator.removeTemplate(key.type, key.scope, key.uid, key.name)
            }
            TemplateRuntimeCoordinator.appendTemplate(spec.storageType, key.scope, key.uid, name)
        } ?: run {
            context.templateScopes.forEach { scope ->
                context.uids.forEach { uid ->
                    TemplateRuntimeCoordinator.appendTemplate(spec.storageType, scope, uid, name)
                }
            }
        }

        markSubscriptionCardUpdated(itemId)
        return persistConfigAndData(success(itemId, "模板已保存"))
    }

    /**
     * 删除当前订阅策略中的单条模板绑定；模板正文保留在全局配置里，避免影响其他订阅复用。
     */
    fun deleteSubscriptionTemplate(itemId: String, key: String): WebUiSubscriptionMutationResultDto {
        val context = resolveEditContext(itemId) ?: return validationFailure("订阅不存在或不支持模板")
        val parsedKey = parseTemplateKey(key) ?: return validationFailure("模板标识无效")
        if (!context.ownsTemplateKey(parsedKey)) return validationFailure("模板不存在")
        val result = TemplateRuntimeCoordinator.removeTemplate(parsedKey.type, parsedKey.scope, parsedKey.uid, parsedKey.name)
        if (result == RemoveTemplateResult.POLICY_MISSING || result == RemoveTemplateResult.TEMPLATE_MISSING) {
            return validationFailure("模板不存在")
        }
        markSubscriptionCardUpdated(itemId)
        return persistMutation(success(itemId, "模板已删除"))
    }

    /**
     * 随机模板开关写入当前订阅全部已有模板策略，保证 WebUI 开关和推送运行态一致。
     */
    fun setSubscriptionTemplateRandom(itemId: String, enabled: Boolean): WebUiSubscriptionMutationResultDto {
        val context = resolveEditContext(itemId) ?: return validationFailure("订阅不存在或不支持模板")
        var changed = false
        templateTypeSpecs.forEach { spec ->
            context.templateScopes.forEach { scope ->
                context.uids.forEach { uid ->
                    if (TemplateRuntimeCoordinator.setRandomEnabled(spec.storageType, scope, uid, enabled)) {
                        changed = true
                    }
                }
            }
        }
        if (!changed) {
            return validationFailure("当前订阅未配置模板策略")
        }
        markSubscriptionCardUpdated(itemId)
        return persistMutation(success(itemId, if (enabled) "随机模板已开启" else "随机模板已关闭"))
    }

    /**
     * @全体列表按类型聚合群号，形成“直播 10001 +2”这种紧凑摘要。
     */
    fun listSubscriptionAtAll(itemId: String): WebUiSubscriptionAtAllListDto {
        val context = resolveEditContext(itemId) ?: return WebUiSubscriptionAtAllListDto(emptyList())
        val groupsByType = linkedMapOf<AtAllType, MutableSet<String>>()
        resolveAtAllScopes(context).forEach { scope ->
            context.uids.forEach { uid ->
                BiliData.atAll[scope]?.get(uid)?.forEach { type ->
                    groupsByType.getOrPut(type) { linkedSetOf() } += scope
                }
            }
        }
        val items = groupsByType.map { (type, groups) ->
            WebUiSubscriptionAtAllItemDto(
                key = type.name,
                type = type.value,
                summary = "${type.value} ${formatAtAllGroups(groups.toList())}",
                groups = groups.toList(),
            )
        }
        return WebUiSubscriptionAtAllListDto(items)
    }

    /**
     * 新增 @全体策略会展开到当前订阅全部推送群，冲突规则复用命令服务的 ALL/DYNAMIC 语义。
     */
    fun saveSubscriptionAtAll(itemId: String, type: String, targetGroups: List<String> = emptyList()): WebUiSubscriptionMutationResultDto {
        val context = resolveEditContext(itemId) ?: return validationFailure("订阅不存在或不支持 @全体")
        val atAllType = parseAtAllType(type) ?: return validationFailure("@全体类型无效")
        if (targetGroups.isEmpty()) {
            return validationFailure("目标群聊必须至少选择一个")
        }
        val writableTargets = context.contactScopes.ifEmpty { context.filterScopes }
        val scopes = targetGroups.distinct().filter { it in writableTargets }
        if (scopes.isEmpty()) {
            return validationFailure("目标群聊必须至少选择一个")
        }
        if (context.uids.isEmpty()) {
            return validationFailure("当前订阅没有可写入 @全体 的推送群")
        }
        resolveAtAllScopes(context).filterNot { it in scopes }.forEach { scope ->
            context.uids.forEach { uid ->
                BiliData.atAll[scope]?.get(uid)?.remove(atAllType)
                cleanupEmptyAtAll(scope, uid)
            }
        }
        scopes.forEach { scope ->
            context.uids.forEach { uid ->
                val set = BiliData.atAll.getOrPut(scope) { mutableMapOf() }.getOrPut(uid) { mutableSetOf() }
                applyAtAllType(set, atAllType)
            }
        }
        markSubscriptionCardUpdated(itemId)
        return persistMutation(success(itemId, "@全体已保存"))
    }

    /**
     * 删除指定 @全体类型时只移除当前订阅 UID 下的该类型，不影响同群其他 UID。
     */
    fun deleteSubscriptionAtAll(itemId: String, key: String): WebUiSubscriptionMutationResultDto {
        val context = resolveEditContext(itemId) ?: return validationFailure("订阅不存在或不支持 @全体")
        val atAllType = parseAtAllType(key) ?: return validationFailure("@全体类型无效")
        var changed = false
        resolveAtAllScopes(context).forEach { scope ->
            context.uids.forEach { uid ->
                val set = BiliData.atAll[scope]?.get(uid) ?: return@forEach
                changed = set.remove(atAllType) || changed
                cleanupEmptyAtAll(scope, uid)
            }
        }
        if (!changed) {
            return validationFailure("@全体配置不存在")
        }
        markSubscriptionCardUpdated(itemId)
        return persistMutation(success(itemId, "@全体已删除"))
    }

    /**
     * 主题色读取优先返回当前订阅已有的第一条作用域绑定，番剧则读取番剧自身颜色字段。
     */
    fun readSubscriptionTheme(itemId: String): WebUiSubscriptionThemeDto {
        val context = resolveEditContext(itemId) ?: return WebUiSubscriptionThemeDto("")
        if (context.kind == "bangumi") {
            return WebUiSubscriptionThemeDto(BiliData.bangumi[context.primaryBangumiId]?.color.orEmpty())
        }
        context.contactScopes.forEach { scope ->
            context.uids.forEach { uid ->
                val color = BiliData.dynamicColorByUid[scope]?.get(uid)
                if (!color.isNullOrBlank()) {
                    return WebUiSubscriptionThemeDto(color)
                }
            }
        }
        return WebUiSubscriptionThemeDto("")
    }

    /**
     * 主题色保存接受空值恢复默认色；非空值只接受单个 HEX 颜色，并展开写入当前订阅的全部推送群。
     */
    fun saveSubscriptionTheme(itemId: String, color: String): WebUiSubscriptionMutationResultDto {
        val context = resolveEditContext(itemId) ?: return validationFailure("订阅不存在或不支持主题色")
        val normalizedColor = color.trim()
        // 空主题色沿用前端“恢复默认”语义，只清除当前订阅范围内的颜色覆盖。
        if (normalizedColor.isBlank()) {
            if (context.kind == "bangumi") {
                val bangumi = BiliData.bangumi[context.primaryBangumiId] ?: return validationFailure("番剧订阅不存在")
                bangumi.color = null
            } else {
                if (context.contactScopes.isEmpty() || context.uids.isEmpty()) {
                    return validationFailure("当前订阅没有可写入主题色的推送群")
                }
                context.contactScopes.forEach { scope ->
                    val colorsByUid = BiliData.dynamicColorByUid[scope] ?: return@forEach
                    context.uids.forEach { uid -> colorsByUid.remove(uid) }
                    if (colorsByUid.isEmpty()) {
                        BiliData.dynamicColorByUid.remove(scope)
                    }
                }
            }
            markSubscriptionCardUpdated(itemId)
            return persistMutation(success(itemId, "主题色已恢复默认"))
        }
        if (!hexColorRegex.matches(normalizedColor)) {
            return validationFailure("HEX颜色格式错误")
        }
        if (context.kind == "bangumi") {
            val bangumi = BiliData.bangumi[context.primaryBangumiId] ?: return validationFailure("番剧订阅不存在")
            bangumi.color = normalizedColor
        } else {
            if (context.contactScopes.isEmpty() || context.uids.isEmpty()) {
                return validationFailure("当前订阅没有可写入主题色的推送群")
            }
            context.contactScopes.forEach { scope ->
                val byUid = BiliData.dynamicColorByUid.getOrPut(scope) { mutableMapOf() }
                context.uids.forEach { uid -> byUid[uid] = normalizedColor }
            }
        }
        markSubscriptionCardUpdated(itemId)
        return persistMutation(success(itemId, "主题色已保存"))
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
        markSubscriptionCardUpdated("dynamic:$uid")
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
        markSubscriptionCardUpdated("group:$groupName")
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

        val previousBangumiContacts = BiliData.bangumi.mapValues { (_, bangumi) -> bangumi.contacts.toSet() }
        val message = followPgcAction(bangumiId, subject!!)
        if (!isSuccessMessage(message)) {
            return validationFailure("番剧号或群号错误：$message")
        }
        markBangumiCardUpdated(bangumiId, previousBangumiContacts)
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
        BiliData.subscriptionCardUpdatedAt.remove("group:$groupName")
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
        BiliData.subscriptionCardUpdatedAt.remove("bangumi:$seasonId")
        return success("bangumi:$seasonId", "番剧订阅已删除")
    }

    /**
     * UID 级删除同时覆盖过滤器、模板策略、@全体、冷却状态和主题色，避免附属配置孤儿化。
     */
    private fun removeUidPayload(uid: Long) {
        BiliData.dynamic.remove(uid)
        BiliData.subscriptionCardUpdatedAt.remove("dynamic:$uid")
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
     * 记录 WebUI 卡片管理信息更新时间，避免列表页用推送内容时间误导用户。
     */
    private fun markSubscriptionCardUpdated(itemId: String) {
        BiliData.subscriptionCardUpdatedAt[itemId] = currentTimeMillisProvider().coerceAtLeast(0L)
    }

    /**
     * 番剧新增入口保留原始 ep/ss 标识，同时为 ss 前缀写入概览卡片使用的季 ID。
     */
    private fun markBangumiCardUpdated(bangumiId: String, previousContactsBySeason: Map<Long, Set<String>>) {
        markSubscriptionCardUpdated("bangumi:$bangumiId")
        if (bangumiId.startsWith("ss")) {
            bangumiId.removePrefix("ss").toLongOrNull()?.let { seasonId ->
                markSubscriptionCardUpdated("bangumi:$seasonId")
            }
        }
        // ep 入口只在业务服务返回后才能知道最终 season id，因此用联系人集合变化反查实际卡片。
        BiliData.bangumi.forEach { (seasonId, bangumi) ->
            if (previousContactsBySeason[seasonId] != bangumi.contacts.toSet()) {
                markSubscriptionCardUpdated("bangumi:$seasonId")
            }
        }
    }

    /**
     * 编辑上下文把卡片 ID 解析成 UID、联系人 scope 和模板 scope，供四类配置页共用。
     */
    private fun resolveEditContext(itemId: String): SubscriptionEditContext? {
        val parts = itemId.split(":", limit = 2)
        if (parts.size != 2) return null
        return when (parts[0]) {
            "dynamic" -> {
                val uid = parts[1].toLongOrNull() ?: return null
                val subscription = BiliData.dynamic[uid] ?: return null
                val groupRefs = subscription.sourceRefs.filter { it.startsWith("groupRef:") }
                SubscriptionEditContext(
                    kind = "dynamic",
                    itemId = itemId,
                    uids = listOf(uid),
                    contactScopes = subscription.contacts.sorted(),
                    templateScopes = (groupRefs.ifEmpty { subscription.contacts }).sorted(),
                    filterScopes = resolveFilterScopes(uid),
                )
            }
            "group" -> {
                val group = BiliData.group[parts[1]] ?: return null
                val groupRef = "groupRef:${group.name}"
                val uids = BiliData.dynamic
                    .filter { (_, subscription) -> groupRef in subscription.sourceRefs }
                    .keys
                    .sorted()
                SubscriptionEditContext(
                    kind = "group",
                    itemId = itemId,
                    uids = uids,
                    contactScopes = group.contacts.sorted(),
                    templateScopes = listOf(groupRef),
                    filterScopes = resolveFilterScopes(uids),
                )
            }
            "bangumi" -> {
                val seasonId = parts[1].toLongOrNull() ?: return null
                if (!BiliData.bangumi.containsKey(seasonId)) return null
                SubscriptionEditContext(
                    kind = "bangumi",
                    itemId = itemId,
                    primaryBangumiId = seasonId,
                )
            }
            else -> null
        }
    }

    /**
     * 过滤器列表和写入都围绕实际存在的 uid 规则桶展开，避免漏掉 groupRef 或历史 scope。
     */
    private fun resolveFilterScopes(uid: Long): List<String> {
        return BiliData.filter.entries
            .filter { (_, filtersByUid) -> filtersByUid.containsKey(uid) }
            .map { (scope, _) -> scope }
            .sorted()
    }

    /**
     * 过滤器列表和写入都围绕实际存在的 uid 规则桶展开，避免漏掉 groupRef 或历史 scope。
     */
    private fun resolveFilterScopes(uids: List<Long>): List<String> {
        return uids.flatMap { resolveFilterScopes(it) }.distinct().sorted()
    }

    /**
     * 过滤器 key 使用无斜杠分隔格式，便于前端安全放入 REST path。
     */
    private fun filterKey(scope: String, uid: Long, kind: String, index: Int): String {
        return listOf(scope, uid.toString(), kind, index.toString()).joinToString("|")
    }

    /**
     * 解析过滤器 key 并做最小合法性检查，避免删除请求误伤其他结构。
     */
    private fun parseFilterKey(key: String): ParsedFilterKey? {
        val parts = key.split("|")
        if (parts.size != 4) return null
        return ParsedFilterKey(
            scope = parts[0],
            uid = parts[1].toLongOrNull() ?: return null,
            kind = normalizeFilterKind(parts[2]) ?: return null,
            index = parts[3].toIntOrNull() ?: return null,
        )
    }

    /**
     * WebUI 和命令文案都可能传入正则/标签的中文或英文形式，这里统一收敛到底层 kind。
     */
    private fun normalizeFilterKind(kind: String): String? {
        return when (kind.trim().lowercase()) {
            "regex", "regular", "正则" -> "regex"
            "type", "tag", "label", "标签" -> "type"
            else -> null
        }
    }

    /**
     * 黑白名单输入统一兼容中文、英文和枚举名，便于前端显示中文但测试使用短英文。
     */
    private fun parseFilterMode(mode: String): FilterMode? {
        return when (mode.trim().lowercase()) {
            "black", "blacklist", "black_list", "黑名单" -> FilterMode.BLACK_LIST
            "white", "whitelist", "white_list", "白名单" -> FilterMode.WHITE_LIST
            else -> null
        }
    }

    /**
     * 标签过滤输入复用 DynamicFilterType 的中文值，避免前端暴露枚举名。
     */
    private fun parseDynamicFilterType(content: String): DynamicFilterType? {
        val text = content.trim()
        return DynamicFilterType.entries.firstOrNull { it.value == text || it.name.equals(text, ignoreCase = true) }
    }

    /**
     * 更新 key 指向的单条过滤规则；如果切换过滤方式，则先删除旧项再追加到目标列表。
     */
    private fun updateFilterRow(
        key: ParsedFilterKey,
        kind: String,
        mode: FilterMode,
        content: String,
        type: DynamicFilterType?,
    ): Boolean {
        val filter = BiliData.filter[key.scope]?.get(key.uid) ?: return false
        val removed = when (key.kind) {
            "type" -> filter.typeSelect.list.removeAtOrNull(key.index)
            "regex" -> filter.regularSelect.list.removeAtOrNull(key.index)
            else -> null
        } ?: return false
        appendFilterRow(filter, kind, mode, content, type)
        cleanupEmptyFilter(key.scope, key.uid)
        return removed is DynamicFilterType || removed is String
    }

    /**
     * 向指定过滤器容器追加一条规则，并同步更新对应列表的黑白名单模式。
     */
    private fun appendFilterRow(
        filter: DynamicFilter,
        kind: String,
        mode: FilterMode,
        content: String,
        type: DynamicFilterType?,
    ) {
        if (kind == "type") {
            filter.typeSelect.mode = mode
            type?.let { filter.typeSelect.list += it }
        } else {
            filter.regularSelect.mode = mode
            filter.regularSelect.list += content
        }
    }

    /**
     * 过滤器两类列表都为空时回收对应 UID 和 subject 桶，避免保存出空壳配置。
     */
    private fun cleanupEmptyFilter(scope: String, uid: Long) {
        val byUid = BiliData.filter[scope] ?: return
        val filter = byUid[uid] ?: return
        if (filter.typeSelect.list.isNotEmpty() || filter.regularSelect.list.isNotEmpty()) return
        byUid.remove(uid)
        if (byUid.isEmpty()) {
            BiliData.filter.remove(scope)
        }
    }

    /**
     * 模板 key 精确定位 scope、UID、类型和模板名，删除时不会影响全局模板正文。
     */
    private fun templateKey(scope: String, uid: Long, type: String, name: String): String {
        return listOf(scope, uid.toString(), type, name).joinToString("|")
    }

    /**
     * 解析模板 key；模板名本身不应含分隔符，WebUI 保存时会保持用户输入原样。
     */
    private fun parseTemplateKey(key: String): ParsedTemplateKey? {
        if (key.isBlank()) return null
        val parts = key.split("|")
        if (parts.size != 4) return null
        return ParsedTemplateKey(
            scope = parts[0],
            uid = parts[1].toLongOrNull() ?: return null,
            type = parts[2],
            name = parts[3],
        )
    }

    /**
     * UI 模板类型支持中文和底层键两种输入，方便路由和浏览器表单复用同一个 DTO。
     */
    private fun templateSpec(type: String): TemplateTypeSpec? {
        val normalized = type.trim()
        return templateTypeSpecs.firstOrNull {
            it.uiType.equals(normalized, ignoreCase = true) ||
                it.storageType.equals(normalized, ignoreCase = true) ||
                it.label == normalized
        }
    }

    /**
     * AtAllType 输入兼容中文展示值和枚举名，避免前后端在 label 与 value 间反复转换。
     */
    private fun parseAtAllType(type: String): AtAllType? {
        val text = type.trim()
        return AtAllType.entries.firstOrNull { it.value == text || it.name.equals(text, ignoreCase = true) }
    }

    /**
     * @全体冲突规则和命令服务保持一致，ALL、DYNAMIC 和细分类型互斥关系不分叉。
     */
    private fun applyAtAllType(target: MutableSet<AtAllType>, type: AtAllType) {
        when (type) {
            AtAllType.ALL -> {
                target.clear()
                target += type
            }
            AtAllType.DYNAMIC -> {
                target.removeAll(listOf(AtAllType.ALL, AtAllType.VIDEO, AtAllType.MUSIC, AtAllType.ARTICLE))
                target += type
            }
            AtAllType.LIVE -> {
                target.remove(AtAllType.ALL)
                target += type
            }
            else -> {
                target.remove(AtAllType.ALL)
                target.remove(AtAllType.DYNAMIC)
                target += type
            }
        }
    }

    /**
     * @全体列表读取当前订阅目标和历史已写入 scope，保证弹窗不会漏掉卡片摘要已统计的旧配置。
     */
    private fun resolveAtAllScopes(context: SubscriptionEditContext): List<String> {
        val scopes = linkedSetOf<String>()
        scopes += context.contactScopes
        BiliData.atAll.forEach { (scope, byUid) ->
            if (context.uids.any { uid -> byUid.containsKey(uid) }) {
                scopes += scope
            }
        }
        return scopes.sorted()
    }

    /**
     * 删除 @全体 后回收空 UID 桶和空 subject 桶，避免列表页误判仍有配置。
     */
    private fun cleanupEmptyAtAll(scope: String, uid: Long) {
        val byUid = BiliData.atAll[scope] ?: return
        if (byUid[uid].isNullOrEmpty()) {
            byUid.remove(uid)
        }
        if (byUid.isEmpty()) {
            BiliData.atAll.remove(scope)
        }
    }

    /**
     * 群号超过一个时使用首个群号加剩余数量，符合原命令摘要的紧凑阅读习惯。
     */
    private fun formatAtAllGroups(groups: List<String>): String {
        if (groups.isEmpty()) return ""
        val sorted = groups.map { shortGroupId(it) }.sorted()
        return if (sorted.size == 1) sorted.first() else "${sorted.first()} +${sorted.size - 1}"
    }

    /**
     * subject 摘要只取最后一段群号，保持 @全体列表行简洁。
     */
    private fun shortGroupId(scope: String): String {
        return scope.substringAfterLast(":")
    }

    /**
     * 模板保存同时涉及主配置和业务数据，两个文件都保存成功才向前端报告完成。
     */
    private fun persistConfigAndData(result: WebUiSubscriptionMutationResultDto): WebUiSubscriptionMutationResultDto {
        val configSaved = runCatching { saveConfigAction() }.getOrDefault(false)
        val dataSaved = runCatching { saveDataAction() }.getOrDefault(false)
        return if (configSaved && dataSaved) {
            result
        } else {
            WebUiSubscriptionMutationResultDto(
                success = false,
                message = "模板已修改但持久化失败",
                itemId = result.itemId,
                validationErrors = listOf("BiliConfig or BiliData save failed"),
            )
        }
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

    /**
     * 模板类型规格把 UI 文案、底层策略键和配置 map 绑定到同一处。
     */
    private data class TemplateTypeSpec(
        val uiType: String,
        val storageType: String,
        val label: String,
        val configMap: (BiliConfig) -> MutableMap<String, String>,
        val policyMap: () -> MutableMap<String, MutableMap<Long, TemplatePolicy>>,
    )

    /**
     * 订阅配置编辑上下文统一描述当前卡片能展开到哪些底层写入作用域。
     */
    private data class SubscriptionEditContext(
        val kind: String,
        val itemId: String,
        val uids: List<Long> = emptyList(),
        val contactScopes: List<String> = emptyList(),
        val templateScopes: List<String> = emptyList(),
        val filterScopes: List<String> = emptyList(),
        val primaryBangumiId: Long = 0L,
    ) {
        /**
         * 过滤器 key 必须落在当前卡片展开出的联系人 scope 与 UID 组合内。
         */
        fun ownsFilterKey(key: ParsedFilterKey): Boolean {
            return key.scope in filterScopes && key.uid in uids
        }

        /**
         * 模板 key 必须落在当前卡片的模板 scope 与 UID 组合内。
         */
        fun ownsTemplateKey(key: ParsedTemplateKey): Boolean {
            return key.scope in templateScopes && key.uid in uids
        }
    }

    /**
     * 过滤器 key 解析结果，用于精确更新或删除某个列表索引。
     */
    private data class ParsedFilterKey(
        val scope: String,
        val uid: Long,
        val kind: String,
        val index: Int,
    )

    /**
     * 模板 key 解析结果，用于把策略绑定和全局模板正文分开处理。
     */
    private data class ParsedTemplateKey(
        val scope: String,
        val uid: Long,
        val type: String,
        val name: String,
    )

    /**
     * MutableList 安全删除指定索引，避免 WebUI 传入旧 key 时抛出越界异常。
     */
    private fun <T> MutableList<T>.removeAtOrNull(index: Int): T? {
        if (index !in indices) return null
        return removeAt(index)
    }
}
