package top.bilibili.webui.service

import top.bilibili.webui.model.WebUiLogSourceDto
import top.bilibili.webui.model.WebUiLogSourceListDto
import top.bilibili.webui.model.WebUiLogWindowDto
import top.bilibili.webui.model.WebUiLogClearResultDto
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.nio.charset.StandardCharsets

/**
 * WebUI 日志 facade 只允许读取固定白名单来源，并按当前运行周期裁掉上次启动遗留的旧日志。
 */
class WebUiLogFacade(
    private val sourceResolvers: Map<String, () -> File?> = defaultLogSourceResolvers(),
    private val maxTailLines: Int = 500,
    private val startupEpochMillis: Long = 0L,
) {
    /**
     * 返回当前允许暴露给管理页的固定日志来源。
     */
    fun listSources(): WebUiLogSourceListDto {
        return WebUiLogSourceListDto(
            sources = sourceResolvers.keys.map { sourceId ->
                WebUiLogSourceDto(
                    id = sourceId,
                    title = defaultLogSourceTitle(sourceId),
                )
            },
        )
    }

    /**
     * 只按固定 source id 读取 UTF-8 tail 窗口；未知或危险 source 一律拒绝。
     */
    fun readLogWindow(sourceId: String, tailLines: Int): WebUiLogWindowDto? {
        val logFile = resolveFixedLogFile(sourceId) ?: return null
        val availableTailLines = tailLinePresets()
        if (!logFile.exists() || !logFile.isFile) {
            return WebUiLogWindowDto(
                sourceId = sourceId,
                title = defaultLogSourceTitle(sourceId),
                requestedTailLines = boundedTailLines(tailLines),
                availableTailLines = availableTailLines,
                lineCount = 0,
                text = "",
                lastModifiedEpochMillis = 0L,
                hasMore = false,
                sourceMissing = true,
            )
        }

        val boundedTailLines = boundedTailLines(tailLines)
        val lines = logFile.readLines(StandardCharsets.UTF_8)
        // 启动边界以下的旧日志会在服务端统一裁掉，页面只保留本次运行产生的可见行。
        val visibleLines = filterLogLines(lines)
        val windowLines = visibleLines.takeLast(boundedTailLines)
        return WebUiLogWindowDto(
            sourceId = sourceId,
            title = defaultLogSourceTitle(sourceId),
            requestedTailLines = boundedTailLines,
            availableTailLines = availableTailLines,
            lineCount = windowLines.size,
            text = windowLines.joinToString(System.lineSeparator()),
            lastModifiedEpochMillis = logFile.lastModified(),
            hasMore = visibleLines.size > windowLines.size,
            sourceMissing = false,
        )
    }

    /**
     * 清空固定日志源时只截断已解析的白名单文件；缺失文件返回可显示状态而不是创建新文件。
     */
    fun clearLogSource(sourceId: String): WebUiLogClearResultDto? {
        val logFile = resolveFixedLogFile(sourceId) ?: return null
        if (!logFile.exists() || !logFile.isFile) {
            return WebUiLogClearResultDto(
                sourceId = sourceId,
                title = defaultLogSourceTitle(sourceId),
                cleared = false,
                sourceMissing = true,
                bytesBefore = 0L,
                lastModifiedEpochMillis = 0L,
            )
        }

        val bytesBefore = logFile.length()
        logFile.writeText("", StandardCharsets.UTF_8)
        return WebUiLogClearResultDto(
            sourceId = sourceId,
            title = defaultLogSourceTitle(sourceId),
            cleared = true,
            sourceMissing = false,
            bytesBefore = bytesBefore,
            lastModifiedEpochMillis = logFile.lastModified(),
        )
    }

    /**
     * 导出日志复用 tail 边界，避免下载接口绕开页面读取窗口的大小限制。
     */
    fun exportLogText(sourceId: String, tailLines: Int): String? {
        return readLogWindow(sourceId, tailLines)?.text
    }

    /**
     * 固定日志源解析集中做 source id 校验，所有读取、清空和导出入口共用同一安全边界。
     */
    private fun resolveFixedLogFile(sourceId: String): File? {
        val resolver = sourceResolvers[sourceId] ?: return null
        if (sourceId.contains("..") || sourceId.contains('/') || sourceId.contains('\\')) {
            return null
        }
        return resolver()
    }

    /**
     * tail 行数统一收口到安全范围内，避免单次请求读取过大窗口拖慢本地管理面。
     */
    private fun boundedTailLines(requestedTailLines: Int): Int {
        return requestedTailLines.coerceIn(1, maxTailLines)
    }

    /**
     * 日志窗口先按启动时间裁去旧会话，再保留同一事件的堆栈续行，避免把上次关机前的尾巴拼进当前页面。
     */
    private fun filterLogLines(lines: List<String>): List<String> {
        if (startupEpochMillis <= 0L) {
            return lines
        }

        val visibleLines = mutableListOf<String>()
        var currentEntryVisible = false
        var sawTimestampedLine = false

        for (line in lines) {
            val lineTimestamp = parseLogTimestampMillis(line)
            if (lineTimestamp != null) {
                sawTimestampedLine = true
                currentEntryVisible = lineTimestamp >= startupEpochMillis
                if (currentEntryVisible) {
                    visibleLines.add(line)
                }
                continue
            }

            if (currentEntryVisible) {
                visibleLines.add(line)
            }
        }

        return if (sawTimestampedLine) visibleLines else lines
    }

    /**
     * 固定日志格式带有本地时区时间戳，先解析成毫秒值再和启动边界比较。
     */
    private fun parseLogTimestampMillis(line: String): Long? {
        val match = LOG_TIMESTAMP_PATTERN.find(line) ?: return null
        val parsedTime = LocalDateTime.parse(match.groupValues[1], LOG_TIMESTAMP_FORMATTER)
        return parsedTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    /**
     * 日志窗口提供固定尾部预设，方便前端直接切换常用历史长度而不暴露任意分页接口。
     */
    private fun tailLinePresets(): List<Int> {
        return listOf(20, 50, maxTailLines)
            .map { it.coerceAtMost(maxTailLines) }
            .filter { it > 0 }
            .distinct()
            .sorted()
    }

    companion object {
        private val LOG_TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        private val LOG_TIMESTAMP_PATTERN = Regex("""^(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\s+\[[^\]]+]""")

        /**
         * 默认日志来源固定到主日志、错误日志和最新守护日志，避免暴露任意文件路径。
         */
        private fun defaultLogSourceResolvers(): Map<String, () -> File?> {
            return linkedMapOf(
                "main" to { File("logs", "bilibili-bot.log") },
                "error" to { File("logs", "error.log") },
                "guardian" to {
                    File("logs/daemon")
                        .takeIf { it.isDirectory }
                        ?.listFiles { file -> file.isFile && file.name.startsWith("Daemon_") && file.name.endsWith(".log") }
                        ?.maxByOrNull { file -> file.lastModified() }
                },
            )
        }
    }
}

/**
 * 日志来源标题集中收口，便于前端稳定展示且不依赖本地文件名细节。
 */
private fun defaultLogSourceTitle(sourceId: String): String {
    return when (sourceId) {
        "main" -> "Main Log"
        "error" -> "Error Log"
        "guardian" -> "Latest Guardian Log"
        else -> sourceId
    }
}
