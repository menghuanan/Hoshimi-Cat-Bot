package top.bilibili.webui

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.bilibili.BiliData
import top.bilibili.BiliAccountConfig
import top.bilibili.BiliConfig
import top.bilibili.DynamicFilter
import top.bilibili.FilterMode
import top.bilibili.RegularFilter
import top.bilibili.SubData
import top.bilibili.TemplatePolicy
import top.bilibili.TypeFilter
import top.bilibili.config.BotConfig
import top.bilibili.config.NapCatConfig
import top.bilibili.config.PlatformConfig
import top.bilibili.webui.auth.WebUiAuthService
import top.bilibili.webui.auth.WebUiCredentialStore
import top.bilibili.webui.auth.WebUiTokenService
import top.bilibili.webui.config.WebUiConfig
import top.bilibili.webui.model.WebUiActionConfirmationRequestDto
import top.bilibili.webui.model.WebUiActionResultDto
import top.bilibili.webui.service.WebUiAuditRecord
import top.bilibili.webui.model.WebUiAuthResponseDto
import top.bilibili.webui.model.WebUiBiliAccountStatusDto
import top.bilibili.webui.model.WebUiHostRuntimeStatusDto
import top.bilibili.webui.model.WebUiBiliConfigWriteRequestDto
import top.bilibili.webui.model.WebUiConfigBatchSaveRequestDto
import top.bilibili.webui.model.WebUiConfigFileDto
import top.bilibili.webui.model.WebUiConfigHotReloadJobDto
import top.bilibili.webui.model.WebUiConfigHotReloadPhase
import top.bilibili.webui.model.WebUiConfigSaveResultDto
import top.bilibili.webui.model.WebUiLogSourceListDto
import top.bilibili.webui.model.WebUiLogWindowDto
import top.bilibili.webui.model.WebUiLoginRequestDto
import top.bilibili.webui.model.WebUiResourceUsageDto
import top.bilibili.webui.model.WebUiRuntimeSummaryDto
import top.bilibili.webui.model.WebUiSessionDto
import top.bilibili.webui.model.WebUiChangePasswordRequestDto
import top.bilibili.webui.model.WebUiSubscriptionAtAllSaveRequestDto
import top.bilibili.webui.model.WebUiSubscriptionCreateRequestDto
import top.bilibili.webui.model.WebUiSubscriptionFilterListDto
import top.bilibili.webui.model.WebUiSubscriptionFilterSaveRequestDto
import top.bilibili.webui.model.WebUiSubscriptionMutationResultDto
import top.bilibili.webui.model.WebUiSubscriptionTemplateListDto
import top.bilibili.webui.model.WebUiSubscriptionTemplateRandomRequestDto
import top.bilibili.webui.model.WebUiSubscriptionTemplateSaveRequestDto
import top.bilibili.webui.model.WebUiSubscriptionThemeDto
import top.bilibili.webui.model.WebUiSubscriptionThemeSaveRequestDto
import top.bilibili.tasker.DailyPushStatsSnapshot
import top.bilibili.tasker.PushDeliveryRecordSnapshot
import top.bilibili.webui.server.installWebUiModule
import top.bilibili.webui.service.WebUiActionFacade
import top.bilibili.webui.service.WebUiAuditService
import top.bilibili.webui.service.WebUiConfigFacade
import top.bilibili.webui.service.WebUiConfigWriteFacade
import top.bilibili.webui.service.WebUiLogFacade
import top.bilibili.webui.service.WebUiRuntimeFacade
import top.bilibili.webui.service.WebUiSubscriptionManagementFacade
import top.bilibili.connector.PlatformHttpClientSnapshot
import top.bilibili.connector.PlatformObservabilitySnapshot
import top.bilibili.connector.PlatformRuntimeStatus

class WebUiRouteSmokeTest {
    private val tempRoot = Files.createTempDirectory("webui-route-smoke")
    private val originalDataVersion = BiliData.dataVersion
    private val originalDynamic = BiliData.dynamic.toMutableMap()
    private val originalGroup = BiliData.group.toMutableMap()
    private val originalBlacklist = BiliData.linkParseBlacklistContacts.toMutableSet()
    private val originalFilter = BiliData.filter.toMutableMap()
    private val originalDynamicTemplatePolicies = BiliData.dynamicTemplatePolicyByScope.toMutableMap()
    private val originalDynamicColorByUid = BiliData.dynamicColorByUid.toMutableMap()
    private val originalAtAll = BiliData.atAll.toMutableMap()

    @AfterTest
    fun cleanup() {
        tempRoot.toFile().deleteRecursively()
        BiliData.dataVersion = originalDataVersion
        BiliData.dynamic = originalDynamic.toMutableMap()
        BiliData.group = originalGroup.toMutableMap()
        BiliData.linkParseBlacklistContacts = originalBlacklist.toMutableSet()
        BiliData.filter = originalFilter.toMutableMap()
        BiliData.dynamicTemplatePolicyByScope = originalDynamicTemplatePolicies.toMutableMap()
        BiliData.dynamicColorByUid = originalDynamicColorByUid.toMutableMap()
        BiliData.atAll = originalAtAll.toMutableMap()
    }

    @Test
    fun `unauthenticated protected apis should be rejected and login route should be reachable`() = testApplication {
        val records = mutableListOf<WebUiAuditRecord>()
        application {
            installWebUiModule(
                settings = WebUiConfig(enabled = true).toSettings(tempRoot.toFile()),
                authService = buildAuthService(),
                runtimeFacade = buildRuntimeFacade(),
                configFacade = buildConfigFacade(),
                configWriteFacade = buildConfigWriteFacade(),
                logFacade = buildLogFacade(),
                actionFacade = buildActionFacade(),
                auditService = WebUiAuditService(sink = { record -> records += record }),
            )
        }

        val webUiClient = createWebUiClient()
        val protectedResponse = webUiClient.get("/api/runtime/summary")
        val loginPage = webUiClient.get("/login")
        val root = webUiClient.get("/")

        assertEquals(HttpStatusCode.Unauthorized, protectedResponse.status)
        assertEquals(HttpStatusCode.OK, loginPage.status)
        assertEquals(HttpStatusCode.Found, root.status)
        assertTrue(records.any { it.target == "/api/runtime/summary" && !it.success })
    }

