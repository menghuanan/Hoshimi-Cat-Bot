package top.bilibili.webui.routes

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.bilibili.webui.auth.WebUiAuthService
import top.bilibili.webui.auth.WebUiCredentialStore
import top.bilibili.webui.auth.WebUiTokenService
import top.bilibili.webui.model.WebUiAuthResponseDto
import top.bilibili.webui.model.WebUiChangePasswordRequestDto
import top.bilibili.webui.model.WebUiLoginRequestDto
import top.bilibili.webui.model.WebUiSessionDto
import top.bilibili.webui.service.WebUiAuditRecord
import top.bilibili.webui.service.WebUiAuditService

class WebUiAuthRoutesTest {
    private val tempRoot = Files.createTempDirectory("webui-auth-routes")

    @AfterTest
    fun cleanup() {
        tempRoot.toFile().deleteRecursively()
    }

    /**
     * 登录成功只通过 Cookie 下发会话材料，JSON 响应不得继续暴露 bearer token 字段。
     */
    @Test
    fun `login should set session and csrf cookies without returning bearer token`() = testApplication {
        val authService = buildAuthService()
        val bootstrapPassword = authService.bootstrapCredentials().initialPassword!!
        installAuthOnlyApplication(authService)

        val login = createWebUiClient().post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(WebUiLoginRequestDto(password = bootstrapPassword))
        }
        val body = login.bodyAsText()

