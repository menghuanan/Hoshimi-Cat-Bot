package top.bilibili.webui.model

import kotlinx.serialization.Serializable

/**
 * 订阅管理页的只读列表 DTO，专门承载卡片渲染和顶部分类计数。
 */
@Serializable
data class WebUiSubscriptionListDto(
    val totalCount: Int,
    val dynamicCount: Int,
    val bangumiCount: Int,
    val groupCount: Int,
    val items: List<WebUiSubscriptionItemDto>,
)

/**
 * 单张订阅卡片的聚合视图，避免前端直接解析 BiliData 字段树。
 */
@Serializable
data class WebUiSubscriptionItemDto(
    val id: String,
    val kind: String,
    val title: String,
    val identifierLabel: String,
    val sourceId: Long,
    val tags: List<String>,
    val targetSectionTitle: String,
    val targets: List<String>,
    val filterInfo: String,
    val filterCount: Int,
    val templateNames: List<String>,
    val templateCount: Int,
    val atAllInfo: String,
    val themeColor: String,
    val themeColorCount: Int,
    val lastUpdatedEpochMillis: Long,
)

/**
 * WebUI 新增订阅请求只承载页面表单字段和二次确认密码，具体类型校验由后端 facade 统一执行。
 */
@Serializable
data class WebUiSubscriptionCreateRequestDto(
    val type: String,
    val uid: String = "",
    val targetGroup: String = "",
    val groupName: String = "",
    val bangumiId: String = "",
    val confirmationPassword: String = "",
)

/**
 * WebUI 订阅写操作结果统一返回成功态、错误列表和可刷新定位的订阅 ID。
 */
@Serializable
data class WebUiSubscriptionMutationResultDto(
    val success: Boolean,
    val message: String,
    val itemId: String? = null,
    val validationErrors: List<String> = emptyList(),
)

/**
 * 订阅过滤器编辑页的列表响应，按底层 t/r 索引拆成单条可编辑记录。
 */
@Serializable
data class WebUiSubscriptionFilterListDto(
    val filters: List<WebUiSubscriptionFilterItemDto>,
)

/**
 * 单条过滤规则的 WebUI 表示，key 用于后续编辑或删除时精确定位底层列表项。
 */
@Serializable
data class WebUiSubscriptionFilterItemDto(
    val key: String,
    val prefix: String,
    val kind: String,
    val label: String,
    val mode: String,
    val content: String,
    val scope: String,
    val summary: String,
)

/**
 * 过滤器保存请求同时覆盖新增和编辑；key 为空表示追加到当前订阅的所有适用目标，确认密码只供路由鉴权。
 */
@Serializable
data class WebUiSubscriptionFilterSaveRequestDto(
    val key: String = "",
    val kind: String,
    val mode: String,
    val content: String,
    val confirmationPassword: String = "",
)

/**
 * 订阅模板编辑页响应，包含当前策略中的模板和随机开关状态。
 */
@Serializable
data class WebUiSubscriptionTemplateListDto(
    val templates: List<WebUiSubscriptionTemplateItemDto>,
    val randomEnabled: Boolean,
)

/**
 * 单条模板策略记录保留模板正文，便于编辑页直接回填名称和内容。
 */
@Serializable
data class WebUiSubscriptionTemplateItemDto(
    val key: String,
    val type: String,
    val typeLabel: String,
    val name: String,
    val content: String,
    val scope: String,
)

/**
 * 模板保存请求会同时写入模板正文和当前订阅的模板策略绑定，确认密码只供路由鉴权。
 */
@Serializable
data class WebUiSubscriptionTemplateSaveRequestDto(
    val key: String = "",
    val type: String,
    val name: String,
    val content: String,
    val confirmationPassword: String = "",
)

/**
 * 随机模板开关请求承载布尔值和确认密码，具体作用域由订阅卡片 ID 决定。
 */
@Serializable
data class WebUiSubscriptionTemplateRandomRequestDto(
    val enabled: Boolean,
    val confirmationPassword: String = "",
)

/**
 * @全体编辑页按类型聚合群组，避免同一类型在多群配置时占满列表。
 */
@Serializable
data class WebUiSubscriptionAtAllListDto(
    val items: List<WebUiSubscriptionAtAllItemDto>,
)

/**
 * 单条 @全体聚合记录，key 对应底层 AtAllType.name。
 */
@Serializable
data class WebUiSubscriptionAtAllItemDto(
    val key: String,
    val type: String,
    val summary: String,
    val groups: List<String>,
)

/**
 * @全体保存请求承载类型、目标群组和确认密码，目标群组由订阅卡片的推送目标展开。
 */
@Serializable
data class WebUiSubscriptionAtAllSaveRequestDto(
    val type: String,
    val targetGroups: List<String> = emptyList(),
    val confirmationPassword: String = "",
)

/**
 * 主题色编辑页只读响应，当前没有配置时返回空字符串；动态订阅会带回已有颜色覆盖的群聊。
 */
@Serializable
data class WebUiSubscriptionThemeDto(
    val color: String,
    val targetGroups: List<String> = emptyList(),
)

/**
 * 主题色保存请求允许空值恢复默认；动态订阅可用 targetGroups 限定本次写入的群聊范围。
 */
@Serializable
data class WebUiSubscriptionThemeSaveRequestDto(
    val color: String,
    val targetGroups: List<String> = emptyList(),
    val confirmationPassword: String = "",
)
