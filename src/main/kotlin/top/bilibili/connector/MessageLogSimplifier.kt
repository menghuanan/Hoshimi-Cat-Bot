package top.bilibili.connector

/**
 * 平台中立的日志消息段，只保留生成可读摘要所需字段。
 */
data class MessageLogPart(
    val type: String,
    val data: Map<String, String> = emptyMap(),
)

/**
 * 将原始消息与结构化消息段收敛成短日志文本，避免协议细节反向泄漏到业务层。
 */
object MessageLogSimplifier {
    private const val MAX_INPUT_LENGTH = 10_000
    private const val MAX_OUTPUT_LENGTH = 100
    private val cqPattern = "\\[CQ:([^,\\]]+)(?:,([^\\]]*))?\\]".toRegex()

    /**
     * 简化原始消息文本，并在输入过长时先截断以保护日志体积。
     */
    fun simplifyIncomingRaw(rawMessage: String, onTooLong: (Int) -> Unit): String {
        val safeMessage = if (rawMessage.length > MAX_INPUT_LENGTH) {
            onTooLong(rawMessage.length)
            rawMessage.take(MAX_INPUT_LENGTH)
        } else {
            rawMessage
        }

        if (!safeMessage.contains("[CQ:")) {
            return truncate(safeMessage)
        }

        val result = StringBuilder()
        var lastIndex = 0
        cqPattern.findAll(safeMessage).forEach { match ->
            if (match.range.first > lastIndex) {
                result.append(safeMessage.substring(lastIndex, match.range.first))
            }
            val params = parseParams(match.groups[2]?.value.orEmpty())
            result.append(placeholderForType(match.groupValues[1], params))
            lastIndex = match.range.last + 1
        }

        if (lastIndex < safeMessage.length) {
            result.append(safeMessage.substring(lastIndex))
        }

        return truncate(result.toString())
    }

    /**
     * 将结构化消息段渲染成紧凑日志文本，供不同 connector 复用一致的摘要规则。
     */
    fun simplifyParts(parts: List<MessageLogPart>): String {
        val rendered = buildString {
            parts.forEach { part ->
                if (part.type == "text") {
                    append(part.data["text"].orEmpty())
                } else {
                    append(placeholderForType(part.type, part.data))
                }
            }
        }
        return truncate(rendered)
    }

    /**
     * 将通用消息类型转换为稳定占位符，未知类型保留类型名以便排查。
     */
    internal fun placeholderForType(type: String, data: Map<String, String> = emptyMap()): String = when (type) {
        "image" -> "[图片]"
        "face" -> "[表情]"
        "at" -> if (data["qq"] == "all") "[@全体]" else "[@]"
        "reply" -> "[回复]"
        "video" -> "[视频]"
        "record" -> "[语音]"
        "file" -> "[文件]"
        "json" -> "[JSON消息]"
        "xml" -> "[XML消息]"
        "rps" -> "[猜拳]"
        "dice" -> "[骰子]"
        "shake" -> "[抖动]"
        "poke" -> "[戳一戳]"
        "share" -> "[分享]"
        "contact" -> "[联系人]"
        "location" -> "[位置]"
        "music" -> "[音乐]"
        "forward" -> "[合并转发]"
        "node" -> "[转发节点]"
        "markdown" -> "[Markdown]"
        "lightapp" -> "[轻应用]"
        "mface" -> "[大表情]"
        else -> "[未知类型:$type]"
    }

    /**
     * CQ 参数只做日志展示级解析，不参与协议重写或业务判断。
     */
    private fun parseParams(rawParams: String): Map<String, String> {
        if (rawParams.isBlank()) {
            return emptyMap()
        }
        return rawParams.split(',')
            .mapNotNull { entry ->
                val index = entry.indexOf('=')
                if (index <= 0) null else entry.substring(0, index) to entry.substring(index + 1)
            }
            .toMap()
    }

    /**
     * 对最终日志摘要应用统一长度限制，避免文本与占位符组合后再次超长。
     */
    private fun truncate(text: String): String {
        return if (text.length > MAX_OUTPUT_LENGTH) text.take(MAX_OUTPUT_LENGTH) + "..." else text
    }
}