        assertEquals(HttpStatusCode.OK, login.status)
        assertEquals("no-store", login.headers[HttpHeaders.CacheControl])
        assertFalse(body.contains("token"))
        assertNotNull(login.cookieValue("hoshimi_cat_bot_webui_session"))
        assertNotNull(login.cookieValue("hoshimi_cat_bot_webui_csrf"))
        assertTrue(login.setCookieFor("hoshimi_cat_bot_webui_session").contains("HttpOnly"))
        assertFalse(login.setCookieFor("hoshimi_cat_bot_webui_csrf").contains("HttpOnly"))
    }

    /**
     * route guard 只信任 Cookie 会话，Bearer header 即使携带有效 token 也不能通过认证。
     */
    @Test
    fun `route guard should reject bearer token when session cookie is absent`() = testApplication {
        val authService = buildAuthService()
        val bootstrapPassword = authService.bootstrapCredentials().initialPassword!!
        val bearerToken = authService.login(bootstrapPassword).token!!
        installAuthOnlyApplication(authService)

        val protected = createWebUiClient().get("/api/protected") {
            header(HttpHeaders.Authorization, "Bearer $bearerToken")
        }

        assertEquals(HttpStatusCode.Unauthorized, protected.status)
        assertEquals("no-store", protected.headers[HttpHeaders.CacheControl])
    }

    /**
     * 已认证写请求必须同时携带会话 Cookie、CSRF Cookie 和匹配的 X-CSRF-Token 头。
     */
    @Test
    fun `unsafe authenticated routes should require matching csrf cookie and header`() = testApplication {
        val authService = buildAuthService()
        val bootstrapPassword = authService.bootstrapCredentials().initialPassword!!
        installAuthOnlyApplication(authService)
        // 先把初始强制改密状态清掉，再单独验证 CSRF 双提交对 unsafe verb 的约束。
        authService.changePassword(
            currentPassword = bootstrapPassword,
            newPassword = "Better123!@",
        )
        val login = loginAndExtractCookies("Better123!@")
        val cookieHeader = login.cookieHeader()

        val missingHeader = createWebUiClient().post("/api/protected-write") {
            header(HttpHeaders.Cookie, cookieHeader)
        }
        val mismatchHeader = createWebUiClient().post("/api/protected-write") {
            header(HttpHeaders.Cookie, cookieHeader)
            header("X-CSRF-Token", "mismatch")
        }
        val accepted = createWebUiClient().post("/api/protected-write") {
            header(HttpHeaders.Cookie, cookieHeader)
            header("X-CSRF-Token", login.csrfToken)
        }

        assertEquals(HttpStatusCode.Forbidden, missingHeader.status)
        assertEquals(HttpStatusCode.Forbidden, mismatchHeader.status)
        assertEquals(HttpStatusCode.OK, accepted.status)
    }

    /**
     * 改密和登出都应走 Cookie 会话，并在成功后清理 session 与 CSRF Cookie。
     */
    @Test
    fun `change password and logout should clear auth cookies after cookie session validation`() = testApplication {
        val authService = buildAuthService()
        val bootstrapPassword = authService.bootstrapCredentials().initialPassword!!
        installAuthOnlyApplication(authService)
        val login = loginAndExtractCookies(bootstrapPassword)
        val cookieHeader = login.cookieHeader()
        val changed = createWebUiClient().post("/api/auth/change-password") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Cookie, cookieHeader)
            header("X-CSRF-Token", login.csrfToken)
            setBody(
                WebUiChangePasswordRequestDto(
                    currentPassword = bootstrapPassword,
                    newPassword = "Better123!@",
                ),
            )
        }

        val relogin = loginAndExtractCookies("Better123!@")
        val logout = createWebUiClient().post("/api/auth/logout") {
            header(HttpHeaders.Cookie, relogin.cookieHeader())
            header("X-CSRF-Token", relogin.csrfToken)
        }
        val sessionAfterLogout = createWebUiClient().get("/api/auth/session") {
            header(HttpHeaders.Cookie, relogin.cookieHeader())
        }.body<WebUiSessionDto>()

        assertEquals(HttpStatusCode.OK, changed.status)
        assertCookieCleared(changed, "hoshimi_cat_bot_webui_session")
        assertCookieCleared(changed, "hoshimi_cat_bot_webui_csrf")
        assertEquals(HttpStatusCode.OK, logout.status)
        assertCookieCleared(logout, "hoshimi_cat_bot_webui_session")
        assertCookieCleared(logout, "hoshimi_cat_bot_webui_csrf")
        assertFalse(sessionAfterLogout.authenticated)
    }

    /**
     * 登录失败审计保留来源和失败计数，不得把 password 或 token 写进 detailSummary。
     */
    @Test
    fun `failed login audit should include request source without leaking secrets`() = testApplication {
        val records = mutableListOf<WebUiAuditRecord>()
        val authService = buildAuthService()
        authService.bootstrapCredentials()
        installAuthOnlyApplication(authService, WebUiAuditService(sink = { record -> records += record }))

        val failed = createWebUiClient().post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.UserAgent, "Mozilla/5.0 SecretTest")
            header("X-Forwarded-For", "203.0.113.9")
            setBody(WebUiLoginRequestDto(password = "wrong-password"))
        }.body<WebUiAuthResponseDto>()
        val audit = records.single { record -> record.target == "login" && !record.success }

        assertFalse(failed.success)
        assertEquals("invalid credentials", failed.message)
        assertTrue(audit.detailSummary.contains("sourceIp=203.0.113.9"))
        assertTrue(audit.detailSummary.contains("failureCount=1"))
        assertFalse(audit.detailSummary.contains("wrong-password"))
        assertFalse(audit.detailSummary.contains("token", ignoreCase = true))
    }

    /**
     * 只安装认证路由和一条测试保护路由，让测试聚焦认证/会话边界而不依赖其它 facade。
     */
    private fun ApplicationTestBuilder.installAuthOnlyApplication(
        authService: WebUiAuthService,
        auditService: WebUiAuditService = WebUiAuditService(sink = {}),
    ) {
        application {
            install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
                json(top.bilibili.utils.json)
            }
            routing {
                registerWebUiAuthRoutes(authService, auditService)
                get("/api/protected") {
                    call.requireWebUiSession(authService, auditService) ?: return@get
                    call.respondText("ok")
                }
                post("/api/protected-write") {
                    call.requireWebUiSession(authService, auditService) ?: return@post
                    call.respondText("ok")
                }
            }
        }
    }

    /**
     * 测试客户端关闭自动重定向并安装 JSON 协商，避免 Cookie 和状态码断言被跟随请求污染。
     */
    private fun ApplicationTestBuilder.createWebUiClient() = createClient {
        followRedirects = false
        install(ContentNegotiation) {
            json(top.bilibili.utils.json)
        }
    }

    /**
     * 登录后提取可直接放入 Cookie 头的 name=value 片段，便于后续请求模拟浏览器同源 Cookie。
     */
    private suspend fun ApplicationTestBuilder.loginAndExtractCookies(password: String): LoginCookies {
        val response = createWebUiClient().post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(WebUiLoginRequestDto(password = password))
        }
        val sessionCookie = response.setCookieFor("hoshimi_cat_bot_webui_session").substringBefore(";")
        val csrfCookie = response.setCookieFor("hoshimi_cat_bot_webui_csrf").substringBefore(";")
        return LoginCookies(
            sessionCookie = sessionCookie,
            csrfCookie = csrfCookie,
            csrfToken = csrfCookie.substringAfter('='),
        )
    }

    /**
     * 测试用认证服务每次使用独立临时凭据文件，避免路由测试之间共享登录状态。
     */
    private fun buildAuthService(): WebUiAuthService {
        val store = WebUiCredentialStore(tempRoot.resolve("${System.nanoTime()}-webui-credentials.json").toFile())
        return WebUiAuthService(
            credentialStore = store,
            tokenService = WebUiTokenService(tokenTtlSeconds = 300L),
        )
    }

    /**
     * Set-Cookie 断言只关心目标 Cookie 的服务端属性，不解析浏览器实现细节。
     */
    private fun HttpResponse.setCookieFor(name: String): String {
        return headers.getAll(HttpHeaders.SetCookie).orEmpty().first { cookie -> cookie.startsWith("$name=") }
    }

    /**
     * Cookie 值断言使用响应头原文，避免测试客户端的 Cookie jar 自动策略影响认证边界。
     */
    private fun HttpResponse.cookieValue(name: String): String? {
        return headers.getAll(HttpHeaders.SetCookie).orEmpty()
            .firstOrNull { cookie -> cookie.startsWith("$name=") }
            ?.substringAfter('=')
            ?.substringBefore(';')
            ?.takeIf { value -> value.isNotBlank() }
    }

    /**
     * 清理 Cookie 必须通过同名 Set-Cookie 与 Max-Age=0 表达，浏览器才能删除旧会话材料。
     */
    private fun assertCookieCleared(response: HttpResponse, name: String) {
        assertTrue(response.setCookieFor(name).contains("Max-Age=0", ignoreCase = true))
    }

    /**
     * 登录 Cookie 结构只保存 header 组装所需的最小字段。
     */
    private data class LoginCookies(
        val sessionCookie: String,
        val csrfCookie: String,
        val csrfToken: String,
    ) {
        /**
         * 认证写请求复用同一组 Cookie 片段，避免每个测试各自拼接 session 和 CSRF 头。
         */
        fun cookieHeader(): String = "$sessionCookie; $csrfCookie"
    }
}
