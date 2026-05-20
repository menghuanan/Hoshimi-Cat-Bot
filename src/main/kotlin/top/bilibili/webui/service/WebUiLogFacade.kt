package top.bilibili.webui.service

import top.bilibili.webui.model.WebUiLogSourceDto
import top.bilibili.webui.model.WebUiLogSourceListDto
import top.bilibili.webui.model.WebUiLogWindowDto
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * WebUI 日志 facade 只允许读取固定白名单来源，避免管理页演变成任意路径文件浏览器。
 */
class WebUiLogFacade(
    private val sourceResolvers: Map<String, () -> File?> = defaultLogSourceResolvers(),
    private val maxTailLines: Int = 200,
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
        val resolver = sourceResolvers[sourceId] ?: return null
        if (sourceId.contains("..") || sourceId.contains('/') || sourceId.contains('\\')) {
            return null
        }

        val logFile = resolver() ?: return null
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
        val windowLines = lines.takeLast(boundedTailLines)
        return WebUiLogWindowDto(
            sourceId = sourceId,
            title = defaultLogSourceTitle(sourceId),
            requestedTailLines = boundedTailLines,
            availableTailLines = availableTailLines,
            lineCount = windowLines.size,
            text = windowLines.joinToString(System.lineSeparator()),
            lastModifiedEpochMillis = logFile.lastModified(),
            hasMore = lines.size > windowLines.size,
            sourceMissing = false,
        )
    }

    /**
     * tail 行数统一收口到安全范围内，避免单次请求读取过大窗口拖慢本地管理面。
     */
    private fun boundedTailLines(requestedTailLines: Int): Int {
        return requestedTailLines.coerceIn(1, maxTailLines)
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
