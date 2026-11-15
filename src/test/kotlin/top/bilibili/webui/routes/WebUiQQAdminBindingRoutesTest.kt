package top.bilibili.webui.routes

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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
import top.bilibili.config.BotConfig
import top.bilibili.config.PlatformConfig
import top.bilibili.config.QQOfficialConfig
import top.bilibili.connector.PlatformType
import top.bilibili.service.QQAdminBindingService
import top.bilibili.webui.auth.WebUiAuthService
import top.bilibili.webui.auth.WebUiCredentialStore
import top.bilibili.webui.auth.WebUiTokenService
import top.bilibili.webui.service.WebUiAuditRecord
import top.bilibili.webui.service.WebUiAuditService

class WebUiQQAdminBindingRoutesTest {
    /** 未登录和缺 CSRF 均不能获取代码；合法请求禁止缓存且审计不包含代码或应用凭据。 */
    @Test
    fun `binding routes enforce auth csrf no-store and redact audit`() = testApplication {
        val root = Files.createTempDirectory("qq-binding-auth")
        val authService = WebUiAuthService(WebUiCredentialStore(root.resolve("credentials.json").toFile()), WebUiTokenService(300L))
        val initial = authService.bootstrapCredentials().initialPassword!!
        check(authService.changePassword(initial, "Better123!@").success)
        val auth = authService.login("Better123!@")
        val cookie = "$WebUiSessionCookieName=${auth.token}; $WebUiCsrfCookieName=${auth.csrfToken}"
        val records = mutableListOf<WebUiAuditRecord>()
        val config = BotConfig(platform = PlatformConfig(type = PlatformType.QQ_OFFICIAL, qqOfficial = QQOfficialConfig("app", "secret")))
        val binding = QQAdminBindingService({ config }, { null }, { true }).also { it.startIfNeeded() }
        val session = binding.begin()
        application {
            install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
            routing { registerWebUiQQAdminBindingRoutes(authService, WebUiAuditService(sink = records::add), binding) }
        }
        try {
            assertEquals(HttpStatusCode.Unauthorized, client.post("/api/qq-admin-binding").status)
            assertEquals(HttpStatusCode.Forbidden, client.post("/api/qq-admin-binding") { header(HttpHeaders.Cookie, cookie) }.status)
            val created = client.post("/api/qq-admin-binding") {
                header(HttpHeaders.Cookie, cookie)
                header(WebUiCsrfHeaderName, auth.csrfToken!!)
            }
            assertEquals(HttpStatusCode.OK, created.status)
            assertEquals("no-store", created.headers[HttpHeaders.CacheControl])
            assertTrue(created.bodyAsText().contains(session.code!!))
            assertFalse(created.bodyAsText().contains("secret"))
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/qq-admin-binding/${session.id}").status)
            val read = client.get("/api/qq-admin-binding/${session.id}") { header(HttpHeaders.Cookie, cookie) }
            assertEquals(HttpStatusCode.OK, read.status)
            assertEquals("no-store", read.headers[HttpHeaders.CacheControl])
            assertTrue(records.any { it.target == "qq-admin-binding:create" && it.success })
            assertFalse(records.any { it.detailSummary.contains(session.code) })
        } finally {
            binding.stop()
            root.toFile().deleteRecursively()
        }
    }
}
