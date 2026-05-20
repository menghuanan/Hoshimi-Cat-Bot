package top.bilibili.webui.server

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import top.bilibili.webui.config.WebUiConfig
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WebUiManagerTest {
    private val tempRoot = Files.createTempDirectory("webui-manager-test")

    @AfterTest
    fun cleanup() {
        tempRoot.toFile().deleteRecursively()
    }

    /**
     * WebUI 首次启动时的密码提示应只保留中文说明和密码本身，避免把凭据路径和重复的英文键名带进日志。
     */
    @Test
    fun `bootstrap password log should only include password text`() {
        val logger = LoggerFactory.getLogger(WebUiManager::class.java) as Logger
        val previousLevel = logger.level
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        logger.level = Level.WARN

        val port = ServerSocket(0).use { socket -> socket.localPort }
        val manager = WebUiManager(WebUiConfig(enabled = true, port = port).toSettings(tempRoot.toFile()))

        try {
            manager.start()
            val warning = appender.list.firstOrNull { event ->
                event.level == Level.WARN && event.formattedMessage.contains("WebUI 初始密码已生成")
            }
            assertNotNull(warning)
            assertTrue(warning.formattedMessage.contains("密码="))
            assertFalse(warning.formattedMessage.contains("credentialFile="))
            assertFalse(warning.formattedMessage.contains("initialPassword="))
        } finally {
            manager.stop()
            logger.detachAppender(appender)
            logger.level = previousLevel
        }
    }
}
