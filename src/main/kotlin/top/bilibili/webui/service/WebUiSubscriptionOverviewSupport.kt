package top.bilibili.webui.service

import top.bilibili.AtAllType
import top.bilibili.BiliData
import top.bilibili.DynamicFilter
import top.bilibili.DynamicFilterType
import top.bilibili.FilterMode
import top.bilibili.SubData
import top.bilibili.TemplatePolicy
import top.bilibili.webui.model.WebUiSubscriptionItemDto
import top.bilibili.webui.model.WebUiSubscriptionListDto

/**
 * 订阅概览只读聚合 BiliData 中的订阅、过滤、模板、@全体和主题色，不参与任何配置写回。
 */
internal fun buildSubscriptionOverview(data: BiliData): WebUiSubscriptionListDto {
    val dynamicItems = data.dynamic.entries
        .sortedWith(compareByDescending<Map.Entry<Long, SubData>> { maxOf(it.value.last, it.value.lastLive) }.thenBy { it.key })
        .map { (uid, subscription) -> buildDynamicSubscriptionItem(data, uid, subscription) }
    val groupItems = data.group.entries
        .sortedBy { it.key }
        .map { (name, group) -> buildGroupSubscriptionItem(data, name, group.contacts) }
    val bangumiItems = data.bangumi.entries
        .sortedBy { it.key }
        .map { (seasonId, bangumi) ->
            WebUiSubscriptionItemDto(
                id = "bangumi:$seasonId",
                kind = "bangumi",
                title = bangumi.title,
                identifierLabel = "SS: ${bangumi.seasonId} / MD: ${bangumi.mediaId}",
                sourceId = seasonId,
                tags = listOf("番剧"),
                targetSectionTitle = "推送目标",
                targets = bangumi.contacts.sorted(),
                filterInfo = "不适用",
                filterCount = 0,
                templateNames = emptyList(),
                templateCount = 0,
                atAllInfo = "未开启",
                themeColor = bangumi.color.orEmpty(),
                themeColorCount = if (bangumi.color.isNullOrBlank()) 0 else 1,
                lastUpdatedEpochMillis = 0L,
            )
        }
    val items = (dynamicItems + groupItems + bangumiItems)
    return WebUiSubscriptionListDto(
        totalCount = items.size,
        dynamicCount = dynamicItems.size,
        bangumiCount = bangumiItems.size,
        groupCount = groupItems.size,
        items = items,
    )
}

/**
 * 动态订阅卡片保留直播与动态双标签，并以 last/lastLive 中较新的时间作为最后更新。
 */
private fun buildDynamicSubscriptionItem(
    data: BiliData,
    uid: Long,
    subscription: SubData,
): WebUiSubscriptionItemDto {
    val scopes = subscriptionScopes(subscription)
    val templateNames = collectTemplateNames(data, uid, scopes)
    return WebUiSubscriptionItemDto(
        id = "dynamic:$uid",
        kind = "dynamic",
        title = subscription.name,
        identifierLabel = "UID: $uid",
        sourceId = uid,
        tags = listOf("直播", "动态"),
        targetSectionTitle = "推送目标",
        targets = subscription.contacts.sorted(),
        filterInfo = summarizeFilters(data, uid),
        filterCount = countFilters(data, uid),
        templateNames = templateNames,
        templateCount = templateNames.size,
        atAllInfo = summarizeAtAll(data, uid, scopes),
        themeColor = resolveDynamicColor(data, uid, scopes),
        themeColorCount = countThemeColors(data, uid),
        lastUpdatedEpochMillis = maxOf(subscription.last, subscription.lastLive),
    )
}

/**
 * 分组卡片按 groupRef 反查订阅 UID，并把订阅 UID 放到和推送目标相同的位置展示。
 */