    /**
     * WebUI HTML 与敏感 API 响应必须带统一安全头；CORS 只允许显式匹配的本机 Origin。
     */
    @Test
    fun `webui responses should include security headers and explicit cors only`() = testApplication {
        val authService = buildAuthService()
        val bootstrapPassword = authService.bootstrapCredentials().initialPassword!!

        application {
            installWebUiModule(
                settings = WebUiConfig(enabled = true, host = "127.0.0.1", port = 18080).toSettings(tempRoot.toFile()),
                authService = authService,
                runtimeFacade = buildRuntimeFacade(),
                configFacade = buildConfigFacade(),
                configWriteFacade = buildConfigWriteFacade(),
                logFacade = buildLogFacade(),
                actionFacade = buildActionFacade(),
                auditService = WebUiAuditService(sink = {}),
            )
        }

        val client = createWebUiClient()
        val auth = reloginForPhase3(authService, bootstrapPassword)
        val loginPage = client.get("/login")
        val runtimeResponse = client.get("/api/runtime/summary") {
            header(HttpHeaders.Cookie, auth.cookieHeader())
        }
        val allowedPreflight = client.options("/api/runtime/summary") {
            header(HttpHeaders.Origin, "http://127.0.0.1:18080")
            header(HttpHeaders.AccessControlRequestMethod, HttpMethod.Get.value)
        }
        val rejectedPreflight = client.options("/api/runtime/summary") {
            header(HttpHeaders.Origin, "http://evil.example")
            header(HttpHeaders.AccessControlRequestMethod, HttpMethod.Get.value)
        }

        assertEquals(HttpStatusCode.OK, loginPage.status)
        assertEquals(HttpStatusCode.OK, runtimeResponse.status)
        assertEquals("default-src 'self'; base-uri 'self'; frame-ancestors 'none'; object-src 'none'", loginPage.headers["Content-Security-Policy"])
        assertEquals("DENY", runtimeResponse.headers["X-Frame-Options"])
        assertEquals("nosniff", runtimeResponse.headers["X-Content-Type-Options"])
        assertEquals("no-referrer", runtimeResponse.headers["Referrer-Policy"])
        assertEquals("camera=(), microphone=(), geolocation=()", runtimeResponse.headers["Permissions-Policy"])
        assertEquals("no-store", runtimeResponse.headers[HttpHeaders.CacheControl])
        assertEquals(HttpStatusCode.OK, allowedPreflight.status)
        assertEquals("http://127.0.0.1:18080", allowedPreflight.headers[HttpHeaders.AccessControlAllowOrigin])
        assertEquals(HttpStatusCode.Forbidden, rejectedPreflight.status)
        assertEquals(null, rejectedPreflight.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    /**
     * 绑定 0.0.0.0 时，同源静态资源仍要按浏览器实际访问的内网地址放行，否则 WebUI 会在脚本阶段白屏。
     */
    @Test
    fun `webui assets should stay reachable for same origin lan access when host is all interfaces`() = testApplication {
        val authService = buildAuthService()
        authService.bootstrapCredentials()

        application {
            installWebUiModule(
                settings = WebUiConfig(enabled = true, host = "0.0.0.0", port = 18080).toSettings(tempRoot.toFile()),
                authService = authService,
                runtimeFacade = buildRuntimeFacade(),
                configFacade = buildConfigFacade(),
                configWriteFacade = buildConfigWriteFacade(),
                logFacade = buildLogFacade(),
                actionFacade = buildActionFacade(),
                auditService = WebUiAuditService(sink = {}),
            )
        }

        val origin = "http://192.168.10.4:18080"
        val response = createWebUiClient().get("/assets/app.js") {
            header(HttpHeaders.Origin, origin)
            header(HttpHeaders.Host, "192.168.10.4:18080")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(origin, response.headers[HttpHeaders.AccessControlAllowOrigin])
        assertTrue(response.bodyAsText().contains("createRoot"))
    }

    /**
     * 常见公网入口会通过反代或隧道传递外部协议和主机名，WebUI 只应放行这些入口自身的同源 Origin。
     */
    @Test
    fun `webui assets should stay reachable through common proxy and tunnel origins`() = testApplication {
        val authService = buildAuthService()
        authService.bootstrapCredentials()

        application {
            installWebUiModule(
                settings = WebUiConfig(enabled = true, host = "0.0.0.0", port = 18080).toSettings(tempRoot.toFile()),
                authService = authService,
                runtimeFacade = buildRuntimeFacade(),
                configFacade = buildConfigFacade(),
                configWriteFacade = buildConfigWriteFacade(),
                logFacade = buildLogFacade(),
                actionFacade = buildActionFacade(),
                auditService = WebUiAuditService(sink = {}),
            )
        }

        val client = createWebUiClient()
        val httpsReverseProxy = client.get("/assets/app.js") {
            header(HttpHeaders.Origin, "https://bot.example.com")
            header(HttpHeaders.Host, "127.0.0.1:18080")
            header("X-Forwarded-Proto", "https")
            header("X-Forwarded-Host", "bot.example.com")
        }
        val standardForwardedProxy = client.get("/assets/app.js") {
            header(HttpHeaders.Origin, "https://cf.example.com")
            header(HttpHeaders.Host, "127.0.0.1:18080")
            header(HttpHeaders.Forwarded, """for=203.0.113.10;proto=https;host="cf.example.com"""")
        }
        val tailnetDirectHost = client.get("/assets/app.css") {
            header(HttpHeaders.Origin, "http://nas.tailnet.ts.net:18080")
            header(HttpHeaders.Host, "nas.tailnet.ts.net:18080")
        }
        val rejectedCrossSite = client.get("/assets/app.js") {
            header(HttpHeaders.Origin, "https://evil.example")
            header(HttpHeaders.Host, "127.0.0.1:18080")
            header("X-Forwarded-Proto", "https")
            header("X-Forwarded-Host", "bot.example.com")
        }

        assertEquals(HttpStatusCode.OK, httpsReverseProxy.status)
        assertEquals("https://bot.example.com", httpsReverseProxy.headers[HttpHeaders.AccessControlAllowOrigin])
        assertTrue(httpsReverseProxy.bodyAsText().contains("createRoot"))
        assertEquals(HttpStatusCode.OK, standardForwardedProxy.status)
        assertEquals("https://cf.example.com", standardForwardedProxy.headers[HttpHeaders.AccessControlAllowOrigin])
        assertEquals(HttpStatusCode.OK, tailnetDirectHost.status)
        assertEquals("http://nas.tailnet.ts.net:18080", tailnetDirectHost.headers[HttpHeaders.AccessControlAllowOrigin])
        assertEquals(HttpStatusCode.Forbidden, rejectedCrossSite.status)
        assertEquals(null, rejectedCrossSite.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    /**
     * 未处理异常对浏览器只返回脱敏错误，不能把本机路径、用户名或 token 字样透出。
     */
    @Test
    fun `unhandled webui exceptions should return sanitized browser errors`() = testApplication {
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
            routing {
                get("/boom") {
                    // 测试专用路由模拟异常路径，验证全局异常响应不会泄露本机细节。
                    error("""failed at C:\Users\alice\bot token=raw-token""")
                }
            }
        }

        val response = createWebUiClient().get("/boom")
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertFalse(body.contains("alice"))
        assertFalse(body.contains("raw-token"))
        assertTrue(body.contains("internal server error"))
    }

    /**
     * 登录失败审计需要包含来源和 UA 摘要，便于后台区分本机误输与异常探测。
     */
    @Test
    fun `failed login audit should include source user agent count and time`() = testApplication {
        val records = mutableListOf<WebUiAuditRecord>()
        val authService = buildAuthService()
        authService.bootstrapCredentials()

        application {
            installWebUiModule(
                settings = WebUiConfig(enabled = true).toSettings(tempRoot.toFile()),
                authService = authService,
                runtimeFacade = buildRuntimeFacade(),
                configFacade = buildConfigFacade(),
                configWriteFacade = buildConfigWriteFacade(),
                logFacade = buildLogFacade(),
                actionFacade = buildActionFacade(),
                auditService = WebUiAuditService(
                    sink = { record -> records += record },
                    clockMillis = { 1779254400000L },
                ),
            )
        }

        val client = createWebUiClient()
        val failed = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.UserAgent, "Mozilla/5.0 WebUI-Smoke")
            setBody(WebUiLoginRequestDto(password = "wrong-password"))
        }

        val loginAudit = records.first { it.target == "login" && !it.success }
        assertEquals(HttpStatusCode.Unauthorized, failed.status)
        assertTrue(loginAudit.detailSummary.contains("sourceIp="))
        assertTrue(loginAudit.detailSummary.contains("userAgent=Mozilla/5.0"))
        assertTrue(loginAudit.detailSummary.contains("failureCount=1"))
        assertTrue(loginAudit.detailSummary.contains("occurredAtEpochMillis=1779254400000"))
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
        }
        val firstAuth = extractLoginCookies(firstLogin)
        val forcedBlocked = webUiClient.get("/api/runtime/summary") {
            header(HttpHeaders.Cookie, firstAuth.cookieHeader())
        }
        val changed = webUiClient.post("/api/auth/change-password") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Cookie, firstAuth.cookieHeader())
            header("X-CSRF-Token", firstAuth.csrfToken)
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
        }
        val auth = extractLoginCookies(relogin)
        val runtimeResponse = webUiClient.get("/api/runtime/summary") {
            header(HttpHeaders.Cookie, auth.cookieHeader())
        }
        val configResponse = webUiClient.get("/api/config/bot") {
            header(HttpHeaders.Cookie, auth.cookieHeader())
        }
        val rootResponse = webUiClient.get("/") {
            header(HttpHeaders.Cookie, auth.cookieHeader())
        }
        val settingsPathResponse = webUiClient.get("/settings") {
            header(HttpHeaders.Cookie, auth.cookieHeader())
        }
        val subscriptionsPathResponse = webUiClient.get("/subscriptions") {
            header(HttpHeaders.Cookie, auth.cookieHeader())
        }
        val logsPathResponse = webUiClient.get("/logs") {
            header(HttpHeaders.Cookie, auth.cookieHeader())
        }
        val reactScriptResponse = webUiClient.get("/assets/app.js")
        val reactStyleResponse = webUiClient.get("/assets/app.css")
        val sessionProbe = webUiClient.get("/api/auth/session") {
            header(HttpHeaders.Cookie, auth.cookieHeader())
        }.body<WebUiSessionDto>()

        assertTrue(firstLogin.body<WebUiAuthResponseDto>().mustChangePassword)
        assertEquals(HttpStatusCode.Forbidden, forcedBlocked.status)
        assertEquals(HttpStatusCode.OK, changed.status)
        assertEquals(HttpStatusCode.OK, runtimeResponse.status)
        assertEquals(HttpStatusCode.OK, configResponse.status)
        assertEquals(HttpStatusCode.OK, rootResponse.status)
        assertEquals(HttpStatusCode.OK, settingsPathResponse.status)
        assertEquals(HttpStatusCode.OK, subscriptionsPathResponse.status)
        assertEquals(HttpStatusCode.OK, logsPathResponse.status)
        assertEquals(HttpStatusCode.OK, reactScriptResponse.status)
        assertEquals(HttpStatusCode.OK, reactStyleResponse.status)
        assertTrue(settingsPathResponse.bodyAsText().contains("""id="root""""))
        assertTrue(subscriptionsPathResponse.bodyAsText().contains("""id="root""""))
        assertTrue(logsPathResponse.bodyAsText().contains("""id="root""""))
        assertTrue(reactScriptResponse.bodyAsText().contains("createRoot"))
        assertTrue(reactStyleResponse.bodyAsText().contains("tailwindcss"))
        assertEquals(false, sessionProbe.mustChangePassword)
        assertEquals(true, sessionProbe.authenticated)
        val runtimeBody = runtimeResponse.body<WebUiRuntimeSummaryDto>()
        assertEquals("RUNNING", runtimeBody.lifecycleState)
        assertEquals("v-test", runtimeBody.appVersion)
        assertEquals(true, runtimeBody.platformReady)
        assertEquals("MANUAL_RESTART_REQUIRED", runtimeBody.restartRequestMode)
        assertEquals(5, runtimeBody.subscriptionCount)
        assertEquals(4, runtimeBody.dynamicSubscriptionCount)
        assertEquals(1, runtimeBody.bangumiSubscriptionCount)
        assertEquals(true, runtimeBody.account.loggedIn)
        assertEquals(2233L, runtimeBody.account.uid)
        assertEquals(true, runtimeBody.webSocket.connected)
        assertEquals(6, runtimeBody.todayPushStats.total)
        assertEquals(1779250800000L, runtimeBody.host.startedAtEpochMillis)
        assertEquals(1779254400000L, runtimeBody.host.systemTimeEpochMillis)
        assertEquals(55.0, runtimeBody.host.cpuUsagePercent)
        assertEquals(50.0, runtimeBody.host.memory.usagePercent)
        assertEquals(25.0, runtimeBody.host.storage.usagePercent)
        assertEquals(2, runtimeBody.recentPushRecords.size)
        assertEquals("直播", runtimeBody.recentPushRecords.first().typeLabel)
        assertEquals("已发送", runtimeBody.recentPushRecords.first().statusLabel)
        assertEquals("米哈游Official", runtimeBody.recentPushRecords.first().summary)
        assertEquals("bot.yml", configResponse.body<WebUiConfigFileDto>().sourceFile)
    }

    /**
     * 订阅写接口必须挂在受认证 API 下，并把业务校验失败映射成可读的 400 响应。
     */
    @Test
    fun `subscription mutation routes should require auth and expose validation failures`() = testApplication {
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

        val client = createWebUiClient()
        val auth = reloginForPhase3(authService, bootstrapPassword)
        val unauthenticated = client.post("/api/subscriptions") {
            contentType(ContentType.Application.Json)
            setBody(WebUiSubscriptionCreateRequestDto(type = "dynamic"))
        }
        val invalidCreateMissingConfirmation = client.post("/api/subscriptions") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Cookie, auth.cookieHeader())
            header("X-CSRF-Token", auth.csrfToken)
            setBody(WebUiSubscriptionCreateRequestDto(type = "bangumi", bangumiId = "md12345", targetGroup = "10001"))
        }
        val invalidDeleteMissingConfirmation = client.delete("/api/subscriptions/missing") {
            header(HttpHeaders.Cookie, auth.cookieHeader())
            header("X-CSRF-Token", auth.csrfToken)
        }
        val invalidCreate = client.post("/api/subscriptions") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Cookie, auth.cookieHeader())
            header("X-CSRF-Token", auth.csrfToken)
            setBody("""{"type":"bangumi","bangumiId":"av12345","targetGroup":"10001","confirmationPassword":"Better123!@"}""")
        }
        val invalidDelete = client.delete("/api/subscriptions/missing") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Cookie, auth.cookieHeader())
            header("X-CSRF-Token", auth.csrfToken)
            setBody(WebUiActionConfirmationRequestDto("Better123!@"))
        }

        assertEquals(HttpStatusCode.Unauthorized, unauthenticated.status)
        assertEquals(HttpStatusCode.Forbidden, invalidCreateMissingConfirmation.status)
        assertEquals(HttpStatusCode.BadRequest, invalidCreate.status)
        assertTrue(invalidCreate.body<WebUiSubscriptionMutationResultDto>().message.contains("ss、md 或 ep"))
        assertEquals(HttpStatusCode.Forbidden, invalidDeleteMissingConfirmation.status)
        assertEquals(HttpStatusCode.BadRequest, invalidDelete.status)
    }

