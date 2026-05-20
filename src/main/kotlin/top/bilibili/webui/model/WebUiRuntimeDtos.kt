package top.bilibili.webui.model

import kotlinx.serialization.Serializable

/**
 * 运行态摘要 DTO 只暴露不可变快照值，避免把运行中对象直接泄露给 WebUI。
 */
@Serializable
data class WebUiRuntimeSummaryDto(
    val lifecycleState: String,
    val uptimeSeconds: Long,
    val platformAdapterInitialized: Boolean,
    val platformReady: Boolean,
    val webUiEnabled: Boolean,
    val restartRequestMode: String,
    val subscriptionCount: Int,
    val groupCount: Int,
)
