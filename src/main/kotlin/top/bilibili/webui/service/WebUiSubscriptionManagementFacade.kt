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
import top.bilibili.service.pgcRegex
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
import top.bilibili.webui.model.WebUiSubscriptionTargetItemDto
import top.bilibili.webui.model.WebUiSubscriptionTargetListDto
import top.bilibili.webui.model.WebUiSubscriptionTargetSaveRequestDto
import top.bilibili.webui.model.WebUiSubscriptionThemeDto
import top.bilibili.webui.model.WebUiSubscriptionUidItemDto
import top.bilibili.webui.model.WebUiSubscriptionUidListDto
import top.bilibili.webui.model.WebUiSubscriptionUidSaveRequestDto

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
    private val removeDynamicAction: suspend (Long) -> String = { uid ->
        DynamicService.removeUidForWebUi(uid)
    },
    private val followPgcAction: suspend (String, String) -> String = { id, subject ->
        PgcService.followPgc(id, subject)
    },
    private val deletePgcAction: suspend (String, String) -> String = { id, subject ->
        PgcService.delPgc(id, subject)
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
     * 读取订阅、分组或番剧当前绑定的推送群聊，列表 key 使用完整 subject 供删除接口复用。
     */
    fun listSubscriptionTargets(itemId: String): WebUiSubscriptionTargetListDto {
        val context = resolveEditContext(itemId) ?: return WebUiSubscriptionTargetListDto(emptyList())
        val targets = when (context.kind) {
            "bangumi" -> BiliData.bangumi[context.primaryBangumiId]?.contacts.orEmpty()
            else -> context.contactScopes
        }
        return WebUiSubscriptionTargetListDto(
            targets.sorted().map { subject ->
                WebUiSubscriptionTargetItemDto(
                    key = subject,
                    targetGroup = shortGroupId(subject),
                    summary = "群聊：${shortGroupId(subject)}",
                )
            },
        )
    }

    /**
     * 新增推送群聊按卡片类型分发到底层来源模型，输入只允许正整数群号。
     */
    suspend fun saveSubscriptionTarget(
        itemId: String,
        request: WebUiSubscriptionTargetSaveRequestDto,
    ): WebUiSubscriptionMutationResultDto {
        val context = resolveEditContext(itemId) ?: return validationFailure("订阅不存在或不支持推送群聊")
        val subject = normalizePositiveGroupTarget(request.targetGroup) ?: return validationFailure("推送群聊必须是正整数")
        val result = when (context.kind) {
            "dynamic" -> saveDynamicTarget(context, subject)
            "group" -> saveGroupTarget(context, subject)
            "bangumi" -> saveBangumiTarget(context, subject)
            else -> validationFailure("订阅类型无效")
        }
        if (!result.success) return result
        markSubscriptionCardUpdated(itemId)
        return persistMutation(result)
    }

    /**
     * 删除单条推送群聊时只移除对应目标，并清理该目标 scope 下当前卡片归属的附属配置。
     */
    suspend fun deleteSubscriptionTarget(itemId: String, key: String): WebUiSubscriptionMutationResultDto {
        val context = resolveEditContext(itemId) ?: return validationFailure("订阅不存在或不支持推送群聊")
        val subject = normalizeGroupTarget(key) ?: return validationFailure("推送群聊标识无效")
        val result = when (context.kind) {
            "dynamic" -> deleteDynamicTarget(context, subject)
            "group" -> deleteGroupTarget(context, subject)
            "bangumi" -> deleteBangumiTarget(context, subject)
            else -> validationFailure("订阅类型无效")
        }
        if (!result.success) return result
        markSubscriptionCardUpdated(itemId)
        return persistMutation(result)
    }

    /**
     * 分组订阅 ID 列表从 groupRef 反查动态订阅，并按分组联系人反查番剧订阅。
     */
    fun listSubscriptionUids(itemId: String): WebUiSubscriptionUidListDto {
        val context = resolveEditContext(itemId) ?: return WebUiSubscriptionUidListDto(emptyList())
        if (context.kind != "group") {
            return WebUiSubscriptionUidListDto(emptyList())
        }
        val groupContacts = context.contactScopes.toSet()
        val bangumiItems = BiliData.bangumi.values
            .filter { bangumi -> groupContacts.isNotEmpty() && bangumi.contacts.any { it in groupContacts } }
            .sortedBy { it.seasonId }
            .map { bangumi ->
                val identifier = "md${bangumi.mediaId}"
                WebUiSubscriptionUidItemDto(
                    key = identifier,
                    identifier = identifier,
                    summary = "番剧：${bangumi.title}（$identifier）",
                )
            }
        return WebUiSubscriptionUidListDto(
            context.uids.map { uid ->
                WebUiSubscriptionUidItemDto(
                    key = uid.toString(),
                    uid = uid,
                    identifier = uid.toString(),
                    summary = "UID：$uid",
                )
            } + bangumiItems,
        )
    }

    /**
     * 分组新增订阅 ID 复用后端分组订阅逻辑，新增后默认推送到该分组全部推送群聊。
     */
    suspend fun saveSubscriptionUid(
        itemId: String,
        request: WebUiSubscriptionUidSaveRequestDto,
    ): WebUiSubscriptionMutationResultDto {
        val context = resolveEditContext(itemId) ?: return validationFailure("订阅不存在或不支持订阅ID")
        if (context.kind != "group") return validationFailure("仅分组支持编辑订阅ID")
        val identifier = parseGroupSubscriptionIdentifier(request.uid)
            ?: return validationFailure("订阅ID必须是 UID 正整数，或 ss/md/ep 前缀番剧ID")
        if (context.contactScopes.isEmpty()) return validationFailure("当前分组没有可推送群聊")
        val groupName = context.groupName()
        val message = when (identifier) {
            is GroupSubscriptionIdentifier.Uid -> addGroupDynamicAction(identifier.uid, groupName)
            is GroupSubscriptionIdentifier.Pgc -> applyPgcToGroupContacts(identifier.id, context.contactScopes, followPgcAction)
        }
        if (!isSuccessMessage(message)) return validationFailure(message)
        markSubscriptionCardUpdated(itemId)
        if (identifier is GroupSubscriptionIdentifier.Pgc) {
            markPgcGroupCardUpdates(identifier.id)
        }
        return persistMutation(success(itemId, message))
    }

    /**
     * 分组删除订阅 ID 必须走对应取消订阅链路，确保账号侧和本地附属数据一起回收。
     */
    suspend fun deleteSubscriptionUid(itemId: String, key: String): WebUiSubscriptionMutationResultDto {
        val context = resolveEditContext(itemId) ?: return validationFailure("订阅不存在或不支持订阅ID")
        if (context.kind != "group") return validationFailure("仅分组支持编辑订阅ID")
        if (context.contactScopes.isEmpty()) return validationFailure("当前分组没有可推送群聊")
        val identifier = parseGroupSubscriptionIdentifier(key) ?: return validationFailure("订阅ID标识无效")
        val message = when (identifier) {
            is GroupSubscriptionIdentifier.Uid -> {
                if (identifier.uid !in context.uids) return validationFailure("订阅ID不存在")
                removeDynamicAction(identifier.uid)
            }
            is GroupSubscriptionIdentifier.Pgc -> {
                if (!groupHasPgcSubscription(context.contactScopes, identifier.id)) {
                    return validationFailure("番剧订阅不存在")
                }
                applyPgcToGroupContacts(identifier.id, context.contactScopes, deletePgcAction)
            }
        }
        if (!isSuccessMessage(message)) return validationFailure(message)
        if (identifier is GroupSubscriptionIdentifier.Uid) {
            removeUidPayload(identifier.uid)
        }
        markSubscriptionCardUpdated(itemId)
        if (identifier is GroupSubscriptionIdentifier.Pgc) {
            markPgcGroupCardUpdates(identifier.id)
        }
        return persistMutation(success(itemId, message))
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
     * 新增或编辑过滤器；动态订阅按目标群聊写入，分组保持原有上下文展开语义。
     */
    fun saveSubscriptionFilter(
        itemId: String,
        request: WebUiSubscriptionFilterSaveRequestDto,
    ): WebUiSubscriptionMutationResultDto {
        val context = resolveEditContext(itemId) ?: return validationFailure("订阅不存在或不支持过滤器")
        val kind = normalizeFilterKind(request.kind) ?: return validationFailure("过滤方式无效")
        val mode = parseFilterMode(request.mode) ?: return validationFailure("黑白名单类型无效")
        val content = request.content.trim()
        if (content.isBlank()) {
            return validationFailure("规则内容必须填写")
        }
        val type = if (kind == "type") parseDynamicFilterType(content) ?: return validationFailure("标签类型无效") else null
        val selectedScopes = resolveNestedTargetScopes(context, request.targetGroups)
        if (context.kind == "dynamic" && selectedScopes == null) {
            return validationFailure("目标群聊必须至少选择一个")
        }

        if (request.key.isNotBlank()) {
            val parsedKey = parseFilterKey(request.key) ?: return validationFailure("过滤器标识无效")
            if (!context.ownsFilterKey(parsedKey) || !removeFilterRow(parsedKey)) {
                return validationFailure("过滤器不存在")
            }
            cleanupEmptyFilter(parsedKey.scope, parsedKey.uid)
        }
        val scopes = selectedScopes ?: if (context.filterScopes.isNotEmpty()) context.filterScopes else context.contactScopes
        if (scopes.isEmpty() || context.uids.isEmpty()) {
            return validationFailure("当前订阅没有可写入过滤器的推送群")
        }
        scopes.forEach { scope ->
            context.uids.forEach { uid ->
                val filter = BiliData.filter.getOrPut(scope) { mutableMapOf() }.getOrPut(uid) { DynamicFilter() }
                appendFilterRow(filter, kind, mode, content, type)
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
     * 保存模板正文并绑定到当前订阅策略；动态订阅按目标群聊选择写入策略。
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
        if (request.content.isBlank()) {
            return validationFailure("模板内容必须填写")
        }
        val selectedScopes = resolveNestedTargetScopes(context, request.targetGroups)
        if (context.kind == "dynamic" && selectedScopes == null) {
            return validationFailure("目标群聊必须至少选择一个")
        }
        val scopes = selectedScopes ?: context.templateScopes
        if (scopes.isEmpty() || context.uids.isEmpty()) {
            return validationFailure("当前订阅没有可写入模板的作用域")
        }

        val config = configProvider()
        spec.configMap(config)[name] = request.content
        parseTemplateKey(request.key)?.let { key ->
            if (!context.ownsTemplateKey(key)) {
                return validationFailure("模板不存在")
            }
            if (key.scope !in scopes || key.type != spec.storageType || key.name != name) {
                TemplateRuntimeCoordinator.removeTemplate(key.type, key.scope, key.uid, key.name)
            }
        }
        scopes.forEach { scope ->
            context.uids.forEach { uid ->
                TemplateRuntimeCoordinator.appendTemplate(spec.storageType, scope, uid, name)
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
     * 主题色读取优先返回当前订阅已有的第一条作用域绑定，并带回动态订阅中已覆盖颜色的群聊。
     */
    fun readSubscriptionTheme(itemId: String): WebUiSubscriptionThemeDto {
        val context = resolveEditContext(itemId) ?: return WebUiSubscriptionThemeDto("")
        if (context.kind == "bangumi") {
            return WebUiSubscriptionThemeDto(BiliData.bangumi[context.primaryBangumiId]?.color.orEmpty())
        }
        val targetGroups = linkedSetOf<String>()
        var firstColor = ""
        context.contactScopes.forEach { scope ->
            context.uids.forEach { uid ->
                val color = BiliData.dynamicColorByUid[scope]?.get(uid)
                if (!color.isNullOrBlank()) {
                    if (firstColor.isBlank()) {
                        firstColor = color
                    }
                    targetGroups += scope
                }
            }
        }
        return WebUiSubscriptionThemeDto(firstColor, targetGroups.toList())
    }

    /**
     * 主题色保存接受空值恢复默认色；单 UP 动态订阅可按所选群聊写入，分组仍展开到全部群聊。
     */
    fun saveSubscriptionTheme(
        itemId: String,
        color: String,
        targetGroups: List<String> = emptyList(),
    ): WebUiSubscriptionMutationResultDto {
        val context = resolveEditContext(itemId) ?: return validationFailure("订阅不存在或不支持主题色")
        val normalizedColor = color.trim()
        // 空主题色沿用前端“恢复默认”语义；单 UP 只清除所选群聊，分组清除全部绑定群聊。
        if (normalizedColor.isBlank()) {
            if (context.kind == "bangumi") {
                val bangumi = BiliData.bangumi[context.primaryBangumiId] ?: return validationFailure("番剧订阅不存在")
                bangumi.color = null
            } else {
                val scopes = resolveThemeWriteScopes(context, targetGroups, requireSelection = false)
                    ?: return validationFailure("目标群聊必须至少选择一个")
                if (scopes.isEmpty()) {
                    return success(itemId, "主题色未变更")
                }
                if (context.uids.isEmpty()) {
                    return validationFailure("当前订阅没有可写入主题色的推送群")
                }
                scopes.forEach { scope ->
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
            val scopes = resolveThemeWriteScopes(context, targetGroups, requireSelection = true)
                ?: return validationFailure("目标群聊必须至少选择一个")
            if (scopes.isEmpty() || context.uids.isEmpty()) {
                return validationFailure("当前订阅没有可写入主题色的推送群")
            }
            scopes.forEach { scope ->
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
     * 番剧订阅允许 ss、md、ep 三类命令层标识，保持 WebUI 与聊天命令入口一致。
     */
    private suspend fun createBangumiSubscription(
        request: WebUiSubscriptionCreateRequestDto,
    ): WebUiSubscriptionMutationResultDto {
        val bangumiId = request.bangumiId.trim().lowercase()
        val subject = normalizeGroupTarget(request.targetGroup)
        val errors = mutableListOf<String>()
        if (!pgcRegex.matches(bangumiId)) errors += "番剧号必须以 ss、md 或 ep 开头"
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
     * 单 UP 新增推送群聊只追加 direct 来源，不影响已有分组或其他 direct 来源。
     */
    private suspend fun saveDynamicTarget(context: SubscriptionEditContext, subject: String): WebUiSubscriptionMutationResultDto {
        val uid = context.uids.firstOrNull() ?: return validationFailure("订阅UID无效")
        val message = addDynamicAction(uid, subject)
        return if (isSuccessMessage(message)) success(context.itemId, message) else validationFailure(message)
    }

    /**
     * 分组新增推送群聊会刷新所有 groupRef UID 的 contacts 展开，新增 UID 后自然推送到全部群聊。
     */
    private fun saveGroupTarget(context: SubscriptionEditContext, subject: String): WebUiSubscriptionMutationResultDto {
        val group = BiliData.group[context.groupName()] ?: return validationFailure("分组不存在")
        if (subject in group.contacts) return validationFailure("推送群聊已存在")
        group.contacts += subject
        rebuildGroupRefContacts(context.groupName())
        return success(context.itemId, "推送群聊已保存")
    }

    /**
     * 番剧新增推送群聊复用追番链路，保证 season 元信息和 contacts 写入仍由 PgcService 管理。
     */
    private suspend fun saveBangumiTarget(context: SubscriptionEditContext, subject: String): WebUiSubscriptionMutationResultDto {
        val message = followPgcAction("ss${context.primaryBangumiId}", subject)
        return if (isSuccessMessage(message)) success(context.itemId, message) else validationFailure(message)
    }

    /**
     * 单 UP 删除推送群聊走 direct 退订来源；最后一个来源被移除时会触发账号侧取消关注。
     */
    private suspend fun deleteDynamicTarget(context: SubscriptionEditContext, subject: String): WebUiSubscriptionMutationResultDto {
        val uid = context.uids.firstOrNull() ?: return validationFailure("订阅UID无效")
        if (subject !in context.contactScopes) return validationFailure("推送群聊不存在")
        val message = DynamicService.removeDirectSubscribe(uid, subject, isSelf = false)
        if (!isSuccessMessage(message)) return validationFailure(message)
        cleanupTargetPayload(subject, listOf(uid))
        return success(context.itemId, message)
    }

    /**
     * 分组删除推送群聊会影响分组内所有 UID 的展开目标，并清理该群上的 UID 附属配置。
     */
    private fun deleteGroupTarget(context: SubscriptionEditContext, subject: String): WebUiSubscriptionMutationResultDto {
        val group = BiliData.group[context.groupName()] ?: return validationFailure("分组不存在")
        if (!group.contacts.remove(subject)) return validationFailure("推送群聊不存在")
        cleanupTargetPayload(subject, context.uids)
        rebuildGroupRefContacts(context.groupName())
        return success(context.itemId, "推送群聊已删除")
    }

    /**
     * 番剧删除推送群聊只移除对应 contacts 绑定，最后一个目标移除时底层会回收番剧条目。
     */
    private suspend fun deleteBangumiTarget(context: SubscriptionEditContext, subject: String): WebUiSubscriptionMutationResultDto {
        if (subject !in BiliData.bangumi[context.primaryBangumiId]?.contacts.orEmpty()) {
            return validationFailure("推送群聊不存在")
        }
        val message = deletePgcAction("ss${context.primaryBangumiId}", subject)
        return if (isSuccessMessage(message)) success(context.itemId, message) else validationFailure(message)
    }

    /**
     * 删除动态卡片时按 UID 清理所有关联业务数据，保证卡片消失后不留下附属策略。
     */
    private suspend fun deleteDynamicSubscription(rawUid: String): WebUiSubscriptionMutationResultDto {
        val uid = rawUid.toLongOrNull() ?: return validationFailure("订阅UID无效")
        if (!BiliData.dynamic.containsKey(uid)) {
            return validationFailure("订阅不存在")
        }
        val message = removeDynamicAction(uid)
        if (!isSuccessMessage(message)) {
            return validationFailure(message)
        }
        removeUidPayload(uid)
        return success("dynamic:$uid", message)
    }

    /**
     * 删除分组卡片时把分组绑定的 UID 视为该卡片归属，一并清理 UID 下属策略和分组本体。
     */
    private suspend fun deleteGroupSubscription(groupName: String): WebUiSubscriptionMutationResultDto {
        if (!BiliData.group.containsKey(groupName)) {
            return validationFailure("分组不存在")
        }
        val groupRef = "groupRef:$groupName"
        val linkedUids = BiliData.dynamic
            .filter { (_, subscription) -> groupRef in subscription.sourceRefs }
            .keys
            .toList()
        linkedUids.forEach { uid ->
            val message = removeDynamicAction(uid)
            if (!isSuccessMessage(message)) {
                return validationFailure(message)
            }
            removeUidPayload(uid)
        }
        BiliData.group.remove(groupName)
        BiliData.subscriptionCardUpdatedAt.remove("group:$groupName")
        removeScopedPayload(groupRef)
        return success("group:$groupName", "分组已删除")
    }

    /**
     * 删除番剧卡片时移除整条番剧订阅记录，番剧主题色随记录一起回收。
     */
    private suspend fun deleteBangumiSubscription(rawSeasonId: String): WebUiSubscriptionMutationResultDto {
        val seasonId = rawSeasonId.toLongOrNull() ?: return validationFailure("番剧号无效")
        val bangumi = BiliData.bangumi[seasonId] ?: return validationFailure("番剧订阅不存在")
        bangumi.contacts.toList().forEach { subject ->
            val message = deletePgcAction("ss$seasonId", subject)
            if (!isSuccessMessage(message)) {
                return validationFailure(message)
            }
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
     * WebUI 推送群聊新增只接受正整数群号，避免把完整 subject 或非法字符串写成新目标。
     */
    private fun normalizePositiveGroupTarget(raw: String): String? {
        val groupId = parsePositiveLong(raw) ?: return null
        return PlatformContact(PlatformType.ONEBOT11, PlatformChatType.GROUP, groupId.toString()).toSubject()
    }

    /**
     * 正整数解析统一服务推送群聊和分组 UID 输入，拒绝 0、负数和非数字内容。
     */
    private fun parsePositiveLong(raw: String): Long? {
        val text = raw.trim()
        if (text.isBlank() || !text.all(Char::isDigit)) return null
        return text.toLongOrNull()?.takeIf { it > 0L }
    }

    /**
     * 删除某个推送群聊后按 UID 清理该 subject 下的过滤器、@全体、冷却和主题色残留。
     */
    private fun cleanupTargetPayload(subject: String, uids: List<Long>) {
        val uidSet = uids.toSet()
        BiliData.filter[subject]?.let { filtersByUid ->
            filtersByUid.keys.removeAll(uidSet)
            if (filtersByUid.isEmpty()) BiliData.filter.remove(subject)
        }
        BiliData.dynamicColorByUid[subject]?.let { colorsByUid ->
            colorsByUid.keys.removeAll(uidSet)
            if (colorsByUid.isEmpty()) BiliData.dynamicColorByUid.remove(subject)
        }
        BiliData.atAll[subject]?.let { atAllByUid ->
            atAllByUid.keys.removeAll(uidSet)
            if (atAllByUid.isEmpty()) BiliData.atAll.remove(subject)
        }
        uidSet.forEach { uid ->
            BiliData.atAllCooldownUntil.keys.removeIf { key -> key.startsWith("$subject|$uid|") || key.startsWith("$subject.$uid.") }
        }
    }

    /**
     * 分组联系人变更后按 groupRef 重建所有关联 UID 的实际 contacts，保持推送目标与分组一致。
     */
    private fun rebuildGroupRefContacts(groupName: String) {
        val groupRef = "groupRef:$groupName"
        BiliData.dynamic.forEach { (_, subData) ->
            if (groupRef !in subData.sourceRefs) return@forEach
            val resolvedContacts = linkedSetOf<String>()
            subData.sourceRefs.forEach { sourceRef ->
                when {
                    sourceRef.startsWith("direct:") -> normalizeContactSubject(sourceRef.removePrefix("direct:"))?.let(resolvedContacts::add)
                    sourceRef == groupRef -> BiliData.group[groupName]?.contacts?.mapNotNullTo(resolvedContacts, ::normalizeContactSubject)
                }
            }
            subData.contacts.clear()
            subData.contacts.addAll(resolvedContacts)
        }
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
     * 番剧新增入口保留原始 ss/md/ep 标识，同时为 ss 前缀写入概览卡片使用的季 ID。
     */
    private fun markBangumiCardUpdated(bangumiId: String, previousContactsBySeason: Map<Long, Set<String>>) {
        markSubscriptionCardUpdated("bangumi:$bangumiId")
        if (bangumiId.startsWith("ss")) {
            bangumiId.removePrefix("ss").toLongOrNull()?.let { seasonId ->
                markSubscriptionCardUpdated("bangumi:$seasonId")
            }
        }
        // md/ep 入口只在业务服务返回后才能知道最终 season id，因此用联系人集合变化反查实际卡片。
        BiliData.bangumi.forEach { (seasonId, bangumi) ->
            if (previousContactsBySeason[seasonId] != bangumi.contacts.toSet()) {
                markSubscriptionCardUpdated("bangumi:$seasonId")
            }
        }
    }

    /**
     * 分组订阅输入兼容命令层基线：纯数字按 UID，ss/md/ep 前缀按番剧标识。
     */
    private fun parseGroupSubscriptionIdentifier(raw: String): GroupSubscriptionIdentifier? {
        val normalized = raw.trim().lowercase()
        parsePositiveLong(normalized)?.let { uid -> return GroupSubscriptionIdentifier.Uid(uid) }
        if (pgcRegex.matches(normalized)) return GroupSubscriptionIdentifier.Pgc(normalized)
        return null
    }

    /**
     * 番剧分组订阅按当前分组联系人逐个执行，保持 WebUI 与 group subscribe 命令语义一致。
     */
    private suspend fun applyPgcToGroupContacts(
        id: String,
        contacts: List<String>,
        action: suspend (String, String) -> String,
    ): String {
        var successCount = 0
        var firstError: String? = null
        contacts.forEach { contact ->
            val message = action(id, contact)
            if (isSuccessMessage(message)) {
                successCount++
            } else if (firstError == null) {
                firstError = message
            }
        }
        return if (firstError != null && successCount == 0) {
            firstError ?: "操作失败"
        } else {
            "操作成功，成功处理 $successCount 个联系人${firstError?.let { "，部分失败：$it" }.orEmpty()}"
        }
    }

    /**
     * 删除番剧标识前先确认该番剧确实命中当前分组联系人，避免误报不存在订阅。
     */
    private fun groupHasPgcSubscription(contacts: List<String>, id: String): Boolean {
        val normalizedId = id.trim().lowercase()
        val pgc = when {
            normalizedId.startsWith("ss") -> {
                val seasonId = normalizedId.removePrefix("ss").toLongOrNull()
                BiliData.bangumi[seasonId]
            }
            normalizedId.startsWith("md") -> {
                val mediaId = normalizedId.removePrefix("md").toLongOrNull()
                BiliData.bangumi.values.firstOrNull { it.mediaId == mediaId }
            }
            else -> null
        }
        return pgc?.contacts?.any { it in contacts } == true || normalizedId.startsWith("ep")
    }

    /**
     * 分组番剧写入后按输入标识能解析出的本地番剧记录刷新实际卡片时间。
     */
    private fun markPgcGroupCardUpdates(id: String) {
        val normalizedId = id.trim().lowercase()
        when {
            normalizedId.startsWith("ss") -> normalizedId.removePrefix("ss").toLongOrNull()?.let { seasonId ->
                if (BiliData.bangumi.containsKey(seasonId)) markSubscriptionCardUpdated("bangumi:$seasonId")
            }
            normalizedId.startsWith("md") -> {
                val mediaId = normalizedId.removePrefix("md").toLongOrNull()
                BiliData.bangumi.values.firstOrNull { it.mediaId == mediaId }?.let { bangumi ->
                    markSubscriptionCardUpdated("bangumi:${bangumi.seasonId}")
                }
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
     * 删除 key 指向的旧过滤规则，编辑保存会再按当前目标群聊重新追加。
     */
    private fun removeFilterRow(key: ParsedFilterKey): Boolean {
        val filter = BiliData.filter[key.scope]?.get(key.uid) ?: return false
        val removed = when (key.kind) {
            "type" -> filter.typeSelect.list.removeAtOrNull(key.index)
            "regex" -> filter.regularSelect.list.removeAtOrNull(key.index)
            else -> null
        } ?: return false
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
     * 单 UP 主题色按前端选择的群聊写入；分组主题色保持旧行为，始终覆盖分组全部推送群。
     */
    private fun resolveThemeWriteScopes(
        context: SubscriptionEditContext,
        targetGroups: List<String>,
        requireSelection: Boolean,
    ): List<String>? {
        if (context.kind != "dynamic") {
            return context.contactScopes
        }
        val scopes = targetGroups.distinct().filter { it in context.contactScopes }
        if (targetGroups.isNotEmpty() && scopes.isEmpty()) {
            return null
        }
        if (requireSelection && scopes.isEmpty()) {
            return null
        }
        return scopes
    }

    /**
     * 过滤器和模板只在动态订阅中按目标群聊收窄写入；其他卡片返回 null 表示沿用旧 scope。
     */
    private fun resolveNestedTargetScopes(
        context: SubscriptionEditContext,
        targetGroups: List<String>,
    ): List<String>? {
        if (context.kind != "dynamic") {
            return null
        }
        val scopes = targetGroups.distinct().filter { it in context.contactScopes }
        return scopes.ifEmpty { null }
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
     * 分组订阅编辑器的统一输入模型，避免 UI 字段名 uid 限死为动态 UID。
     */
    private sealed class GroupSubscriptionIdentifier {
        data class Uid(val uid: Long) : GroupSubscriptionIdentifier()
        data class Pgc(val id: String) : GroupSubscriptionIdentifier()
    }

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
         * 分组 itemId 以 group:<name> 保存，内部写入需要取回真实分组名。
         */
        fun groupName(): String {
            return itemId.removePrefix("group:")
        }

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
