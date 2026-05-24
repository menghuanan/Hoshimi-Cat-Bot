package top.bilibili.webui.service

import top.bilibili.BiliConfigManager
import top.bilibili.config.ConfigManager
import top.bilibili.currentVersionLabel
import top.bilibili.connector.PlatformObservabilitySnapshot
import top.bilibili.connector.PlatformRuntimeStatus
import top.bilibili.core.BiliBiliBot
import top.bilibili.tasker.DailyPushStatsSnapshot
import top.bilibili.tasker.PushDeliveryRecordSnapshot
import top.bilibili.tasker.PushStatistics
import top.bilibili.webui.model.WebUiBiliAccountStatusDto
import top.bilibili.webui.model.WebUiHostRuntimeStatusDto
import top.bilibili.webui.model.WebUiRecentPushRecordDto
import top.bilibili.webui.model.WebUiResourceUsageDto
import top.bilibili.webui.model.WebUiRuntimeSummaryDto
import top.bilibili.webui.model.WebUiTodayPushStatsDto
import top.bilibili.webui.model.WebUiWebSocketStatusDto
import java.io.File
import java.lang.management.ManagementFactory
import java.lang.management.OperatingSystemMXBean
import com.sun.management.OperatingSystemMXBean as SunOperatingSystemMXBean

/**
 * WebUI 运行态 facade 只读取现有公开运行状态，并映射为只读 DTO 快照。
 */
class WebUiRuntimeFacade(
    private val lifecycleStateProvider: () -> String = { BiliBiliBot.currentLifecycleState().name },
    private val uptimeSecondsProvider: () -> Long = { BiliBiliBot.getUptimeSeconds() },
    // 版本标签只读取启动期暴露的应用版本，不回看任何配置文件或运行中对象。
    private val appVersionProvider: () -> String = { currentVersionLabel() },
    private val platformAdapterInitializedProvider: () -> Boolean = { BiliBiliBot.isPlatformAdapterInitialized() },
    private val webUiEnabledProvider: () -> Boolean = { runCatching { ConfigManager.botConfig.webui.enabled }.getOrDefault(false) },
    private val restartSupportedProvider: () -> Boolean = { false },
    private val subscriptionCountProvider: () -> Int = {
        runCatching { BiliConfigManager.data.dynamic.size + BiliConfigManager.data.bangumi.size }.getOrDefault(0)
    },
    private val dynamicSubscriptionCountProvider: () -> Int = { runCatching { BiliConfigManager.data.dynamic.size }.getOrDefault(0) },
    private val bangumiSubscriptionCountProvider: () -> Int = { runCatching { BiliConfigManager.data.bangumi.size }.getOrDefault(0) },
    private val groupCountProvider: () -> Int = { runCatching { BiliConfigManager.data.group.size }.getOrDefault(0) },
    private val accountStatusProvider: () -> WebUiBiliAccountStatusDto = { readDefaultAccountStatus() },
    private val platformRuntimeStatusProvider: () -> PlatformRuntimeStatus = {
        runCatching { BiliBiliBot.requireConnectorManager().runtimeStatus() }
            .getOrDefault(PlatformRuntimeStatus(connected = false, reconnectAttempts = 0))
    },
    private val platformObservabilityProvider: () -> PlatformObservabilitySnapshot = {
        runCatching { BiliBiliBot.requireConnectorManager().runtimeObservability() }
            .getOrDefault(PlatformObservabilitySnapshot.empty("platform adapter is not initialized"))
    },
    private val pushStatisticsProvider: () -> DailyPushStatsSnapshot = { PushStatistics.snapshot() },
    private val hostStatusProvider: () -> WebUiHostRuntimeStatusDto = { readHostRuntimeStatus() },
) {
    /**
     * 运行态响应始终是即时快照，避免前端持有对可变运行态对象的直接引用。
     */
    fun readSummary(): WebUiRuntimeSummaryDto {
        val lifecycleState = lifecycleStateProvider()
        val platformAdapterInitialized = platformAdapterInitializedProvider()
        val platformRuntimeStatus = platformRuntimeStatusProvider()
        val platformObservability = platformObservabilityProvider()
        val pushSnapshot = pushStatisticsProvider()
        return WebUiRuntimeSummaryDto(
            lifecycleState = lifecycleState,
            uptimeSeconds = uptimeSecondsProvider(),
            appVersion = appVersionProvider(),
            platformAdapterInitialized = platformAdapterInitialized,
            platformReady = platformAdapterInitialized && lifecycleState == "RUNNING",
            webUiEnabled = webUiEnabledProvider(),
            restartRequestMode = resolveRestartRequestMode(),
            subscriptionCount = subscriptionCountProvider(),
            dynamicSubscriptionCount = dynamicSubscriptionCountProvider(),
            bangumiSubscriptionCount = bangumiSubscriptionCountProvider(),
            groupCount = groupCountProvider(),
            account = accountStatusProvider(),
            webSocket = buildWebSocketStatus(platformRuntimeStatus, platformObservability),
            todayPushStats = pushSnapshot.toWebUiDto(),
            recentPushRecords = pushSnapshot.recentRecords.map { record -> record.toWebUiDto() },
            host = hostStatusProvider(),
        )
    }

    /**
     * 运行态只输出一个面向 operator 的重启模式摘要，避免泄露更细的进程管理实现细节。
     */
    private fun resolveRestartRequestMode(): String {
        return if (restartSupportedProvider()) {
            "SUPERVISOR_CONTROLLED"
        } else {
            "MANUAL_RESTART_REQUIRED"
        }
    }

    /**
     * WebSocket 卡片同时参考连接健康和底层 session 活跃态，兼容不同平台 adapter 的观测深度。
     */
    private fun buildWebSocketStatus(
        runtimeStatus: PlatformRuntimeStatus,
        observability: PlatformObservabilitySnapshot,
    ): WebUiWebSocketStatusDto {
        val activeClients = observability.clients.filter { client -> client.webSocketSessionActive }
        val transports = observability.clients.map { client -> sanitizeRuntimeText("${client.adapterName}/${client.transportName}") }
        return WebUiWebSocketStatusDto(
            connected = runtimeStatus.connected || activeClients.isNotEmpty(),
            reconnectAttempts = runtimeStatus.reconnectAttempts,
            activeSessionCount = activeClients.size,
            transports = transports,
            note = observability.note?.let(::sanitizeRuntimeText),
        )
    }

}

