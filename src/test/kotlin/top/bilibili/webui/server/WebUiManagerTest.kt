package top.bilibili.webui.server

import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.serialization.kotlinx.json.json
import org.slf4j.LoggerFactory
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import top.bilibili.webui.auth.WebUiAuthService
import top.bilibili.webui.auth.WebUiCredentialStore
import top.bilibili.webui.auth.WebUiTokenService
import top.bilibili.webui.config.WebUiConfig
import top.bilibili.webui.model.WebUiLoginRequestDto
import top.bilibili.webui.service.WebUiActionFacade
import top.bilibili.webui.service.WebUiAuditService
import top.bilibili.webui.service.WebUiConfigFacade
import top.bilibili.webui.service.WebUiConfigWriteFacade
import top.bilibili.webui.service.WebUiLogFacade
import top.bilibili.webui.service.WebUiRuntimeFacade
import java.net.ServerSocket
import java.nio.file.Files
import kotlinx.coroutines.delay
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
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

    /**
     * WebUI 请求边界应在内容过大前就拒绝登录请求，避免把超限 body 交给路由继续处理。
     */
    @Test
    fun `oversized request bodies should be rejected before login handling`() = testApplication {
        val authService = buildAuthService()
        authService.bootstrapCredentials()

        application {
            installWebUiModule(
                settings = WebUiConfig(enabled = true).toSettings(tempRoot.toFile()),
                authService = authService,
                runtimeFacade = WebUiRuntimeFacade(),
                configFacade = WebUiConfigFacade(),
                configWriteFacade = WebUiConfigWriteFacade(),
                logFacade = WebUiLogFacade(),
                actionFacade = WebUiActionFacade(),
                auditService = WebUiAuditService(sink = {}),
                requestTimeoutMillis = 5_000L,
                requestBodyLimitBytes = 128L,
            )
        }

        val response = createClient {
            followRedirects = false
            install(ContentNegotiation) {
                json(top.bilibili.utils.json)
            }
        }.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(WebUiLoginRequestDto(password = "x".repeat(512)))
        }

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertTrue(response.bodyAsText().contains("request body too large"))
    }

    /**
     * WebUI 慢请求应在受控超时内被取消，避免本地管理面把协程长期挂住。
     */
    @Test
    fun `slow webui requests should time out before hanging indefinitely`() = testApplication {
        val authService = buildAuthService()

        application {
            installWebUiModule(
                settings = WebUiConfig(enabled = true).toSettings(tempRoot.toFile()),
                authService = authService,
                runtimeFacade = WebUiRuntimeFacade(),
                configFacade = WebUiConfigFacade(),
                configWriteFacade = WebUiConfigWriteFacade(),
                logFacade = WebUiLogFacade(),
                actionFacade = WebUiActionFacade(),
                auditService = WebUiAuditService(sink = {}),
                requestTimeoutMillis = 100L,
                requestBodyLimitBytes = 1_024L,
            )
            routing {
                get("/api/slow") {
                    delay(300L)
                    call.respondText("too late")
                }
            }
        }

        val response = createClient {
            followRedirects = false
            install(ContentNegotiation) {
                json(top.bilibili.utils.json)
            }
        }.get("/api/slow")

        assertEquals(HttpStatusCode.RequestTimeout, response.status)
        assertTrue(response.bodyAsText().contains("request timed out"))
    }

    /**
     * WebUI 端口变化需要响应先返回 redirect，再由延迟计划切换监听器。
     */
    @Test
    fun `reload should return redirect url when port changes`() {
        val manager = WebUiManager(WebUiConfig(enabled = true, host = "127.0.0.1", port = 18080).toSettings(tempRoot.toFile()))

        val result = manager.planReload(WebUiConfig(enabled = true, host = "127.0.0.1", port = 18081).toSettings(tempRoot.toFile()))

        assertTrue(result.restartRequired)
        assertEquals("http://127.0.0.1:18081/", result.webUiRedirectUrl)
    }

    /**
     * token TTL 这类运行参数变化也需要重启 WebUI，但地址未变时不需要前端跳转。
     */
    @Test
    fun `reload should restart without redirect when only session settings change`() {
        val manager = WebUiManager(WebUiConfig(enabled = true, tokenTtlSeconds = 300L).toSettings(tempRoot.toFile()))

        val result = manager.planReload(WebUiConfig(enabled = true, tokenTtlSeconds = 600L).toSettings(tempRoot.toFile()))

        assertTrue(result.restartRequired)
        assertEquals(null, result.webUiRedirectUrl)
    }

    /**
     * 完全一致的设置不应创建重启计划，避免连续保存造成管理面无谓闪断。
     */
    @Test
    fun `reload should be no-op when settings are unchanged`() {
        val settings = WebUiConfig(enabled = true, port = 18080).toSettings(tempRoot.toFile())
        val manager = WebUiManager(settings)

        val result = manager.planReload(settings)

        assertFalse(result.restartRequired)
    }

    /**
     * 测试用凭据服务每次使用独立临时凭据文件，避免各个断言共享登录状态。
     */
    private fun buildAuthService(): WebUiAuthService {
        val store = WebUiCredentialStore(tempRoot.resolve("${System.nanoTime()}-webui-credentials.json").toFile())
        return WebUiAuthService(
            credentialStore = store,
            tokenService = WebUiTokenService(tokenTtlSeconds = 300L),
        )
    }
}
