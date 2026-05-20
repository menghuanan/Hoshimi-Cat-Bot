package top.bilibili.webui.model

import kotlinx.serialization.Serializable

/**
 * 运行态摘要 DTO 只暴露不可变快照值，避免把运行中对象直接泄露给 WebUI；最近推送记录和今日统计一起构成首页概览。
 */
@Serializable
data class WebUiRuntimeSummaryDto(
    val lifecycleState: String,
    val uptimeSeconds: Long,
    val appVersion: String,
    val platformAdapterInitialized: Boolean,
    val platformReady: Boolean,
    val webUiEnabled: Boolean,
    val restartRequestMode: String,
    val subscriptionCount: Int,
    val dynamicSubscriptionCount: Int,
    val bangumiSubscriptionCount: Int,
    val groupCount: Int,
    val account: WebUiBiliAccountStatusDto,
    val webSocket: WebUiWebSocketStatusDto,
    val todayPushStats: WebUiTodayPushStatsDto,
    val recentPushRecords: List<WebUiRecentPushRecordDto>,
    val host: WebUiHostRuntimeStatusDto,
)

/**
 * B 站账号状态只公开登录可用性和 UID，不把 Cookie 或鉴权细节暴露给前端。
 */
@Serializable
data class WebUiBiliAccountStatusDto(
    val loggedIn: Boolean,
    val uid: Long?,
    val cookieConfigured: Boolean,
)

/**
 * WebSocket 状态聚合平台中立运行态和 transport 观测信息，避免前端感知具体 vendor。
 */
@Serializable
data class WebUiWebSocketStatusDto(
    val connected: Boolean,
    val reconnectAttempts: Int,
    val activeSessionCount: Int,
    val transports: List<String>,
    val note: String?,
)

/**
 * 今日推送统计按自然日滚动，只保留展示首页所需的成功、失败和类型拆分。
 */
@Serializable
data class WebUiTodayPushStatsDto(
    val date: String,
    val total: Int,
    val dynamic: Int,
    val live: Int,
    val liveClose: Int,
    val failed: Int,
    val lastSuccessAtEpochMillis: Long?,
)

/**
 * 最近推送记录直接面向首页展示，保留类型、状态和摘要即可，不把任务内部对象带到前端。
 */
@Serializable
data class WebUiRecentPushRecordDto(
    val timestampEpochMillis: Long,
    val type: String,
    val typeLabel: String,
    val success: Boolean,
    val statusLabel: String,
    val summary: String,
    val target: String?,
)

/**
 * 宿主运行态快照只包含首页展示所需的系统指标，避免前端直接推断 JVM 或 OS 内部对象。
 */
@Serializable
data class WebUiHostRuntimeStatusDto(
    val startedAtEpochMillis: Long,
    val systemTimeEpochMillis: Long,
    val systemLoadAverage: Double?,
    val cpuUsagePercent: Double?,
    val memory: WebUiResourceUsageDto,
    val storage: WebUiResourceUsageDto,
    val docker: WebUiDockerRuntimeStatusDto,
)

/**
 * 资源使用率统一以字节和百分比表达，便于前端用同一套格式化逻辑展示内存与存储。
 */
@Serializable
data class WebUiResourceUsageDto(
    val usedBytes: Long,
    val totalBytes: Long,
    val usagePercent: Double?,
)

/**
 * Docker 状态只表达当前进程是否看起来运行在容器内，不连接 Docker daemon。
 */
@Serializable
data class WebUiDockerRuntimeStatusDto(
    val detected: Boolean,
    val evidence: String?,
)
