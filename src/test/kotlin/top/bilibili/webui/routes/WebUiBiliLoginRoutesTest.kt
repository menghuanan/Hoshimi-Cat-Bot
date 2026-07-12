package top.bilibili.webui.routes

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import top.bilibili.service.QrLoginCancelResult
import top.bilibili.service.QrLoginPhase
import top.bilibili.service.QrLoginSessionSnapshot
import top.bilibili.service.QrLoginStartResult
import top.bilibili.webui.auth.WebUiAuthService
import top.bilibili.webui.auth.WebUiCredentialStore
import top.bilibili.webui.auth.WebUiTokenService
import top.bilibili.webui.model.WebUiBiliLoginConflictDto
import top.bilibili.webui.model.WebUiBiliLoginSessionDto
import top.bilibili.webui.service.WebUiAuditRecord
import top.bilibili.webui.service.WebUiAuditService
import top.bilibili.webui.service.WebUiBiliLoginFacade

class WebUiBiliLoginRoutesTest {
    /** 创建、轮询和取消必须经过 session、unsafe 请求的 CSRF 校验及 no-store 响应边界。 */
    @Test
    fun `bili login routes should enforce guards and expose sanitized session lifecycle`() = testApplication {
        val authService = buildAuthenticatedService()
        val auth = authenticate(authService)
        val records = mutableListOf<WebUiAuditRecord>()
        var cancelled = false
        val baseSnapshot = QrLoginSessionSnapshot("session-1", QrLoginPhase.WAITING_FOR_SCAN, 181_000L, "等待扫码")
        val facade = WebUiBiliLoginFacade(
            startLogin = { QrLoginStartResult.Started(baseSnapshot, byteArrayOf(1, 2, 3), "https://secret.example/qr") },
            readSnapshot = {
                if (cancelled) baseSnapshot.copy(phase = QrLoginPhase.CANCELLED, message = "登录已取消") else baseSnapshot
            },
            cancelLogin = { cancelled = true; QrLoginCancelResult.CANCELLED },
        )
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            routing {
                registerWebUiBiliLoginRoutes(authService, facade, WebUiAuditService(sink = records::add))
            }
        }
        val apiClient = createClient {
            install(ClientContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        val unauthenticated = apiClient.post("/api/bili-login/sessions") {
            contentType(ContentType.Application.Json)
        }
        val missingCsrf = apiClient.post("/api/bili-login/sessions") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Cookie, auth.cookieHeader())
        }
        val created = apiClient.post("/api/bili-login/sessions") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Cookie, auth.cookieHeader())
            header(WebUiCsrfHeaderName, auth.csrfToken)
        }
        val polled = apiClient.get("/api/bili-login/sessions/session-1") {
            header(HttpHeaders.Cookie, auth.cookieHeader())
        }
        val cancelledResponse = apiClient.delete("/api/bili-login/sessions/session-1") {
            header(HttpHeaders.Cookie, auth.cookieHeader())
            header(WebUiCsrfHeaderName, auth.csrfToken)
        }

        assertEquals(HttpStatusCode.Unauthorized, unauthenticated.status)
        assertEquals(HttpStatusCode.Forbidden, missingCsrf.status)
        assertEquals(HttpStatusCode.Created, created.status)
        assertEquals("no-store", created.headers[HttpHeaders.CacheControl])
        assertEquals("AQID", created.body<WebUiBiliLoginSessionDto>().qrImageBase64)
        assertEquals(HttpStatusCode.OK, polled.status)
        assertEquals(null, polled.body<WebUiBiliLoginSessionDto>().qrImageBase64)
        assertEquals(HttpStatusCode.OK, cancelledResponse.status)
        assertTrue(cancelled)
        assertTrue(records.any { it.target == "bili-login:create" && it.success })
        assertTrue(records.any { it.target == "bili-login:cancel" && it.success })
        assertFalse(records.any { it.detailSummary.contains("secret.example") || it.detailSummary.contains("Better123!@") })
    }

    /** 活动会话冲突只返回剩余秒数，未知会话的读取和取消统一返回 404。 */
    @Test
    fun `bili login routes should map conflict and missing sessions`() = testApplication {
        val authService = buildAuthenticatedService()
        val auth = authenticate(authService)
        val facade = WebUiBiliLoginFacade(
            startLogin = { QrLoginStartResult.Conflict(QrLoginPhase.WAITING_FOR_SCAN, 42L) },
            readSnapshot = { null },
            cancelLogin = { QrLoginCancelResult.NOT_FOUND },
        )
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            routing {
                registerWebUiBiliLoginRoutes(authService, facade, WebUiAuditService())
            }
        }
        val apiClient = createClient {
            install(ClientContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        val conflict = apiClient.post("/api/bili-login/sessions") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Cookie, auth.cookieHeader())
            header(WebUiCsrfHeaderName, auth.csrfToken)
        }
        val missingRead = apiClient.get("/api/bili-login/sessions/missing") {
            header(HttpHeaders.Cookie, auth.cookieHeader())
        }
        val missingCancel = apiClient.delete("/api/bili-login/sessions/missing") {
            header(HttpHeaders.Cookie, auth.cookieHeader())
            header(WebUiCsrfHeaderName, auth.csrfToken)
        }

        assertEquals(HttpStatusCode.Conflict, conflict.status)
        assertEquals(top.bilibili.webui.model.WebUiBiliLoginPhase.WAITING_FOR_SCAN, conflict.body<WebUiBiliLoginConflictDto>().phase)
        assertEquals(42L, conflict.body<WebUiBiliLoginConflictDto>().remainingSeconds)
        assertTrue(conflict.body<WebUiBiliLoginConflictDto>().message.contains("42"))
        assertEquals(HttpStatusCode.NotFound, missingRead.status)
        assertEquals(HttpStatusCode.NotFound, missingCancel.status)
    }

    /** 提交态创建冲突不得承诺 1 秒后可重试，响应应给出 phase 且省略 retryAfter。 */
    @Test
    fun `bili login route should expose committing conflict without retry seconds`() = testApplication {
        val authService = buildAuthenticatedService()
        val auth = authenticate(authService)
        val facade = WebUiBiliLoginFacade(
            startLogin = { QrLoginStartResult.Conflict(QrLoginPhase.COMMITTING, null) },
            readSnapshot = { null },
            cancelLogin = { QrLoginCancelResult.COMMITTING },
        )
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            routing {
                registerWebUiBiliLoginRoutes(authService, facade, WebUiAuditService())
            }
        }
        val apiClient = createClient {
            install(ClientContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        val conflict = apiClient.post("/api/bili-login/sessions") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Cookie, auth.cookieHeader())
            header(WebUiCsrfHeaderName, auth.csrfToken)
        }
        val body = conflict.body<WebUiBiliLoginConflictDto>()

        assertEquals(HttpStatusCode.Conflict, conflict.status)
        assertEquals(top.bilibili.webui.model.WebUiBiliLoginPhase.COMMITTING, body.phase)
        assertEquals(null, body.remainingSeconds)
        assertFalse(body.message.contains("1 秒"))
    }

    /** 测试认证服务先完成强制改密，保证保护路由只验证本功能所需 guard。 */
    private suspend fun buildAuthenticatedService(): WebUiAuthService {
        val root = Files.createTempDirectory("webui-bili-login-routes")
        val service = WebUiAuthService(
            credentialStore = WebUiCredentialStore(root.resolve("credentials.json").toFile()),
            tokenService = WebUiTokenService(300L),
        )
        val initialPassword = service.bootstrapCredentials().initialPassword!!
        check(service.changePassword(initialPassword, "Better123!@").success)
        return service
    }

    /** 每个用例使用独立 cookie/csrf 对，避免认证状态跨测试共享。 */
    private suspend fun authenticate(service: WebUiAuthService): TestAuth {
        val login = service.login("Better123!@")
        check(login.success)
        return TestAuth(login.token!!, login.csrfToken!!)
    }

    /** 测试请求按生产双 Cookie 约定组装认证头。 */
    private data class TestAuth(val token: String, val csrfToken: String) {
        fun cookieHeader(): String {
            return "$WebUiSessionCookieName=$token; $WebUiCsrfCookieName=$csrfToken"
        }
    }
}
