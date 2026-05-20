package top.bilibili.webui.service

import top.bilibili.BiliConfigManager
import top.bilibili.config.ConfigManager
import top.bilibili.core.BiliBiliBot
import top.bilibili.webui.model.WebUiRuntimeSummaryDto

/**
 * WebUI 运行态 facade 只读取现有公开运行状态，并映射为只读 DTO 快照。
 */
class WebUiRuntimeFacade(
    private val lifecycleStateProvider: () -> String = { BiliBiliBot.currentLifecycleState().name },
    private val uptimeSecondsProvider: () -> Long = { BiliBiliBot.getUptimeSeconds() },
    private val platformAdapterInitializedProvider: () -> Boolean = { BiliBiliBot.isPlatformAdapterInitialized() },
    private val webUiEnabledProvider: () -> Boolean = { runCatching { ConfigManager.botConfig.webui.enabled }.getOrDefault(false) },
    private val restartSupportedProvider: () -> Boolean = { false },
    private val subscriptionCountProvider: () -> Int = { runCatching { BiliConfigManager.data.dynamic.size }.getOrDefault(0) },
    private val groupCountProvider: () -> Int = { runCatching { BiliConfigManager.data.group.size }.getOrDefault(0) },
) {
    /**
     * 运行态响应始终是即时快照，避免前端持有对可变运行态对象的直接引用。
     */
    fun readSummary(): WebUiRuntimeSummaryDto {
        return WebUiRuntimeSummaryDto(
            lifecycleState = lifecycleStateProvider(),
            uptimeSeconds = uptimeSecondsProvider(),
            platformAdapterInitialized = platformAdapterInitializedProvider(),
            platformReady = platformAdapterInitializedProvider() && lifecycleStateProvider() == "RUNNING",
            webUiEnabled = webUiEnabledProvider(),
            restartRequestMode = resolveRestartRequestMode(),
            subscriptionCount = subscriptionCountProvider(),
            groupCount = groupCountProvider(),
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
}
