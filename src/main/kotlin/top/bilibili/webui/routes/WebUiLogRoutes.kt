package top.bilibili.webui.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.withCharset
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import top.bilibili.webui.auth.WebUiAuthService
import top.bilibili.webui.service.WebUiAuditService
import top.bilibili.webui.service.WebUiLogFacade

/**
 * WebUI 日志路由只允许读取固定白名单日志来源，不接受任意文件路径。
 */
fun Route.registerWebUiLogRoutes(
    authService: WebUiAuthService,
    logFacade: WebUiLogFacade,
    auditService: WebUiAuditService,
) {
    get("/api/logs/sources") {
        call.requireWebUiSession(authService, auditService) ?: return@get
        call.respond(logFacade.listSources())
    }

    get("/api/logs/{sourceId}") {
        call.requireWebUiSession(authService, auditService) ?: return@get
        val sourceId = call.parameters["sourceId"].orEmpty()
        val tailLines = call.request.queryParameters["tail"]?.toIntOrNull() ?: 200
        val window = logFacade.readLogWindow(sourceId, tailLines)
        if (window == null) {
            call.respond(io.ktor.http.HttpStatusCode.NotFound)
            return@get
        }
        call.respond(window)
    }

    get("/api/logs/{sourceId}/export") {
        call.requireWebUiSession(authService, auditService) ?: return@get
        val sourceId = call.parameters["sourceId"].orEmpty()
        val tailLines = call.request.queryParameters["tail"]?.toIntOrNull() ?: 500
        val text = logFacade.exportLogText(sourceId, tailLines)
        if (text == null) {
            call.respond(io.ktor.http.HttpStatusCode.NotFound)
            return@get
        }

        // 下载头只使用 source id 生成稳定文件名，避免日志标题注入响应头。
        val safeFileSourceId = sourceId.replace(Regex("""[^A-Za-z0-9._-]"""), "_").ifBlank { "logs" }
        call.response.header(
            HttpHeaders.ContentDisposition,
            """attachment; filename="dynamic-bot-${safeFileSourceId}.log"""",
        )
        call.respondText(text, ContentType.Text.Plain.withCharset(Charsets.UTF_8))
    }

    post("/api/logs/{sourceId}/clear") {
        call.requireWebUiSession(authService, auditService) ?: return@post
        val sourceId = call.parameters["sourceId"].orEmpty()
        val result = logFacade.clearLogSource(sourceId)
        if (result == null) {
            call.respond(io.ktor.http.HttpStatusCode.NotFound)
            return@post
        }
        call.respond(result)
    }
}
