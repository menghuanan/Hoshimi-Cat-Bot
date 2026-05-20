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
