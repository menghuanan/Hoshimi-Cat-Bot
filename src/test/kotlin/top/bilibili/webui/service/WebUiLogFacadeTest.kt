package top.bilibili.webui.service

import java.nio.charset.StandardCharsets
import java.nio.file.Files
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
     * 清空日志只允许作用于固定白名单 source，避免前端按钮变成任意文件截断入口。
     */
    @Test
    fun `clear should truncate only fixed log source files`() {
        val logFile = tempRoot.resolve("bilibili-bot.log")
        Files.writeString(logFile, "line-1\nline-2\n", StandardCharsets.UTF_8)
        val facade = WebUiLogFacade(
            sourceResolvers = mapOf(
                "main" to { logFile.toFile() },
            ),
        )

        val result = facade.clearLogSource("main")
        val rejected = facade.clearLogSource("../secrets")

        assertNotNull(result)
        assertTrue(result.cleared)
        assertEquals("main", result.sourceId)
        assertTrue(result.bytesBefore > 0L)
        assertEquals("", Files.readString(logFile, StandardCharsets.UTF_8))
        assertNull(rejected)
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
}
