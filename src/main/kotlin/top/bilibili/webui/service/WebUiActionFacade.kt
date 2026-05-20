package top.bilibili.webui.service

import top.bilibili.core.BiliBiliBot
import top.bilibili.webui.model.WebUiActionConfirmationRequestDto
import top.bilibili.webui.model.WebUiActionRequestDto
import top.bilibili.webui.model.WebUiActionResultDto
import top.bilibili.webui.model.WebUiActionOutcome

/**
 * WebUI 动作 facade 只负责把 reload、shutdown 和 restart-request 映射到明确分离的运行语义。
 */
class WebUiActionFacade(
    private val reloadAction: () -> Unit = {
        BiliBiliBot.reloadManagedConfiguration()
    },
    private val shutdownAction: () -> Unit = {
        BiliBiliBot.stop("webui-shutdown")
    },
    private val restartSupportedProvider: () -> Boolean = { false },
) {
    /**
     * reload 只执行受控配置重载，不触发停机或重启语义。
     */
    fun reloadConfig(
        request: WebUiActionRequestDto,
        confirmation: WebUiActionConfirmationRequestDto,
    ): WebUiActionResultDto {
        reloadAction()
        return buildResult(
            request = request,
            outcome = WebUiActionOutcome.RELOAD_CONFIG_REQUESTED,
            message = "configuration reload requested",
            operatorHint = "Refresh the config panels and inspect the runtime summary after reload.",
            gracefulStopScheduled = false,
            restartExpected = false,
            inProcessRestartPerformed = false,
            autoRestartSupported = restartSupportedProvider(),
        )
    }

    /**
     * shutdown 只表达优雅停机请求，不把它升级成 reload 或 restart。
     */
    fun shutdown(
        request: WebUiActionRequestDto,
        confirmation: WebUiActionConfirmationRequestDto,
    ): WebUiActionResultDto {
        shutdownAction()
        return buildResult(
            request = request,
            outcome = WebUiActionOutcome.GRACEFUL_SHUTDOWN_REQUESTED,
            message = "graceful shutdown requested",
            operatorHint = "Wait for the process to exit before starting it again.",
            gracefulStopScheduled = true,
            restartExpected = false,
            inProcessRestartPerformed = false,
            autoRestartSupported = restartSupportedProvider(),
        )
    }

    /**
     * restart-request 从不在进程内自行拉起新实例；它只表达重启意图并通过优雅停机交给外部环境处理。
     */
    fun requestRestart(
        request: WebUiActionRequestDto,
        confirmation: WebUiActionConfirmationRequestDto,
    ): WebUiActionResultDto {
        val autoRestartSupported = restartSupportedProvider()
        shutdownAction()
        return buildResult(
            request = request,
            outcome = if (autoRestartSupported) {
                WebUiActionOutcome.RESTART_REQUESTED_WITH_SUPERVISOR
            } else {
                WebUiActionOutcome.RESTART_REQUESTED_MANUAL_FALLBACK
            },
            message = if (autoRestartSupported) {
                "restart requested; external supervisor is expected to bring the service back"
            } else {
                "restart requested; graceful shutdown has been scheduled and manual restart is required"
            },
            operatorHint = if (autoRestartSupported) {
                "Watch the supervisor or container manager for the next process start."
            } else {
                "Restart the bot manually after the stop completes."
            },
            gracefulStopScheduled = true,
            restartExpected = autoRestartSupported,
            inProcessRestartPerformed = false,
            autoRestartSupported = autoRestartSupported,
        )
    }

    /**
     * 动作结果统一收口到同一构造路径，避免每个方法各自拼字段导致语义漂移。
     */
    private fun buildResult(
        request: WebUiActionRequestDto,
        outcome: WebUiActionOutcome,
        message: String,
        operatorHint: String,
        gracefulStopScheduled: Boolean,
        restartExpected: Boolean,
        inProcessRestartPerformed: Boolean,
        autoRestartSupported: Boolean,
    ): WebUiActionResultDto {
        return WebUiActionResultDto(
            success = true,
            action = request.action,
            outcome = outcome,
            message = message,
            operatorHint = operatorHint,
            gracefulStopScheduled = gracefulStopScheduled,
            restartExpected = restartExpected,
            inProcessRestartPerformed = inProcessRestartPerformed,
            autoRestartSupported = autoRestartSupported,
        )
    }
}
