package top.bilibili.webui.routes

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
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
}
