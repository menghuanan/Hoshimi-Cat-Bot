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
import top.bilibili.BiliAccountConfig
import top.bilibili.BiliConfig
import top.bilibili.config.BotConfig
import top.bilibili.config.NapCatConfig
import top.bilibili.config.PlatformConfig
import top.bilibili.webui.auth.WebUiAuthService
import top.bilibili.webui.auth.WebUiCredentialStore
import top.bilibili.webui.auth.WebUiTokenService
import top.bilibili.webui.config.WebUiConfig
import top.bilibili.webui.model.WebUiActionConfirmationRequestDto
import top.bilibili.webui.model.WebUiActionResultDto
import top.bilibili.webui.model.WebUiAuthResponseDto
import top.bilibili.webui.model.WebUiBiliConfigWriteRequestDto
import top.bilibili.webui.model.WebUiConfigFileDto
import top.bilibili.webui.model.WebUiConfigSaveResultDto
import top.bilibili.webui.model.WebUiLogSourceListDto
import top.bilibili.webui.model.WebUiLogWindowDto
import top.bilibili.webui.model.WebUiLoginRequestDto
import top.bilibili.webui.model.WebUiRuntimeSummaryDto
import top.bilibili.webui.model.WebUiSessionDto
import top.bilibili.webui.model.WebUiChangePasswordRequestDto
import top.bilibili.webui.server.installWebUiModule
import top.bilibili.webui.service.WebUiActionFacade
import top.bilibili.webui.service.WebUiAuditService
import top.bilibili.webui.service.WebUiConfigFacade
import top.bilibili.webui.service.WebUiConfigWriteFacade
import top.bilibili.webui.service.WebUiLogFacade
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
                configWriteFacade = buildConfigWriteFacade(),
                logFacade = buildLogFacade(),
                actionFacade = buildActionFacade(),
                auditService = WebUiAuditService(sink = {}),
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
                configWriteFacade = buildConfigWriteFacade(),
                logFacade = buildLogFacade(),
                actionFacade = buildActionFacade(),
                auditService = WebUiAuditService(sink = {}),
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

    @Test
    fun `config save routes should stay file scoped reject stale snapshots and require stronger confirmation`() = testApplication {
        val authService = buildAuthService()
        val bootstrapPassword = authService.bootstrapCredentials().initialPassword!!
        var currentBiliConfig = BiliConfig(
            adminContact = "onebot11:private:1",
            accountConfig = BiliAccountConfig(cookie = "raw-cookie"),
        )
        var savedConfig: BiliConfig? = null
        val configFacade = WebUiConfigFacade(
            biliConfigProvider = { currentBiliConfig },
            botConfigProvider = { BotConfig() },
        )
        val configWriteFacade = WebUiConfigWriteFacade(
            configFacade = configFacade,
            biliConfigProvider = { currentBiliConfig },
            botConfigProvider = { BotConfig() },
            saveBiliConfigAction = { updated ->
                savedConfig = updated
                currentBiliConfig = updated
            },
        )

        application {
            installWebUiModule(
                settings = WebUiConfig(enabled = true).toSettings(tempRoot.toFile()),
                authService = authService,
                runtimeFacade = buildRuntimeFacade(),
                configFacade = configFacade,
                configWriteFacade = configWriteFacade,
                logFacade = buildLogFacade(),
                actionFacade = buildActionFacade(),
                auditService = WebUiAuditService(sink = {}),
            )
        }

        val token = reloginForPhase3(authService, bootstrapPassword)
        val currentSnapshot = createWebUiClient().get("/api/config/bili-config") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.body<WebUiConfigFileDto>()
        val missingConfirmation = createWebUiClient().post("/api/config/bili-config") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(
                WebUiBiliConfigWriteRequestDto(
                    snapshotToken = currentSnapshot.snapshotToken,
                    adminContact = "onebot11:private:2",
                    cookie = "",
                    baiduAppId = "",
                    baiduSecurityKey = "",
                    debugMode = false,
                    confirmationPassword = "",
                ),
            )
        }
        val staleSnapshot = createWebUiClient().post("/api/config/bili-config") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(
                WebUiBiliConfigWriteRequestDto(
                    snapshotToken = "stale-token",
                    adminContact = "onebot11:private:2",
                    cookie = "",
                    baiduAppId = "",
                    baiduSecurityKey = "",
                    debugMode = false,
                    confirmationPassword = "Better123!@",
                ),
            )
        }
        val saved = createWebUiClient().post("/api/config/bili-config") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(
                WebUiBiliConfigWriteRequestDto(
                    snapshotToken = currentSnapshot.snapshotToken,
                    adminContact = "onebot11:private:2",
                    cookie = "",
                    baiduAppId = "",
                    baiduSecurityKey = "",
                    debugMode = true,
                    confirmationPassword = "Better123!@",
                ),
            )
        }

        assertEquals(HttpStatusCode.Forbidden, missingConfirmation.status)
        assertEquals(HttpStatusCode.Conflict, staleSnapshot.status)
        assertEquals(HttpStatusCode.OK, saved.status)
        assertEquals("onebot11:private:2", savedConfig?.adminContact)
        assertEquals("raw-cookie", savedConfig?.accountConfig?.cookie)
        assertTrue(saved.body<WebUiConfigSaveResultDto>().persisted)
    }

    @Test
    fun `log routes and action routes should expose fixed sources and keep action semantics distinct`() = testApplication {
        val authService = buildAuthService()
        val bootstrapPassword = authService.bootstrapCredentials().initialPassword!!
        val logFile = tempRoot.resolve("bilibili-bot.log")
        Files.writeString(logFile, "line-1\nline-2\nline-3\n")
        var reloadCalls = 0
        var shutdownCalls = 0

        application {
            installWebUiModule(
                settings = WebUiConfig(enabled = true).toSettings(tempRoot.toFile()),
                authService = authService,
                runtimeFacade = buildRuntimeFacade(),
                configFacade = buildConfigFacade(),
                configWriteFacade = buildConfigWriteFacade(),
                logFacade = WebUiLogFacade(
                    sourceResolvers = mapOf("main" to { logFile.toFile() }),
                    maxTailLines = 2,
                ),
                actionFacade = WebUiActionFacade(
                    reloadAction = { reloadCalls += 1 },
                    shutdownAction = { shutdownCalls += 1 },
                    restartSupportedProvider = { false },
                ),
                auditService = WebUiAuditService(sink = {}),
            )
        }

        val token = reloginForPhase3(authService, bootstrapPassword)
        val sourceList = createWebUiClient().get("/api/logs/sources") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val logWindow = createWebUiClient().get("/api/logs/main?tail=20") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val reload = createWebUiClient().post("/api/actions/reload-config") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(WebUiActionConfirmationRequestDto("Better123!@"))
        }
        val restart = createWebUiClient().post("/api/actions/request-restart") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(WebUiActionConfirmationRequestDto("Better123!@"))
        }

        assertEquals(HttpStatusCode.OK, sourceList.status)
        assertEquals(HttpStatusCode.OK, logWindow.status)
        assertEquals(HttpStatusCode.OK, reload.status)
        assertEquals(HttpStatusCode.OK, restart.status)
        assertEquals(listOf("main"), sourceList.body<WebUiLogSourceListDto>().sources.map { source -> source.id })
        assertEquals(2, logWindow.body<WebUiLogWindowDto>().lineCount)
        assertEquals("reload-config", reload.body<WebUiActionResultDto>().action)
        assertEquals("request-restart", restart.body<WebUiActionResultDto>().action)
        assertEquals(1, reloadCalls)
        assertEquals(1, shutdownCalls)
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

    private fun buildConfigWriteFacade(): WebUiConfigWriteFacade {
        return WebUiConfigWriteFacade(
            configFacade = buildConfigFacade(),
            botConfigProvider = { BotConfig() },
        )
    }

    private fun buildLogFacade(): WebUiLogFacade {
        val logFile = tempRoot.resolve("default.log")
        Files.writeString(logFile, "default-line-1\ndefault-line-2\n")
        return WebUiLogFacade(
            sourceResolvers = mapOf("main" to { logFile.toFile() }),
            maxTailLines = 2,
        )
    }

    private fun buildActionFacade(): WebUiActionFacade {
        return WebUiActionFacade(
            reloadAction = {},
            shutdownAction = {},
            restartSupportedProvider = { false },
        )
    }

    /**
     * Phase 3 路由 smoke 统一复用登录+改密流程，确保后续保存和动作请求处于已认证且非强制改密状态。
     */
    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.reloginForPhase3(
        authService: WebUiAuthService,
        bootstrapPassword: String,
    ): String {
        val client = createWebUiClient()
        client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(WebUiLoginRequestDto(password = bootstrapPassword))
        }
        client.post("/api/auth/change-password") {
            contentType(ContentType.Application.Json)
            val bootstrapToken = authService.login(bootstrapPassword).token!!
            header(HttpHeaders.Authorization, "Bearer $bootstrapToken")
            setBody(
                WebUiChangePasswordRequestDto(
                    currentPassword = bootstrapPassword,
                    newPassword = "Better123!@",
                ),
            )
        }
        return client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(WebUiLoginRequestDto(password = "Better123!@"))
        }.body<WebUiAuthResponseDto>().token!!
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
