package top.bilibili.webui.service

import top.bilibili.service.QrLoginCancelResult
import top.bilibili.service.QrLoginCoordinator
import top.bilibili.service.QrLoginPhase
import top.bilibili.service.QrLoginSessionSnapshot
import top.bilibili.service.QrLoginStartResult
import top.bilibili.webui.model.WebUiBiliLoginPhase
import top.bilibili.webui.model.WebUiBiliLoginSessionDto
import java.util.Base64

/** WebUI 创建结果保留路由映射所需分支，不把 HTTP 状态码下沉到 service facade。 */
sealed interface WebUiBiliLoginStartOutcome {
    data class Created(val session: WebUiBiliLoginSessionDto) : WebUiBiliLoginStartOutcome
    data class Conflict(
        val phase: WebUiBiliLoginPhase,
        val remainingSeconds: Long?,
    ) : WebUiBiliLoginStartOutcome
    data class Unavailable(val message: String) : WebUiBiliLoginStartOutcome
}

/** WebUI 取消结果与协调器一一对应，路由据此实现幂等终态和提交冲突。 */
enum class WebUiBiliLoginCancelOutcome {
    CANCELLED,
    COMMITTING,
    ALREADY_TERMINAL,
    NOT_FOUND,
}

/**
 * WebUI 二维码登录 facade 只投影脱敏状态和 PNG Base64，不接触认证、Cookie 或回调 URL。
 */
class WebUiBiliLoginFacade(
    private val startLogin: suspend () -> QrLoginStartResult = {
        QrLoginCoordinator.shared.start("webui")
    },
    private val readSnapshot: (String) -> QrLoginSessionSnapshot? = QrLoginCoordinator.shared::snapshot,
    private val cancelLogin: (String) -> QrLoginCancelResult = QrLoginCoordinator.shared::cancel,
) {
    /** 创建响应是唯一允许携带二维码图片的 DTO。 */
    suspend fun start(): WebUiBiliLoginStartOutcome {
        return when (val result = startLogin()) {
            is QrLoginStartResult.Started -> WebUiBiliLoginStartOutcome.Created(
                result.snapshot.toWebDto(Base64.getEncoder().encodeToString(result.qrImageBytes)),
            )
            is QrLoginStartResult.Conflict -> WebUiBiliLoginStartOutcome.Conflict(
                phase = result.phase.toWebPhase(),
                remainingSeconds = result.remainingSeconds,
            )
            is QrLoginStartResult.Failed -> WebUiBiliLoginStartOutcome.Unavailable(result.message)
        }
    }

    /** 状态轮询只读取脱敏快照，禁止重复返回二维码图片。 */
    fun read(sessionId: String): WebUiBiliLoginSessionDto? {
        return readSnapshot(sessionId)?.toWebDto(qrImageBase64 = null)
    }

    /** 取消语义保持结构化映射，终态幂等由路由结合 read() 返回。 */
    fun cancel(sessionId: String): WebUiBiliLoginCancelOutcome {
        return when (cancelLogin(sessionId)) {
            QrLoginCancelResult.CANCELLED -> WebUiBiliLoginCancelOutcome.CANCELLED
            QrLoginCancelResult.COMMITTING -> WebUiBiliLoginCancelOutcome.COMMITTING
            QrLoginCancelResult.ALREADY_TERMINAL -> WebUiBiliLoginCancelOutcome.ALREADY_TERMINAL
            QrLoginCancelResult.NOT_FOUND -> WebUiBiliLoginCancelOutcome.NOT_FOUND
        }
    }
}

/** service phase 显式映射到浏览器枚举，后续内部重构不会改变 HTTP 字段。 */
private fun QrLoginSessionSnapshot.toWebDto(qrImageBase64: String?): WebUiBiliLoginSessionDto {
    return WebUiBiliLoginSessionDto(
        sessionId = sessionId,
        phase = phase.toWebPhase(),
        expiresAtEpochMillis = expiresAtEpochMillis,
        message = message,
        qrImageBase64 = qrImageBase64,
    )
}

/** service phase 统一转换到 WebUI 契约，session 与 conflict 响应不得出现两套映射。 */
private fun QrLoginPhase.toWebPhase(): WebUiBiliLoginPhase {
    return when (this) {
        QrLoginPhase.WAITING_FOR_SCAN -> WebUiBiliLoginPhase.WAITING_FOR_SCAN
        QrLoginPhase.WAITING_FOR_CONFIRMATION -> WebUiBiliLoginPhase.WAITING_FOR_CONFIRMATION
        QrLoginPhase.COMMITTING -> WebUiBiliLoginPhase.COMMITTING
        QrLoginPhase.SUCCEEDED -> WebUiBiliLoginPhase.SUCCEEDED
        QrLoginPhase.EXPIRED -> WebUiBiliLoginPhase.EXPIRED
        QrLoginPhase.FAILED -> WebUiBiliLoginPhase.FAILED
        QrLoginPhase.CANCELLED -> WebUiBiliLoginPhase.CANCELLED
    }
}
