package top.bilibili.webui.routes

import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import top.bilibili.webui.auth.WebUiAuthService
import top.bilibili.webui.model.WebUiActionConfirmationRequestDto
import top.bilibili.webui.model.WebUiActionRequestDto
import top.bilibili.webui.service.WebUiActionFacade
import top.bilibili.webui.service.WebUiAuditService

/**
 * WebUI 动作路由只负责认证、确认和 facade 调用，不在 HTTP 层混入生命周期细节。
 */
fun Route.registerWebUiActionRoutes(
    authService: WebUiAuthService,
    actionFacade: WebUiActionFacade,
    auditService: WebUiAuditService,
) {
    post("/api/actions/reload-config") {
        val session = call.requireWebUiSession(authService) ?: return@post
        val confirmation = call.receive<WebUiActionConfirmationRequestDto>()
        if (!call.requireHighRiskConfirmation(authService, session, confirmation.confirmationPassword)) {
            return@post
        }
        val result = actionFacade.reloadConfig(WebUiActionRequestDto("reload-config"), confirmation)
        auditService.recordRiskyAction("reload-config", result, "webui action request")
        call.respond(result)
    }

    post("/api/actions/shutdown") {
        val session = call.requireWebUiSession(authService) ?: return@post
        val confirmation = call.receive<WebUiActionConfirmationRequestDto>()
        if (!call.requireHighRiskConfirmation(authService, session, confirmation.confirmationPassword)) {
            return@post
        }
        val result = actionFacade.shutdown(WebUiActionRequestDto("shutdown"), confirmation)
        auditService.recordRiskyAction("shutdown", result, "webui action request")
        call.respond(result)
    }

    post("/api/actions/request-restart") {
        val session = call.requireWebUiSession(authService) ?: return@post
        val confirmation = call.receive<WebUiActionConfirmationRequestDto>()
        if (!call.requireHighRiskConfirmation(authService, session, confirmation.confirmationPassword)) {
            return@post
        }
        val result = actionFacade.requestRestart(WebUiActionRequestDto("request-restart"), confirmation)
        auditService.recordRiskyAction("request-restart", result, "webui action request")
        call.respond(result)
    }
}
