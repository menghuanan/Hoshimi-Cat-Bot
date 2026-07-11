package top.bilibili.service

import kotlinx.serialization.Serializable
import top.bilibili.connector.PlatformCapabilityService

/**
 * 管理员操作通知的结构化消息体，由服务层统一格式化并投递。
 */
@Serializable
data class ActionMessage(
    val operator: String,
    val target: String,
    val action: String,
    val message: String,
)

/**
 * 按字段构造管理员操作通知并发送，避免调用方重复拼装审计消息。
 */
suspend fun actionNotify(subject: String?, operator: String, target: String, action: String, message: String) {
    actionNotify(subject, ActionMessage(operator, target, action, message))
}

/**
 * 发送结构化管理员操作通知，并在发送前应用管理通知开关与联系人能力判断。
 */
suspend fun actionNotify(subject: String?, message: ActionMessage) {
    if (PlatformCapabilityService.canSendManagedAdminNotice(subject = subject)) {
        actionNotify(buildString {
            appendLine("操作人: ${message.operator}")
            appendLine("目标: ${message.target}")
            appendLine("操作: ${message.action}")
            appendLine("消息: ${message.message}")
        })
    }
}

/**
 * 发送纯文本管理员通知；网关发送失败时回退为日志记录以保留审计信息。
 */
suspend fun actionNotify(message: String) {
    if (!PlatformCapabilityService.canSendManagedAdminNotice()) return
    val success = MessageGatewayProvider.require().sendAdminMessage(message)
    if (!success) {
        // 回退到日志输出，是为了在网关不可用时仍保留关键管理审计信息。
        logger.info("通知消息: $message")
    }
}
