package top.bilibili.webui.routes

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.*
import kotlinx.serialization.json.Json
import top.bilibili.config.PlatformGroupDirectoryStore
import top.bilibili.connector.*
import top.bilibili.service.PlatformGroupDirectoryService
import top.bilibili.webui.auth.*
import top.bilibili.webui.service.*

class WebUiGroupDirectoryRoutesTest {
    /** 群 OpenID 属于受保护管理数据，读需 session，备注/刷新另需 CSRF，审计不得带具体群标识。 */
    @Test fun `directory routes enforce auth csrf generation and redacted audit`() = testApplication {
        val root = Files.createTempDirectory("qq-groups-auth")
        val authService = WebUiAuthService(WebUiCredentialStore(root.resolve("credentials.json").toFile()), WebUiTokenService(300L))
        val initial = authService.bootstrapCredentials().initialPassword!!
        check(authService.changePassword(initial, "Better123!@").success)
        val auth = authService.login("Better123!@")
        val cookie = "$WebUiSessionCookieName=${auth.token}; $WebUiCsrfCookieName=${auth.csrfToken}"
        val records = mutableListOf<WebUiAuditRecord>()
        val directory = PlatformGroupDirectoryService({ PlatformGroupContext("app-private", "current", true) }, { true },
            { _, _, _, _ -> PlatformGroupProfile() }, PlatformGroupDirectoryStore(root.resolve("groups.json").toFile()))
        directory.initialize(); directory.observe("app-private", PlatformGroupObservation("opaque-private", observedAt = 1))
        application {
            install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
            routing { registerWebUiGroupDirectoryRoutes(authService, WebUiAuditService(sink = records::add), WebUiGroupDirectoryFacade(directory)) }
        }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/platform/groups").status)
        val read = client.get("/api/platform/groups") { header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.OK, read.status); assertEquals("no-store", read.headers[HttpHeaders.CacheControl])
        assertTrue(read.bodyAsText().contains("opaque-private")); assertFalse(read.bodyAsText().contains("app-private"))
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/platform/groups/alias") { header(HttpHeaders.Cookie, cookie) }.status)
        val changed = client.post("/api/platform/groups/alias") {
            header(HttpHeaders.Cookie, cookie); header(WebUiCsrfHeaderName, auth.csrfToken!!)
            contentType(ContentType.Application.Json); setBody("""{"generation":"current","openId":"opaque-private","alias":"测试备注"}""")
        }
        assertEquals(HttpStatusCode.OK, changed.status)
        assertEquals("测试备注", directory.snapshot().groups.single().alias)
        val stale = client.post("/api/platform/groups/refresh") {
            header(HttpHeaders.Cookie, cookie); header(WebUiCsrfHeaderName, auth.csrfToken!!)
            contentType(ContentType.Application.Json); setBody("""{"generation":"old"}""")
        }
        assertEquals(HttpStatusCode.Conflict, stale.status)
        assertTrue(records.any { it.target == "group-directory:alias" && it.success })
        assertFalse(records.any { it.detailSummary.contains("opaque-private") || it.detailSummary.contains("测试备注") })
    }
}
