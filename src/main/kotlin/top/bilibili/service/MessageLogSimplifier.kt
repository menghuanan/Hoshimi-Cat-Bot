package top.bilibili.service

import top.bilibili.connector.onebot11.core.OneBot11MessageLogSimplifier

/**
 * 为业务层日志提供稳定的文本简化入口；结构化 vendor 段简化留在 connector 内部。
 */
object MessageLogSimplifier {
    /**
     * 简化原始 OneBot 文本消息，并在输入过长时先截断保护日志体积。
     */
    fun simplifyIncomingRaw(rawMessage: String, onTooLong: (Int) -> Unit): String {
        return OneBot11MessageLogSimplifier.simplifyIncomingRaw(rawMessage, onTooLong)
    }
}
