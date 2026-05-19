package top.bilibili.webui

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.bilibili.config.BotConfig
import top.bilibili.webui.auth.WebUiAuthService
import top.bilibili.webui.auth.WebUiCredentialStore
import top.bilibili.webui.auth.WebUiTokenService
import top.bilibili.webui.config.WebUiConfig
import top.bilibili.webui.model.WebUiAuthResponseDto
import top.bilibili.webui.model.WebUiConfigFileDto
import top.bilibili.webui.model.WebUiLoginRequestDto
import top.bilibili.webui.model.WebUiRuntimeSummaryDto
import top.bilibili.webui.model.WebUiSessionDto
import top.bilibili.webui.model.WebUiChangePasswordRequestDto
import top.bilibili.webui.server.installWebUiModule
import top.bilibili.webui.service.WebUiConfigFacade
import top.bilibili.webui.service.WebUiRuntimeFacade

class WebUiRouteSmokeTest {
    private val tempRoot = Files.createTempDirectory("webui-route-smoke")

    @AfterTest
    fun cleanup() {
        tempRoot.toFile().deleteRecursively()
    }

    @Test
    fun `unauthenticated protected apis should be rejected and login route should be reachable`() = testApplication {
        application {
            installWebUiModule(
                settings = WebUiConfig(enabled = true).toSettings(tempRoot.toFile()),
                authService = buildAuthService(),
                runtimeFacade = buildRuntimeFacade(),
                configFacade = buildConfigFacade(),
            )
        }

        val webUiClient = createWebUiClient()
        val protectedResponse = webUiClient.get("/api/runtime/summary")
        val loginPage = webUiClient.get("/login")
        val root = webUiClient.get("/")

        assertEquals(HttpStatusCode.Unauthorized, protectedResponse.status)
        assertEquals(HttpStatusCode.OK, loginPage.status)
        assertEquals(HttpStatusCode.Found, root.status)
    }

    @Test
    fun `authenticated runtime and config routes should respond successfully after forced password change`() = testApplication {
        val authService = buildAuthService()
        val bootstrapPassword = authService.bootstrapCredentials().initialPassword!!

        application {
            installWebUiModule(
                settings = WebUiConfig(enabled = true).toSettings(tempRoot.toFile()),
                authService = authService,
                runtimeFacade = buildRuntimeFacade(),
                configFacade = buildConfigFacade(),
            )
        }

        val webUiClient = createWebUiClient()
        val firstLogin = webUiClient.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(WebUiLoginRequestDto(password = bootstrapPassword))
        }.body<WebUiAuthResponseDto>()
        val firstToken = firstLogin.token!!
        val forcedBlocked = webUiClient.get("/api/runtime/summary") {
            header(HttpHeaders.Authorization, "Bearer $firstToken")
        }
        val changed = webUiClient.post("/api/auth/change-password") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $firstToken")
            setBody(
                WebUiChangePasswordRequestDto(
                    currentPassword = bootstrapPassword,
                    newPassword = "Better123!@",
                ),
            )
        }
        val relogin = webUiClient.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(WebUiLoginRequestDto(password = "Better123!@"))
        }.body<WebUiAuthResponseDto>()
        val token = relogin.token!!
        val runtimeResponse = webUiClient.get("/api/runtime/summary") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val configResponse = webUiClient.get("/api/config/bot") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val rootResponse = webUiClient.get("/") {
            header(HttpHeaders.Cookie, "${top.bilibili.webui.routes.WebUiTokenCookieName}=$token")
        }
        val sessionProbe = webUiClient.get("/api/auth/session") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.body<WebUiSessionDto>()

        assertTrue(firstLogin.mustChangePassword)
        assertEquals(HttpStatusCode.Forbidden, forcedBlocked.status)
        assertEquals(HttpStatusCode.OK, changed.status)
        assertEquals(HttpStatusCode.OK, runtimeResponse.status)
        assertEquals(HttpStatusCode.OK, configResponse.status)
        assertEquals(HttpStatusCode.OK, rootResponse.status)
        assertEquals(false, sessionProbe.mustChangePassword)
        assertEquals(true, sessionProbe.authenticated)
        assertEquals("RUNNING", runtimeResponse.body<WebUiRuntimeSummaryDto>().lifecycleState)
        assertEquals("bot.yml", configResponse.body<WebUiConfigFileDto>().sourceFile)
    }

    private fun buildAuthService(): WebUiAuthService {
        val store = WebUiCredentialStore(tempRoot.resolve("webui-credentials.json").toFile())
        val tokenService = WebUiTokenService(tokenTtlSeconds = 300L)
        return WebUiAuthService(
            credentialStore = store,
            tokenService = tokenService,
        )
    }

    private fun buildRuntimeFacade(): WebUiRuntimeFacade {
        return WebUiRuntimeFacade(
            lifecycleStateProvider = { "RUNNING" },
            uptimeSecondsProvider = { 42L },
            platformAdapterInitializedProvider = { true },
            webUiEnabledProvider = { true },
            subscriptionCountProvider = { 5 },
            groupCountProvider = { 2 },
        )
    }

    private fun buildConfigFacade(): WebUiConfigFacade {
        return WebUiConfigFacade(
            botConfigProvider = { BotConfig() },
        )
    }

    /**
     * WebUI smoke client显式关闭自动重定向，并安装 JSON 协商，确保断言的是原始路由边界而不是浏览器跟随后的结果。
     */
    private fun io.ktor.server.testing.ApplicationTestBuilder.createWebUiClient() = createClient {
        followRedirects = false
        install(ContentNegotiation) {
            json(top.bilibili.utils.json)
        }
    }
}
