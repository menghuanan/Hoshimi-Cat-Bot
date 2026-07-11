package top.bilibili.service

import top.bilibili.connector.MessageLogSimplifier as ConnectorMessageLogSimplifier

/**
 * 为业务层日志提供稳定的文本简化入口，并复用平台中立的 connector 日志规则。
 */
object MessageLogSimplifier {
    /**
     * 简化原始平台消息，并在输入过长时先截断保护日志体积。
     */
    fun simplifyIncomingRaw(rawMessage: String, onTooLong: (Int) -> Unit): String {
        return ConnectorMessageLogSimplifier.simplifyIncomingRaw(rawMessage, onTooLong)
    }
}