private fun buildGroupSubscriptionItem(
    data: BiliData,
    groupName: String,
    contacts: Set<String>,
): WebUiSubscriptionItemDto {
    val groupRef = "groupRef:$groupName"
    val linkedSubscriptions = data.dynamic.entries
        .filter { (_, subscription) -> subscription.sourceRefs.contains(groupRef) }
        .sortedBy { it.key }
    val uids = linkedSubscriptions.map { (uid, _) -> uid }
    val templateNames = linkedSetOf<String>()
    uids.forEach { uid -> collectTemplateNames(data, uid, setOf(groupRef)).forEach { templateNames += it } }
    val atAllText = summarizeGroupAtAll(data, uids, contacts + groupRef)
    return WebUiSubscriptionItemDto(
        id = "group:$groupName",
        kind = "group",
        title = groupName,
        identifierLabel = summarizeGroupUids(uids),
        sourceId = 0L,
        tags = listOf("分组"),
        targetSectionTitle = "推送目标",
        targets = contacts.sorted(),
        filterInfo = "共 ${uids.sumOf { uid -> countFilters(data, uid) }} 个过滤器",
        filterCount = uids.sumOf { uid -> countFilters(data, uid) },
        templateNames = templateNames.toList(),
        templateCount = templateNames.size,
        atAllInfo = atAllText,
        themeColor = "",
        themeColorCount = uids.sumOf { uid -> countThemeColors(data, uid) },
        lastUpdatedEpochMillis = linkedSubscriptions.maxOfOrNull { (_, subscription) ->
            maxOf(subscription.last, subscription.lastLive)
        } ?: 0L,
    )
}

/**
 * 分组卡片头部最多展示两个订阅 UID，超出部分使用 +N 保持标题区域紧凑。
 */
private fun summarizeGroupUids(uids: List<Long>): String {
    if (uids.isEmpty()) {
        return "订阅UID: 暂无"
    }
    val head = uids.take(2).joinToString("、")
    val more = if (uids.size > 2) " +${uids.size - 2}" else ""
    return "订阅UID: $head$more"
}

/**
 * 模板和颜色可能按直接联系人或 groupRef 绑定，因此同时保留原始 sourceRef 与 direct 解包后的 scope。
 */
private fun subscriptionScopes(subscription: SubData): Set<String> {
    val scopes = linkedSetOf<String>()
    scopes += subscription.contacts
    subscription.sourceRefs.forEach { sourceRef ->
        scopes += sourceRef
        if (sourceRef.startsWith("direct:")) {
            scopes += sourceRef.removePrefix("direct:")
        }
    }
    return scopes
}

/**
 * 过滤器信息按联系人聚合成短句，前端只负责展示，不反推过滤器结构。
 */
private fun summarizeFilters(data: BiliData, uid: Long): String {
    val summaries = data.filter.entries.mapNotNull { (contact, filtersByUid) ->
        val filter = filtersByUid[uid] ?: return@mapNotNull null
        "${shortContact(contact)} ${describeFilter(filter)}"
    }
    return summaries.joinToString("；").ifBlank { "未设置" }
}

/**
 * 过滤器数量按联系人维度统计，和卡片上的“该订阅下有多少个过滤器”保持一致。
 */
private fun countFilters(data: BiliData, uid: Long): Int {
    return data.filter.values.count { filtersByUid -> filtersByUid.containsKey(uid) }
}

/**
 * 单个过滤器同时呈现类型过滤和正则过滤，空列表保留模式但标记为空。
 */
private fun describeFilter(filter: DynamicFilter): String {
    val typeText = describeTypedList(
        mode = filter.typeSelect.mode,
        values = filter.typeSelect.list.map(DynamicFilterType::value),
        label = "类型",
    )
    val regularText = describeTypedList(
        mode = filter.regularSelect.mode,
        values = filter.regularSelect.list,
        label = "正则",
    )
    return "$typeText，$regularText"
}

/**
 * 黑白名单列表使用模式中文名和最多三项内容，避免窄卡片被长规则撑开。
 */
