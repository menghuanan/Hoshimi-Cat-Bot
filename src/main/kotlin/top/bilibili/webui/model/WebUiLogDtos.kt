package top.bilibili.webui.model

import kotlinx.serialization.Serializable

/**
 * 日志来源 DTO 只暴露固定 source id 和展示标题，不向前端泄露真实文件路径。
 */
@Serializable
data class WebUiLogSourceDto(
    val id: String,
    val title: String,
)

/**
 * 日志来源列表保持只读结构，便于前端按固定白名单渲染选择器。
 */
@Serializable
data class WebUiLogSourceListDto(
    val sources: List<WebUiLogSourceDto>,
)

/**
 * 单个日志窗口响应只返回受限 tail 文本和必要元数据，不提供任意文件浏览能力。
 */
@Serializable
data class WebUiLogWindowDto(
    val sourceId: String,
    val title: String,
    val requestedTailLines: Int,
    val lineCount: Int,
    val text: String,
    val lastModifiedEpochMillis: Long,
    val hasMore: Boolean,
)
