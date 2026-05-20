package top.bilibili.webui.service

import top.bilibili.core.BiliBiliBot
import top.bilibili.webui.model.WebUiActionConfirmationRequestDto
import top.bilibili.webui.model.WebUiActionRequestDto
import top.bilibili.webui.model.WebUiActionResultDto

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
        return WebUiActionResultDto(
            success = true,
            action = request.action,
            message = "configuration reload requested",
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
        return WebUiActionResultDto(
            success = true,
            action = request.action,
            message = "graceful shutdown requested",
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
        return WebUiActionResultDto(
            success = true,
            action = request.action,
            message = if (autoRestartSupported) {
                "restart requested; external supervisor is expected to bring the service back"
            } else {
                "restart requested; graceful shutdown has been scheduled and manual restart is required"
            },
            gracefulStopScheduled = true,
            restartExpected = autoRestartSupported,
            inProcessRestartPerformed = false,
            autoRestartSupported = autoRestartSupported,
        )
    }
}