/**
 * 默认账号状态只使用已加载的 Cookie 与 UID，避免首页轮询额外访问 B 站接口。
 */
private fun readDefaultAccountStatus(): WebUiBiliAccountStatusDto {
    val uid = BiliBiliBot.uid.takeIf { it > 0L }
    val cookieConfigured = runCatching {
        BiliConfigManager.config.accountConfig.cookie.isNotBlank() || !BiliBiliBot.cookie.isEmpty()
    }.getOrDefault(false)
    return WebUiBiliAccountStatusDto(
        loggedIn = uid != null,
        uid = uid,
        cookieConfigured = cookieConfigured,
    )
}

/**
 * 将 tasker 层快照转换为 WebUI DTO，保持 tasker 不依赖 webui 序列化模型。
 */
private fun DailyPushStatsSnapshot.toWebUiDto(): WebUiTodayPushStatsDto {
    return WebUiTodayPushStatsDto(
        date = date,
        total = total,
        dynamic = dynamic,
        live = live,
        liveClose = liveClose,
        failed = failed,
        lastSuccessAtEpochMillis = lastSuccessAtEpochMillis,
    )
}

/**
 * 最近推送记录的 WebUI 视图把原始类型名翻译成 operator 直接可读的标签。
 */
private fun PushDeliveryRecordSnapshot.toWebUiDto(): WebUiRecentPushRecordDto {
    return WebUiRecentPushRecordDto(
        timestampEpochMillis = timestampEpochMillis,
        type = type,
        typeLabel = when (type) {
            "DYNAMIC" -> "动态"
            "LIVE" -> "直播"
            "LIVE_CLOSE" -> "下播"
            else -> type
        },
        success = success,
        statusLabel = if (success) "已发送" else "发送失败",
        summary = sanitizeRuntimeText(summary),
        target = target?.let(::sanitizeRuntimeText),
    )
}

/**
 * 运行态文本只保留 Dashboard 可展示摘要，屏蔽本机路径、用户名、内网地址和凭据键值。
 */
private fun sanitizeRuntimeText(value: String): String {
    return value
        .replace(Regex("""(?i)\bauthorization\s*[:=]\s*bearer\s+[^\s,;]+"""), "authorization: bearer <redacted>")
        .replace(Regex("""(?i)\bbearer\s+[^\s,;]+"""), "bearer <redacted>")
        .replace(Regex("""(?i)\b(token|password|secret|cookie|api[_-]?key|access[_-]?token)\s*[:=]\s*[^\s,;]+"""), "$1=<redacted>")
        .replace(Regex("""\b(?:10|127)\.\d{1,3}\.\d{1,3}\.\d{1,3}\b"""), "<redacted>")
        .replace(Regex("""\b192\.168\.\d{1,3}\.\d{1,3}\b"""), "<redacted>")
        .replace(Regex("""\b172\.(?:1[6-9]|2\d|3[0-1])\.\d{1,3}\.\d{1,3}\b"""), "<redacted>")
        .replace(Regex("""\b169\.254\.\d{1,3}\.\d{1,3}\b"""), "<redacted>")
        .replace(Regex("""(?i)(?<![\w:])::1(?![\w:])"""), "<redacted>")
        .replace(Regex("""(?i)\b(?:fc|fd)[0-9a-f]{2}:[0-9a-f:]+\b"""), "<redacted>")
        .replace(Regex("""(?i)\bfe80:[0-9a-f:]+\b"""), "<redacted>")
        .replace(Regex("""\b[A-Za-z]:\\[^\s,;]+"""), "<redacted>")
        .replace(Regex("""(?<!:)\/(?:home|Users|mnt|var|etc|opt|srv|root|tmp|data)[^\s,;]*"""), "<redacted>")
}

