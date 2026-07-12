package top.bilibili.webui.model

import kotlinx.serialization.Serializable

/** WebUI 二维码登录阶段与浏览器契约固定，避免前端依赖 service 层枚举。 */
@Serializable
enum class WebUiBiliLoginPhase {
    WAITING_FOR_SCAN,
    WAITING_FOR_CONFIRMATION,
    COMMITTING,
    SUCCEEDED,
    EXPIRED,
    FAILED,
    CANCELLED,
}

/** 创建登录会话只接收内存确认密码，不接收二维码或 Cookie 材料。 */
@Serializable
data class WebUiBiliLoginStartRequestDto(
    val confirmationPassword: String,
)

/** 会话 DTO 只在创建响应携带二维码 Base64，后续轮询返回 null。 */
@Serializable
data class WebUiBiliLoginSessionDto(
    val sessionId: String,
    val phase: WebUiBiliLoginPhase,
    val expiresAtEpochMillis: Long,
    val message: String,
    val qrImageBase64: String? = null,
)

/** 全局会话冲突只暴露脱敏 phase 与可选租约时间，提交态不伪造 retryAfter。 */
@Serializable
data class WebUiBiliLoginConflictDto(
    val message: String,
    val phase: WebUiBiliLoginPhase,
    val remainingSeconds: Long? = null,
)

/** 通用失败响应保持最小消息字段，避免异常对象进入浏览器。 */
@Serializable
data class WebUiBiliLoginErrorDto(
    val message: String,
)