private fun describeTypedList(
    mode: FilterMode,
    values: List<String>,
    label: String,
): String {
    val content = values.take(3).joinToString("、").ifBlank { "空" }
    val more = if (values.size > 3) " +${values.size - 3}" else ""
    return "$label${mode.value}: $content$more"
}

/**
 * 模板名按动态、直播、下播三类策略合并去重，优先展示和订阅来源相关的 scope。
 */
private fun collectTemplateNames(data: BiliData, uid: Long, scopes: Set<String>): List<String> {
    val policies = listOf(
        data.dynamicTemplatePolicyByScope,
        data.liveTemplatePolicyByScope,
        data.liveCloseTemplatePolicyByScope,
    )
    val names = linkedSetOf<String>()
    policies.forEach { policyByScope ->
        collectPolicyTemplates(policyByScope, uid, scopes).forEach { names += it }
    }
    return names.toList()
}

/**
 * 单类模板策略先扫当前订阅相关 scope，找不到时再保底扫描 UID 绑定，避免遗漏旧数据。
 */
private fun collectPolicyTemplates(
    policyByScope: Map<String, Map<Long, TemplatePolicy>>,
    uid: Long,
    scopes: Set<String>,
): List<String> {
    val names = linkedSetOf<String>()
    scopes.forEach { scope ->
        policyByScope[scope]?.get(uid)?.templates?.forEach { names += it }
    }
    if (names.isEmpty()) {
        policyByScope.values.forEach { policiesByUid ->
            policiesByUid[uid]?.templates?.forEach { names += it }
        }
    }
    return names.toList()
}

/**
 * @全体状态只展示已开启的类型，未配置时保持明确的“未开启”。
 */
private fun summarizeAtAll(data: BiliData, uid: Long, scopes: Set<String>): String {
    val types = linkedSetOf<AtAllType>()
    scopes.forEach { scope ->
        data.atAll[scope]?.get(uid)?.forEach { types += it }
    }
    if (types.isEmpty()) {
        data.atAll.values.forEach { byUid -> byUid[uid]?.forEach { types += it } }
    }
    return types.map(AtAllType::value).joinToString("、").ifBlank { "未开启" }
}

/**
 * 分组卡片的 @全体状态聚合分组关联 UID 和分组目标联系人上的所有已配置类型。
 */
private fun summarizeGroupAtAll(data: BiliData, uids: List<Long>, scopes: Set<String>): String {
    val types = linkedSetOf<AtAllType>()
    scopes.forEach { scope ->
        uids.forEach { uid -> data.atAll[scope]?.get(uid)?.forEach { types += it } }
    }
    if (types.isEmpty()) {
        uids.forEach { uid ->
            data.atAll.values.forEach { byUid -> byUid[uid]?.forEach { types += it } }
        }
    }
    return types.map(AtAllType::value).joinToString("、").ifBlank { "未开启" }
}

/**
 * 主题色优先使用当前订阅来源 scope 的配置，随后回落到任意 UID 绑定色。
 */
private fun resolveDynamicColor(data: BiliData, uid: Long, scopes: Set<String>): String {
    scopes.forEach { scope ->
        val color = data.dynamicColorByUid[scope]?.get(uid)
        if (!color.isNullOrBlank()) {
            return color
        }
    }
    return data.dynamicColorByUid.values.firstNotNullOfOrNull { colorsByUid -> colorsByUid[uid] }.orEmpty()
}

/**
 * 主题色数量按 scope 中的 UID 绑定数统计，便于前端展示“有多少个主题色”。
 */
private fun countThemeColors(data: BiliData, uid: Long): Int {
    return data.dynamicColorByUid.values.count { colorsByUid -> !colorsByUid[uid].isNullOrBlank() }
}

/**
 * 联系人 subject 在卡片里用短格式展示，保留群/私聊语义又不挤占宽度。
 */
private fun shortContact(contact: String): String {
    val parts = contact.split(':')
    return if (parts.size >= 2) {
        parts.takeLast(2).joinToString(":")
    } else {
        contact
    }
}