    /**
     * 订阅配置编辑 API 应覆盖过滤器、模板、@全体和主题色四类入口，并保持认证边界一致。
     */
    @Test
    fun `subscription config editor routes should expose filter template atall and theme operations`() = testApplication {
        val authService = buildAuthService()
        val bootstrapPassword = authService.bootstrapCredentials().initialPassword!!
        val runtimeConfig = BiliConfig()
        BiliData.apply {
            dynamic = mutableMapOf(
                123L to SubData(
                    name = "Alice",
                    contacts = mutableSetOf("onebot11:group:10001"),
                    sourceRefs = mutableSetOf("direct:onebot11:group:10001"),
                ),
            )
            filter = mutableMapOf(
                "onebot11:group:10001" to mutableMapOf(
                    123L to DynamicFilter(
                        typeSelect = TypeFilter(),
                        regularSelect = RegularFilter(FilterMode.BLACK_LIST, mutableListOf("^old")),
                    ),
                ),
            )
            dynamicTemplatePolicyByScope = mutableMapOf(
                "onebot11:group:10001" to mutableMapOf(123L to TemplatePolicy(templates = mutableListOf("OneMsg"))),
            )
            dynamicColorByUid = mutableMapOf()
            atAll = mutableMapOf()
        }

        application {
            installWebUiModule(
                settings = WebUiConfig(enabled = true).toSettings(tempRoot.toFile()),
                authService = authService,
                runtimeFacade = buildRuntimeFacade(),
                configFacade = buildConfigFacade(),
                configWriteFacade = buildConfigWriteFacade(),
                logFacade = buildLogFacade(),
                actionFacade = buildActionFacade(),
                subscriptionManagementFacade = WebUiSubscriptionManagementFacade(
                    configProvider = { runtimeConfig },
                    saveConfigAction = { true },
                    saveDataAction = { true },
                ),
                auditService = WebUiAuditService(sink = {}),
            )
        }

        val auth = reloginForPhase3(authService, bootstrapPassword)
        val client = createWebUiClient()
        val filters = client.get("/api/subscriptions/dynamic%3A123/filters") {
            header(HttpHeaders.Cookie, auth.cookieHeader())
        }.body<WebUiSubscriptionFilterListDto>()
        val filterKey = filters.filters.first().key
        val filterMissingConfirmation = client.post("/api/subscriptions/dynamic%3A123/filters") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Cookie, auth.cookieHeader())
            header("X-CSRF-Token", auth.csrfToken)
            setBody(WebUiSubscriptionFilterSaveRequestDto(key = filterKey, kind = "regex", mode = "white", content = "^new"))
        }
        val templates = client.get("/api/subscriptions/dynamic%3A123/templates") {
            header(HttpHeaders.Cookie, auth.cookieHeader())
        }.body<WebUiSubscriptionTemplateListDto>()
        val templateMissingConfirmation = client.post("/api/subscriptions/dynamic%3A123/templates") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Cookie, auth.cookieHeader())
            header("X-CSRF-Token", auth.csrfToken)
            setBody(WebUiSubscriptionTemplateSaveRequestDto(type = "dynamic", name = "WebTpl", content = "{name}"))
        }
        val randomMissingConfirmation = client.post("/api/subscriptions/dynamic%3A123/templates/random") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Cookie, auth.cookieHeader())
            header("X-CSRF-Token", auth.csrfToken)
            setBody(WebUiSubscriptionTemplateRandomRequestDto(enabled = true))
        }
        val atAllMissingConfirmation = client.post("/api/subscriptions/dynamic%3A123/atall") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Cookie, auth.cookieHeader())
            header("X-CSRF-Token", auth.csrfToken)
            setBody(WebUiSubscriptionAtAllSaveRequestDto(type = "直播", targetGroups = listOf("onebot11:group:10001")))
        }
        val themeMissingConfirmation = client.post("/api/subscriptions/dynamic%3A123/theme") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Cookie, auth.cookieHeader())
            header("X-CSRF-Token", auth.csrfToken)
            setBody(WebUiSubscriptionThemeSaveRequestDto(color = "#AABBCC"))
        }
        val filterSaved = client.post("/api/subscriptions/dynamic%3A123/filters") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Cookie, auth.cookieHeader())
            header("X-CSRF-Token", auth.csrfToken)
            setBody("""{"key":"$filterKey","kind":"regex","mode":"white","content":"^new","targetGroups":["onebot11:group:10001"],"confirmationPassword":"Better123!@"}""")
        }
        val templateSaved = client.post("/api/subscriptions/dynamic%3A123/templates") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Cookie, auth.cookieHeader())
            header("X-CSRF-Token", auth.csrfToken)
            setBody("""{"type":"dynamic","name":"WebTpl","content":"{name}","targetGroups":["onebot11:group:10001"],"confirmationPassword":"Better123!@"}""")
        }
        val randomSaved = client.post("/api/subscriptions/dynamic%3A123/templates/random") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Cookie, auth.cookieHeader())
            header("X-CSRF-Token", auth.csrfToken)
            setBody("""{"enabled":true,"confirmationPassword":"Better123!@"}""")
        }
        val atAllSaved = client.post("/api/subscriptions/dynamic%3A123/atall") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Cookie, auth.cookieHeader())
            header("X-CSRF-Token", auth.csrfToken)
            setBody("""{"type":"直播","targetGroups":["onebot11:group:10001"],"confirmationPassword":"Better123!@"}""")
        }
        val themeSaved = client.post("/api/subscriptions/dynamic%3A123/theme") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Cookie, auth.cookieHeader())
            header("X-CSRF-Token", auth.csrfToken)
            setBody("""{"color":"#AABBCC","targetGroups":["onebot11:group:10001"],"confirmationPassword":"Better123!@"}""")
        }
        val theme = client.get("/api/subscriptions/dynamic%3A123/theme") {
            header(HttpHeaders.Cookie, auth.cookieHeader())
        }.body<WebUiSubscriptionThemeDto>()

        assertEquals(listOf("r0"), filters.filters.map { it.prefix })
        assertEquals(listOf("OneMsg"), templates.templates.map { it.name })
        assertEquals(HttpStatusCode.Forbidden, filterMissingConfirmation.status)
        assertEquals(HttpStatusCode.OK, filterSaved.status)
        assertEquals(HttpStatusCode.Forbidden, templateMissingConfirmation.status)
        assertEquals(HttpStatusCode.OK, templateSaved.status)
        assertEquals(HttpStatusCode.Forbidden, randomMissingConfirmation.status)
        assertEquals(HttpStatusCode.OK, randomSaved.status)
        assertEquals(HttpStatusCode.Forbidden, atAllMissingConfirmation.status)
        assertEquals(HttpStatusCode.OK, atAllSaved.status)
        assertEquals(HttpStatusCode.Forbidden, themeMissingConfirmation.status)
        assertEquals(HttpStatusCode.OK, themeSaved.status)
        assertEquals("#AABBCC", theme.color)
        assertEquals(listOf("onebot11:group:10001"), theme.targetGroups)
    }

    /**
     * 认证失败、改密失败和高风险确认拒绝都应落审计，便于本地排查权限链路。
     */
    @Test
    fun `auth and confirmation denial paths should emit audit records`() = testApplication {
        val records = mutableListOf<WebUiAuditRecord>()
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
                auditService = WebUiAuditService(sink = { record -> records += record }),
            )
        }

        val client = createWebUiClient()
        val failedLogin = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(WebUiLoginRequestDto(password = "wrong-password"))
        }
        val firstLogin = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(WebUiLoginRequestDto(password = bootstrapPassword))
        }
        val firstAuth = extractLoginCookies(firstLogin)
        val failedChange = client.post("/api/auth/change-password") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Cookie, firstAuth.cookieHeader())
            header("X-CSRF-Token", firstAuth.csrfToken)
            setBody(
                WebUiChangePasswordRequestDto(
                    currentPassword = "wrong-password",
                    newPassword = "Better123!@",
                ),
            )
        }
        val changed = client.post("/api/auth/change-password") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Cookie, firstAuth.cookieHeader())
            header("X-CSRF-Token", firstAuth.csrfToken)
            setBody(
                WebUiChangePasswordRequestDto(
                    currentPassword = bootstrapPassword,
                    newPassword = "Better123!@",
                ),
            )
        }
        val relogin = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(WebUiLoginRequestDto(password = "Better123!@"))
        }
        val reloginAuth = extractLoginCookies(relogin)
        val biliConfigSnapshot = client.get("/api/config/bili-config") {
            header(HttpHeaders.Cookie, reloginAuth.cookieHeader())
        }.body<WebUiConfigFileDto>()
        val deniedSave = client.post("/api/config/bili-config") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Cookie, reloginAuth.cookieHeader())
            header("X-CSRF-Token", reloginAuth.csrfToken)
            setBody(
                WebUiBiliConfigWriteRequestDto(
                    snapshotToken = biliConfigSnapshot.snapshotToken,
                    adminContact = "onebot11:private:2",
                    cookie = "",
                    baiduAppId = "new-app-id",
                    baiduSecurityKey = "",
                    debugMode = true,
                    confirmationPassword = "",
                ),
            )
        }

        assertEquals(HttpStatusCode.Unauthorized, failedLogin.status)
        assertEquals(HttpStatusCode.BadRequest, failedChange.status)
        assertEquals(HttpStatusCode.OK, changed.status)
        assertEquals(HttpStatusCode.Forbidden, deniedSave.status)
        assertTrue(records.isNotEmpty())
        assertTrue(records.any { it.target == "login" })
        assertTrue(records.any { it.target == "change-password" })
        assertTrue(records.any { it.target == "high-risk-confirmation" })
    }

    /**
     * 登出路由必须同时让当前 token 失效并清理 cookie，确保浏览器随后回到登录边界。
     */
    @Test
    fun `logout route should revoke current session and expire auth cookie`() = testApplication {
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

        val auth = reloginForPhase3(authService, bootstrapPassword)
        val client = createWebUiClient()
        val logout = client.post("/api/auth/logout") {
            header(HttpHeaders.Cookie, auth.cookieHeader())
            header("X-CSRF-Token", auth.csrfToken)
        }
        val runtimeAfterLogout = client.get("/api/runtime/summary") {
            header(HttpHeaders.Cookie, auth.cookieHeader())
        }
        val sessionAfterLogout = client.get("/api/auth/session") {
            header(HttpHeaders.Cookie, auth.cookieHeader())
        }.body<WebUiSessionDto>()

        assertEquals(HttpStatusCode.OK, logout.status)
        assertTrue(logout.headers.getAll(HttpHeaders.SetCookie).orEmpty().any { cookie ->
            cookie.contains("${top.bilibili.webui.routes.WebUiSessionCookieName}=") && cookie.contains("Max-Age=0")
        })
        assertEquals(HttpStatusCode.Unauthorized, runtimeAfterLogout.status)
        assertEquals(false, sessionAfterLogout.authenticated)
    }

    @Test
    fun `config save routes should stay file scoped reject stale snapshots and require stronger confirmation`() = testApplication {
        val authService = buildAuthService()
        val bootstrapPassword = authService.bootstrapCredentials().initialPassword!!
        var currentBiliConfig = BiliConfig(
            adminContact = "onebot11:private:1",
            accountConfig = BiliAccountConfig(cookie = "raw-cookie"),
        )
        var currentBiliData = BiliData.apply {
            dataVersion = 4
            dynamic = mutableMapOf()
            group = mutableMapOf()
            linkParseBlacklistContacts = mutableSetOf("onebot11:private:1")
        }
        var currentBotConfig = BotConfig(
            platform = PlatformConfig(
                type = top.bilibili.connector.PlatformType.ONEBOT11,
                adapter = "onebot11",
                onebot11 = NapCatConfig(host = "127.0.0.1", port = 3001, token = "raw-token"),
            ),
        )
        var savedConfig: BiliConfig? = null
        var savedBlacklist: Set<String>? = null
        var savedBotConfig: BotConfig? = null
        val configFacade = WebUiConfigFacade(
            biliConfigProvider = { currentBiliConfig },
            biliDataProvider = { currentBiliData },
            botConfigProvider = { currentBotConfig },
        )
        val configWriteFacade = WebUiConfigWriteFacade(
            configFacade = configFacade,
            biliConfigProvider = { currentBiliConfig },
            botConfigProvider = { currentBotConfig },
            saveBiliConfigAction = { updated ->
                savedConfig = updated
                currentBiliConfig = updated
                true
            },
            saveBiliDataAction = { contacts ->
                savedBlacklist = contacts
                currentBiliData.linkParseBlacklistContacts = contacts.toMutableSet()
                true
            },
            saveBotConfigAction = { updated ->
                savedBotConfig = updated
                currentBotConfig = updated
                true
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

        val auth = reloginForPhase3(authService, bootstrapPassword)
        val currentSnapshot = createWebUiClient().get("/api/config/bili-config") {
            header(HttpHeaders.Cookie, auth.cookieHeader())
        }.body<WebUiConfigFileDto>()
        val dataSnapshot = createWebUiClient().get("/api/config/bili-data") {
            header(HttpHeaders.Cookie, auth.cookieHeader())
        }.body<WebUiConfigFileDto>()
        val botSnapshot = createWebUiClient().get("/api/config/bot") {
            header(HttpHeaders.Cookie, auth.cookieHeader())
        }.body<WebUiConfigFileDto>()
        val missingConfirmation = createWebUiClient().post("/api/config/bili-config") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Cookie, auth.cookieHeader())
            header("X-CSRF-Token", auth.csrfToken)
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
        // 单文件兼容路由逐个等待终态，避免生产 debounce 窗口让 smoke test 读到后续 job 的排队态。
        val staleSnapshot = createWebUiClient().post("/api/config/bili-config") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Cookie, auth.cookieHeader())
            header("X-CSRF-Token", auth.csrfToken)
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
        val staleJob = pollConfigSaveJob(staleSnapshot.body<WebUiConfigHotReloadJobDto>().jobId, auth.cookieHeader())
        val dataSaved = createWebUiClient().post("/api/config/bili-data") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Cookie, auth.cookieHeader())
            header("X-CSRF-Token", auth.csrfToken)
            setBody(
                top.bilibili.webui.model.WebUiBiliDataWriteRequestDto(
                    snapshotToken = dataSnapshot.snapshotToken,
                    linkParseBlacklistContacts = listOf("onebot11:private:2", "onebot11:group:3"),
                    confirmationPassword = "Better123!@",
                ),
            )
        }
        val dataJob = pollConfigSaveJob(dataSaved.body<WebUiConfigHotReloadJobDto>().jobId, auth.cookieHeader())
        val botStaleSnapshot = createWebUiClient().post("/api/config/bot") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Cookie, auth.cookieHeader())
            header("X-CSRF-Token", auth.csrfToken)
            setBody(
                top.bilibili.webui.model.WebUiBotConfigWriteRequestDto(
                    snapshotToken = "stale-token",
                    platformType = "ONEBOT11",
                    adapter = "onebot11",
                    oneBot11Host = "127.0.0.1",
                    oneBot11Port = 3001,
                    oneBot11Token = "",
                    confirmationPassword = "Better123!@",
                ),
            )
        }
        val botStaleJob = pollConfigSaveJob(botStaleSnapshot.body<WebUiConfigHotReloadJobDto>().jobId, auth.cookieHeader())
        val botSaved = createWebUiClient().post("/api/config/bot") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Cookie, auth.cookieHeader())
            header("X-CSRF-Token", auth.csrfToken)
            setBody(
                top.bilibili.webui.model.WebUiBotConfigWriteRequestDto(
                    snapshotToken = botSnapshot.snapshotToken,
                    platformType = "ONEBOT11",
                    adapter = "onebot11",
                    oneBot11Host = "10.0.0.2",
                    oneBot11Port = 3100,
                    oneBot11Token = "",
                    confirmationPassword = "Better123!@",
                ),
            )
        }
        val botJob = pollConfigSaveJob(botSaved.body<WebUiConfigHotReloadJobDto>().jobId, auth.cookieHeader())
        val saved = createWebUiClient().post("/api/config/bili-config") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Cookie, auth.cookieHeader())
            header("X-CSRF-Token", auth.csrfToken)
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
        val savedJob = pollConfigSaveJob(saved.body<WebUiConfigHotReloadJobDto>().jobId, auth.cookieHeader())

        assertEquals(HttpStatusCode.Forbidden, missingConfirmation.status)
        assertEquals(HttpStatusCode.Accepted, staleSnapshot.status)
        assertEquals(WebUiConfigHotReloadPhase.FAILED, staleJob.phase)
        assertTrue(staleJob.outcomes.any { outcome -> !outcome.result.success })
        assertEquals(HttpStatusCode.Accepted, dataSaved.status)
        assertEquals(HttpStatusCode.Accepted, botStaleSnapshot.status)
        assertEquals(WebUiConfigHotReloadPhase.FAILED, botStaleJob.phase)
        assertTrue(botStaleJob.outcomes.any { outcome -> !outcome.result.success })
        assertEquals(HttpStatusCode.Accepted, botSaved.status)
        assertEquals(HttpStatusCode.Accepted, saved.status)
        assertEquals(WebUiConfigHotReloadPhase.APPLIED, dataJob.phase)
        assertEquals(WebUiConfigHotReloadPhase.APPLIED, botJob.phase)
        assertEquals(WebUiConfigHotReloadPhase.APPLIED, savedJob.phase)
        assertEquals("onebot11:private:2", savedConfig?.adminContact)
        assertEquals("raw-cookie", savedConfig?.accountConfig?.cookie)
        assertEquals(setOf("onebot11:private:2", "onebot11:group:3"), savedBlacklist)
        assertEquals("10.0.0.2", savedBotConfig?.selectedOneBot11Config()?.host)
        assertTrue(savedJob.outcomes.any { outcome -> outcome.result.persisted })
    }

    @Test
    fun `config batch save route should enqueue hot reload job and expose job polling`() = testApplication {
        val authService = buildAuthService()
        val bootstrapPassword = authService.bootstrapCredentials().initialPassword!!
        var currentConfig = BiliConfig(adminContact = "onebot11:private:1")
        val records = mutableListOf<WebUiAuditRecord>()
        val configFacade = WebUiConfigFacade(
            biliConfigProvider = { currentConfig },
            botConfigProvider = { BotConfig() },
        )
        val configWriteFacade = WebUiConfigWriteFacade(
            configFacade = configFacade,
            biliConfigProvider = { currentConfig },
            saveBiliConfigAction = { updated ->
                currentConfig = updated
                true
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
                auditService = WebUiAuditService(sink = { record -> records += record }),
            )
        }

        val auth = reloginForPhase3(authService, bootstrapPassword)
        val currentSnapshot = createWebUiClient().get("/api/config/bili-config") {
            header(HttpHeaders.Cookie, auth.cookieHeader())
        }.body<WebUiConfigFileDto>()
        val accepted = createWebUiClient().post("/api/config/save-batch") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Cookie, auth.cookieHeader())
            header("X-CSRF-Token", auth.csrfToken)
            setBody(
                WebUiConfigBatchSaveRequestDto(
                    biliConfig = WebUiBiliConfigWriteRequestDto(
                        snapshotToken = currentSnapshot.snapshotToken,
                        adminContact = "onebot11:private:2",
                        confirmationPassword = "Better123!@",
                    ),
                ),
            )
        }
        val job = accepted.body<WebUiConfigHotReloadJobDto>()
        val polledJob = pollConfigSaveJob(job.jobId, auth.cookieHeader())

        assertEquals(HttpStatusCode.Accepted, accepted.status)
        assertTrue(job.phase in setOf(WebUiConfigHotReloadPhase.QUEUED, WebUiConfigHotReloadPhase.APPLIED))
        assertEquals(job.jobId, polledJob.jobId)
        assertTrue(
            records.any { record ->
                record.eventType == "config-save" &&
                    record.target == "BiliConfig.yml" &&
                    record.success
            },
            "records=$records polledJob=$polledJob",
        )
        assertFalse(records.any { record -> record.detailSummary.contains("Better123!@") })
    }

    @Test
    fun `log routes and action routes should expose fixed sources and keep action semantics distinct`() = testApplication {
        val authService = buildAuthService()
        val bootstrapPassword = authService.bootstrapCredentials().initialPassword!!
        val logFile = tempRoot.resolve("bilibili-bot.log")
        Files.writeString(logFile, "line-1\nline-2\nline-3\n")
        var reloadCalls = 0
        var shutdownCalls = 0
        val records = mutableListOf<WebUiAuditRecord>()

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
                auditService = WebUiAuditService(sink = { record -> records += record }),
            )
        }

        val auth = reloginForPhase3(authService, bootstrapPassword)
        val sourceList = createWebUiClient().get("/api/logs/sources") {
            header(HttpHeaders.Cookie, auth.cookieHeader())
        }
        val logWindow = createWebUiClient().get("/api/logs/main?tail=20") {
            header(HttpHeaders.Cookie, auth.cookieHeader())
        }
        val exportedLog = createWebUiClient().get("/api/logs/main/export?tail=20") {
            header(HttpHeaders.Cookie, auth.cookieHeader())
        }
        val clearMissingConfirmation = createWebUiClient().post("/api/logs/main/clear") {
            header(HttpHeaders.Cookie, auth.cookieHeader())
        }
        val clearedLog = createWebUiClient().post("/api/logs/main/clear") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Cookie, auth.cookieHeader())
            header("X-CSRF-Token", auth.csrfToken)
            setBody(WebUiActionConfirmationRequestDto("Better123!@"))
        }
        val logWindowAfterClear = createWebUiClient().get("/api/logs/main?tail=20") {
            header(HttpHeaders.Cookie, auth.cookieHeader())
        }
        val reload = createWebUiClient().post("/api/actions/reload-config") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Cookie, auth.cookieHeader())
            header("X-CSRF-Token", auth.csrfToken)
            setBody(WebUiActionConfirmationRequestDto("Better123!@"))
        }
        val restart = createWebUiClient().post("/api/actions/request-restart") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Cookie, auth.cookieHeader())
            header("X-CSRF-Token", auth.csrfToken)
            setBody(WebUiActionConfirmationRequestDto("Better123!@"))
        }

        assertEquals(HttpStatusCode.OK, sourceList.status)
        assertEquals(HttpStatusCode.OK, logWindow.status)
        assertEquals(HttpStatusCode.OK, exportedLog.status)
        assertEquals(HttpStatusCode.Forbidden, clearMissingConfirmation.status)
        assertEquals(HttpStatusCode.OK, clearedLog.status)
        assertEquals(HttpStatusCode.OK, logWindowAfterClear.status)
        assertEquals(HttpStatusCode.OK, reload.status)
        assertEquals(HttpStatusCode.OK, restart.status)
        assertEquals(listOf("main"), sourceList.body<WebUiLogSourceListDto>().sources.map { source -> source.id })
        assertEquals(2, logWindow.body<WebUiLogWindowDto>().lineCount)
        assertEquals(listOf(2), logWindow.body<WebUiLogWindowDto>().availableTailLines)
        assertEquals(false, logWindow.body<WebUiLogWindowDto>().sourceMissing)
        assertTrue(exportedLog.bodyAsText().contains("line-2"))
        assertEquals(0, logWindowAfterClear.body<WebUiLogWindowDto>().lineCount)
        assertEquals("reload-config", reload.body<WebUiActionResultDto>().action)
        assertEquals(top.bilibili.webui.model.WebUiActionOutcome.RELOAD_CONFIG_REQUESTED, reload.body<WebUiActionResultDto>().outcome)
        assertEquals("request-restart", restart.body<WebUiActionResultDto>().action)
        assertEquals(top.bilibili.webui.model.WebUiActionOutcome.RESTART_REQUESTED_MANUAL_FALLBACK, restart.body<WebUiActionResultDto>().outcome)
        assertEquals(1, reloadCalls)
        assertEquals(1, shutdownCalls)
        assertTrue(records.any { it.eventType == "risky-action" && it.target == "clear-log:main" && it.success })
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
            appVersionProvider = { "v-test" },
            subscriptionCountProvider = { 5 },
            dynamicSubscriptionCountProvider = { 4 },
            bangumiSubscriptionCountProvider = { 1 },
            groupCountProvider = { 2 },
            accountStatusProvider = {
                WebUiBiliAccountStatusDto(
                    loggedIn = true,
                    uid = 2233L,
                    cookieConfigured = true,
                )
            },
            platformRuntimeStatusProvider = {
                PlatformRuntimeStatus(
                    connected = true,
                    reconnectAttempts = 2,
                )
            },
            platformObservabilityProvider = {
                PlatformObservabilitySnapshot(
                    clients = listOf(
                        PlatformHttpClientSnapshot(
                            adapterName = "onebot11",
                            transportName = "napcat",
                            webSocketSessionActive = true,
                        ),
                    ),
                )
            },
            pushStatisticsProvider = {
                DailyPushStatsSnapshot(
                    date = "2026-05-20",
                    total = 6,
                    dynamic = 4,
                    live = 1,
                    liveClose = 1,
                    failed = 0,
                    lastSuccessAtEpochMillis = null,
                    recentRecords = listOf(
                        PushDeliveryRecordSnapshot(
                            timestampEpochMillis = 1779254700000L,
                            type = "LIVE",
                            success = true,
                            summary = "米哈游Official",
                            target = "onebot11:group:10001",
                        ),
                        PushDeliveryRecordSnapshot(
                            timestampEpochMillis = 1779254600000L,
                            type = "DYNAMIC",
                            success = true,
                            summary = "LexBurner",
                            target = "onebot11:group:10002",
                        ),
                    ),
                )
            },
            hostStatusProvider = {
                WebUiHostRuntimeStatusDto(
                    startedAtEpochMillis = 1779250800000L,
                    systemTimeEpochMillis = 1779254400000L,
                    systemLoadAverage = 0.66,
                    cpuUsagePercent = 55.0,
                    memory = WebUiResourceUsageDto(
                        usedBytes = 512L,
                        totalBytes = 1024L,
                        usagePercent = 50.0,
                    ),
                    storage = WebUiResourceUsageDto(
                        usedBytes = 256L,
                        totalBytes = 1024L,
                        usagePercent = 25.0,
                    ),
                )
            },
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
    ): LoginCookies {
        val client = createWebUiClient()
        val initialLogin = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(WebUiLoginRequestDto(password = bootstrapPassword))
        }
        val initialAuth = extractLoginCookies(initialLogin)
        client.post("/api/auth/change-password") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Cookie, initialAuth.cookieHeader())
            header("X-CSRF-Token", initialAuth.csrfToken)
            setBody(
                WebUiChangePasswordRequestDto(
                    currentPassword = bootstrapPassword,
                    newPassword = "Better123!@",
                ),
            )
        }
        val relogin = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(WebUiLoginRequestDto(password = "Better123!@"))
        }
        return extractLoginCookies(relogin)
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

    /**
     * 热重载 job 由后台 worker 完成，路由测试短轮询终态以避免和 worker 调度竞速。
     */
    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.pollConfigSaveJob(
        jobId: String,
        cookieHeader: String,
    ): WebUiConfigHotReloadJobDto {
        repeat(20) {
            val job = createWebUiClient().get("/api/config/save-jobs/$jobId") {
                header(HttpHeaders.Cookie, cookieHeader)
            }.body<WebUiConfigHotReloadJobDto>()
            if (job.phase == WebUiConfigHotReloadPhase.APPLIED || job.phase == WebUiConfigHotReloadPhase.FAILED) {
                return job
            }
            kotlinx.coroutines.delay(25)
        }
        return createWebUiClient().get("/api/config/save-jobs/$jobId") {
            header(HttpHeaders.Cookie, cookieHeader)
        }.body()
    }

    /**
     * 登录响应只通过 Set-Cookie 下发会话，测试用 cookie 结构集中封装便于复用。
     */
    private fun extractLoginCookies(response: io.ktor.client.statement.HttpResponse): LoginCookies {
        val setCookies = response.headers.getAll(HttpHeaders.SetCookie).orEmpty()
        val sessionCookie = setCookies.first { it.startsWith("${top.bilibili.webui.routes.WebUiSessionCookieName}=") }.substringBefore(";")
        val csrfCookie = setCookies.first { it.startsWith("${top.bilibili.webui.routes.WebUiCsrfCookieName}=") }.substringBefore(";")
        return LoginCookies(
            sessionCookie = sessionCookie,
            csrfToken = csrfCookie.substringAfter('='),
        )
    }

    /**
     * 认证请求复用 session 和 CSRF cookie，避免测试里重复拼接 header 片段。
     */
    private data class LoginCookies(
        val sessionCookie: String,
        val csrfToken: String,
    ) {
        fun cookieHeader(): String = "$sessionCookie; ${top.bilibili.webui.routes.WebUiCsrfCookieName}=$csrfToken"
    }
}