/**
 * 默认宿主状态按请求即时采样，只读取本机公开指标，不新增后台采集任务。
 */
internal fun readHostRuntimeStatus(
    startedAtEpochMillisProvider: () -> Long = { BiliBiliBot.getStartTimeEpochMillis() },
    systemTimeMillisProvider: () -> Long = { System.currentTimeMillis() },
    osBeanProvider: () -> OperatingSystemMXBean = { ManagementFactory.getOperatingSystemMXBean() },
    rootFileProvider: () -> File = { File(".").absoluteFile },
    cpuLoadProvider: () -> Double? = { readCpuLoadRatio(osBeanProvider()) },
    systemLoadAverageProvider: () -> Double? = { readSystemLoadAverage(osBeanProvider()) },
    memoryUsageProvider: () -> WebUiResourceUsageDto = { readMemoryUsage(osBeanProvider()) },
): WebUiHostRuntimeStatusDto {
    return WebUiHostRuntimeStatusDto(
        startedAtEpochMillis = startedAtEpochMillisProvider(),
        systemTimeEpochMillis = systemTimeMillisProvider(),
        systemLoadAverage = systemLoadAverageProvider(),
        cpuUsagePercent = cpuLoadProvider()?.let { ratio -> clampPercent(ratio * 100.0) },
        memory = sanitizeUsage(memoryUsageProvider()),
        storage = readStorageUsage(rootFileProvider()),
    )
}

/**
 * 读取系统 CPU 负载比例；JDK 不支持时返回 null，让前端展示不可用状态。
 */
private fun readCpuLoadRatio(osBean: OperatingSystemMXBean): Double? {
    val sunBean = osBean as? SunOperatingSystemMXBean ?: return null
    val value = runCatching { sunBean.cpuLoad }
        .recoverCatching { sunBean.processCpuLoad }
        .getOrDefault(-1.0)
    return value.takeIf { it >= 0.0 }
}

/**
 * 读取系统 load average；Windows 等不支持的平台会返回负数，此时降级为 null。
 */
private fun readSystemLoadAverage(osBean: OperatingSystemMXBean): Double? {
    return osBean.systemLoadAverage.takeIf { it >= 0.0 }
}

/**
 * 优先使用系统物理内存指标，不可用时回退到 JVM heap 视角，保证首页仍有可解释数值。
 */
private fun readMemoryUsage(osBean: OperatingSystemMXBean): WebUiResourceUsageDto {
    val sunBean = osBean as? SunOperatingSystemMXBean
    val total = runCatching { sunBean?.totalMemorySize ?: 0L }.getOrDefault(0L)
    val free = runCatching { sunBean?.freeMemorySize ?: 0L }.getOrDefault(0L)
    if (total > 0L && free >= 0L) {
        val used = (total - free).coerceAtLeast(0L)
        return usageOf(used, total)
    }

    val runtime = Runtime.getRuntime()
    val jvmTotal = runtime.maxMemory().coerceAtLeast(0L)
    val jvmUsed = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L)
    return usageOf(jvmUsed, jvmTotal)
}

/**
 * 存储指标以当前工作目录所在磁盘为准，和项目配置、数据、日志目录的默认落点保持一致。
 */
private fun readStorageUsage(rootFile: File): WebUiResourceUsageDto {
    val total = rootFile.totalSpace.coerceAtLeast(0L)
    val usable = rootFile.usableSpace.coerceAtLeast(0L)
    val used = (total - usable).coerceAtLeast(0L)
    return usageOf(used, total)
}

/**
 * 统一创建资源使用率对象，避免 total 为 0 或 used 超界时产生无意义百分比。
 */
private fun usageOf(usedBytes: Long, totalBytes: Long): WebUiResourceUsageDto {
    val safeTotal = totalBytes.coerceAtLeast(0L)
    val safeUsed = usedBytes.coerceIn(0L, safeTotal.takeIf { it > 0L } ?: Long.MAX_VALUE)
    return WebUiResourceUsageDto(
        usedBytes = safeUsed,
        totalBytes = safeTotal,
        usagePercent = if (safeTotal > 0L) clampPercent(safeUsed.toDouble() * 100.0 / safeTotal.toDouble()) else null,
    )
}

/**
 * 清洗外部 provider 传入的使用率，确保 WebUI 进度条永远只接收 0 到 100 的百分比。
 */
private fun sanitizeUsage(usage: WebUiResourceUsageDto): WebUiResourceUsageDto {
    return usage.copy(
        usedBytes = usage.usedBytes.coerceAtLeast(0L),
        totalBytes = usage.totalBytes.coerceAtLeast(0L),
        usagePercent = usage.usagePercent?.let(::clampPercent),
    )
}

/**
 * 百分比清洗集中在这里，避免 NaN 或无穷值泄漏到 JSON 响应。
 */
private fun clampPercent(value: Double): Double? {
    if (value.isNaN() || value.isInfinite()) {
        return null
    }
    return value.coerceIn(0.0, 100.0)
}
