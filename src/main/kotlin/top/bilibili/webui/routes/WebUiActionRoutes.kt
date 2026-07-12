package top.bilibili.webui.routes

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import top.bilibili.webui.auth.WebUiAuthService
import top.bilibili.webui.model.WebUiActionRequestDto
import top.bilibili.webui.service.WebUiActionFacade
import top.bilibili.webui.service.WebUiAuditService

/**
 * WebUI 动作路由只负责会话认证、CSRF 校验、审计和 facade 调用，不在 HTTP 层混入生命周期细节。
 */
fun Route.registerWebUiActionRoutes(
    authService: WebUiAuthService,
    actionFacade: WebUiActionFacade,
    auditService: WebUiAuditService,
) {
    post("/api/actions/reload-config") {
        call.requireWebUiSession(authService, auditService) ?: return@post
        val result = actionFacade.reloadConfig(WebUiActionRequestDto("reload-config"))
        auditService.recordRiskyAction("reload-config", result, "webui action request")
        call.respond(result)
    }

    post("/api/actions/shutdown") {
        call.requireWebUiSession(authService, auditService) ?: return@post
        val result = actionFacade.shutdown(WebUiActionRequestDto("shutdown"))
        auditService.recordRiskyAction("shutdown", result, "webui action request")
        call.respond(result)
    }

    post("/api/actions/request-restart") {
        call.requireWebUiSession(authService, auditService) ?: return@post
        val result = actionFacade.requestRestart(WebUiActionRequestDto("request-restart"))
        auditService.recordRiskyAction("request-restart", result, "webui action request")
        call.respond(result)
    }
}
