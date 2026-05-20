package top.bilibili.webui.model

import kotlinx.serialization.Serializable

/**
 * 动作请求 DTO 只描述操作者想执行的高风险动作，不包含任何运行时内部引用。
 */
@Serializable
data class WebUiActionRequestDto(
    val action: String,
)

/**
 * 高风险动作确认 DTO 为后续 stronger confirmation 预留统一载体。
 */
@Serializable
data class WebUiActionConfirmationRequestDto(
    val confirmationPassword: String,
)

/**
 * 动作结果分类明确区分 reload、stop 和 restart-request 的实际效果，避免前端只看布尔值猜测语义。
 */
@Serializable
enum class WebUiActionOutcome {
    RELOAD_CONFIG_REQUESTED,
    GRACEFUL_SHUTDOWN_REQUESTED,
    RESTART_REQUESTED_WITH_SUPERVISOR,
    RESTART_REQUESTED_MANUAL_FALLBACK,
}

/**
 * 动作结果 DTO 明确返回停机、重启预期与运维提示，避免前端自行猜测实际语义。
 */
@Serializable
data class WebUiActionResultDto(
    val success: Boolean,
    val action: String,
    val outcome: WebUiActionOutcome,
    val message: String,
    val operatorHint: String,
    val gracefulStopScheduled: Boolean,
    val restartExpected: Boolean,
    val inProcessRestartPerformed: Boolean,
    val autoRestartSupported: Boolean,
)
