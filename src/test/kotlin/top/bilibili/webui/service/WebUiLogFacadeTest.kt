package top.bilibili.webui.service

import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.ZoneId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebUiLogFacadeTest {
    private val tempRoot = Files.createTempDirectory("webui-log-facade")

    @AfterTest
    fun cleanup() {
        tempRoot.toFile().deleteRecursively()
    }

    @Test
    fun `only allowed fixed log sources should be exposed`() {
        val facade = WebUiLogFacade(
            sourceResolvers = mapOf(
                "main" to { tempRoot.resolve("bilibili-bot.log").toFile() },
                "error" to { tempRoot.resolve("error.log").toFile() },
            ),
        )

        val listedSources = facade.listSources().sources

        assertEquals(listOf("main", "error"), listedSources.map { it.id })
    }

    @Test
    fun `arbitrary path traversal or unknown source ids should be rejected`() {
        val facade = WebUiLogFacade(
            sourceResolvers = mapOf(
                "main" to { tempRoot.resolve("bilibili-bot.log").toFile() },
            ),
        )

        assertNull(facade.readLogWindow("../secrets", tailLines = 20))
        assertNull(facade.readLogWindow("missing", tailLines = 20))
    }

    @Test
    fun `tail windows should return bounded utf8 content with metadata`() {
        val logFile = tempRoot.resolve("bilibili-bot.log")
        Files.writeString(
            logFile,
            buildString {
                appendLine("第一行")
                appendLine("第二行")
                appendLine("第三行")
                appendLine("第四行")
            },
            StandardCharsets.UTF_8,
        )
        val facade = WebUiLogFacade(
            sourceResolvers = mapOf(
                "main" to { logFile.toFile() },
            ),
            maxTailLines = 2,
        )

        val window = facade.readLogWindow("main", tailLines = 20)

        assertNotNull(window)
        assertEquals("main", window.sourceId)
        assertEquals(2, window.lineCount)
        assertEquals(listOf(2), window.availableTailLines)
        assertTrue(window.text.contains("第三行"))
        assertTrue(window.text.contains("第四行"))
        assertFalse(window.text.contains("第一行"))
        assertTrue(window.hasMore)
        assertFalse(window.sourceMissing)
        assertTrue(window.lastModifiedEpochMillis > 0L)
    }

    /**
     * 启动边界之前的旧会话日志应在服务端被裁掉，窗口只展示本次运行的新日志。
     */
    @Test
    fun `startup boundary should hide previous session log lines`() {
        val logFile = tempRoot.resolve("bilibili-bot.log")
        Files.writeString(
            logFile,
            buildString {
                appendLine("2026-05-23 09:59:59.000 [main] INFO top.bilibili.Main - before start")
                appendLine("before stack")
                appendLine("2026-05-23 10:00:00.000 [main] INFO top.bilibili.Main - after start")
                appendLine("after stack")
                appendLine("2026-05-23 10:00:01.000 [main] INFO top.bilibili.Main - later")
            },
            StandardCharsets.UTF_8,
        )
        val startupEpochMillis = LocalDateTime.of(2026, 5, 23, 10, 0, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val facade = WebUiLogFacade(
            sourceResolvers = mapOf(
                "main" to { logFile.toFile() },
            ),
            startupEpochMillis = startupEpochMillis,
        )

        val window = facade.readLogWindow("main", tailLines = 20)

        assertNotNull(window)
        assertFalse(window.text.contains("before start"))
        assertFalse(window.text.contains("before stack"))
        assertTrue(window.text.contains("after start"))
        assertTrue(window.text.contains("after stack"))
        assertTrue(window.text.contains("later"))
        assertEquals(3, window.lineCount)
        assertFalse(window.hasMore)
    }

    /**
     * 固定日志来源即使当前文件不存在，也应返回可调试的空窗口元数据，而不是把 source 语义直接丢失。
     */
    @Test
    fun `missing fixed log files should still return empty debug metadata`() {
        val facade = WebUiLogFacade(
            sourceResolvers = mapOf(
                "main" to { tempRoot.resolve("missing.log").toFile() },
            ),
            maxTailLines = 10,
        )

        val window = facade.readLogWindow("main", tailLines = 40)

        assertNotNull(window)
        assertEquals(0, window.lineCount)
        assertEquals(10, window.requestedTailLines)
        assertEquals(listOf(10), window.availableTailLines)
        assertTrue(window.sourceMissing)
    }

    /**
     * 导出日志复用受限 tail 窗口，确保下载内容和页面可见来源保持同一安全边界。
     */
    @Test
    fun `export should return bounded utf8 content for fixed source`() {
        val logFile = tempRoot.resolve("bilibili-bot.log")
        Files.writeString(
            logFile,
            "line-1\nline-2\nline-3\n",
            StandardCharsets.UTF_8,
        )
        val facade = WebUiLogFacade(
            sourceResolvers = mapOf(
                "main" to { logFile.toFile() },
            ),
            maxTailLines = 2,
        )

        val exportText = facade.exportLogText("main", tailLines = 20)

        assertNotNull(exportText)
        assertFalse(exportText.contains("line-1"))
        assertTrue(exportText.contains("line-2"))
        assertTrue(exportText.contains("line-3"))
    }

    /**
     * 单行异常日志也必须受字节窗口保护，避免没有换行时从文件尾一路读完整个大文件。
     */
    @Test
    fun `tail reader should cap oversized single line logs by bytes`() {
        val logFile = tempRoot.resolve("bilibili-bot.log")
        Files.writeString(
            logFile,
            "x".repeat(128 * 1024),
            StandardCharsets.UTF_8,
        )
        val facade = WebUiLogFacade(
            sourceResolvers = mapOf(
                "main" to { logFile.toFile() },
            ),
            maxTailLines = 1,
        )

        val window = facade.readLogWindow("main", tailLines = 1)

        assertNotNull(window)
        assertEquals(1, window.lineCount)
        assertTrue(window.text.isNotBlank())
        assertTrue(window.text.length <= 40_000, "tail text length=${window.text.length}")
        assertTrue(window.hasMore)
    }

    /**
     * 日志读取实现必须保持 bounded tail 语义，避免未来回退成整文件 readLines()。
     */
    @Test
    fun `log facade implementation should not read the entire file into memory`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/top/bilibili/webui/service/WebUiLogFacade.kt"),
            StandardCharsets.UTF_8,
        )

        assertFalse(source.contains(".readLines("))
    }
}
