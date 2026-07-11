package top.bilibili.connector.onebot11.core

import top.bilibili.connector.MessageLogPart
import top.bilibili.connector.MessageLogSimplifier

/**
 * OneBot11 日志简化使用的最小消息段模型，vendor DTO 必须先转换到这里。
 */
data class MessageLogSegment(
    val type: String,
    val data: Map<String, String> = emptyMap(),
)

/**
 * 将 OneBot11 原始消息与消息段收敛成可读日志文本，避免日志中充斥长 CQ 片段。
 */
object OneBot11MessageLogSimplifier {
    /**
     * 简化原始 OneBot 文本消息，并在输入过长时先截断保护日志体积。
     */
    fun simplifyIncomingRaw(rawMessage: String, onTooLong: (Int) -> Unit): String {
        return MessageLogSimplifier.simplifyIncomingRaw(rawMessage, onTooLong)
    }

    /**
     * 将结构化消息段渲染成紧凑日志文本，方便统一记录多种消息类型。
     */
    fun simplifySegments(segments: List<MessageLogSegment>): String {
        return MessageLogSimplifier.simplifyParts(segments.map { MessageLogPart(it.type, it.data) })
    }

}
