package top.bilibili.webui.routes

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import top.bilibili.webui.auth.WebUiAuthService
import top.bilibili.webui.model.WebUiBiliLoginConflictDto
import top.bilibili.webui.model.WebUiBiliLoginErrorDto
import top.bilibili.webui.model.WebUiBiliLoginPhase
import top.bilibili.webui.model.WebUiBiliLoginStartRequestDto
import top.bilibili.webui.service.WebUiAuditService
import top.bilibili.webui.service.WebUiBiliLoginCancelOutcome
import top.bilibili.webui.service.WebUiBiliLoginFacade
import top.bilibili.webui.service.WebUiBiliLoginStartOutcome

/**
 * B站二维码登录路由统一执行认证、CSRF、高风险确认和脱敏响应，不接触底层登录凭据。
 */
fun Route.registerWebUiBiliLoginRoutes(
    authService: WebUiAuthService,
    facade: WebUiBiliLoginFacade,
    auditService: WebUiAuditService,
) {
    post("/api/bili-login/sessions") {
        val session = call.requireWebUiSession(authService, auditService) ?: return@post
        val request = call.receive<WebUiBiliLoginStartRequestDto>()
        if (!call.requireHighRiskConfirmation(authService, session, request.confirmationPassword, auditService)) {
            return@post
        }
        call.markBiliLoginNoStore()
        when (val outcome = facade.start()) {
            is WebUiBiliLoginStartOutcome.Created -> {
                auditService.recordRiskyEvent(
                    action = "bili-login:create",
                    success = true,
                    outcome = "CREATED",
                    detailSummary = "sessionId=${outcome.session.sessionId} phase=${outcome.session.phase}",
                )
                call.respond(HttpStatusCode.Created, outcome.session)
            }
            is WebUiBiliLoginStartOutcome.Conflict -> {
                val message = outcome.remainingSeconds?.let { remainingSeconds ->
                    "已有登录流程进行中，请在 $remainingSeconds 秒后重试"
                } ?: "已有登录流程正在提交凭据，请稍候"
                auditService.recordRiskyEvent(
                    action = "bili-login:create",
                    success = false,
                    outcome = "CONFLICT",
                    detailSummary = "phase=${outcome.phase} remainingSeconds=${outcome.remainingSeconds}",
                )
                call.respond(
                    HttpStatusCode.Conflict,
                    WebUiBiliLoginConflictDto(message, outcome.phase, outcome.remainingSeconds),
                )
            }
            is WebUiBiliLoginStartOutcome.Unavailable -> {
                auditService.recordRiskyEvent(
                    action = "bili-login:create",
                    success = false,
                    outcome = "UNAVAILABLE",
                    detailSummary = "message=${outcome.message}",
                )
                call.respond(HttpStatusCode.BadGateway, WebUiBiliLoginErrorDto(outcome.message))
            }
        }
    }

    get("/api/bili-login/sessions/{sessionId}") {
        call.requireWebUiSession(authService, auditService) ?: return@get
        call.markBiliLoginNoStore()
        val sessionId = call.parameters["sessionId"].orEmpty()
        val snapshot = facade.read(sessionId)
        if (snapshot == null) {
            call.respond(HttpStatusCode.NotFound, WebUiBiliLoginErrorDto("登录会话不存在"))
            return@get
        }
        if (snapshot.phase.isTerminal()) {
            auditService.recordRiskyEvent(
                action = "bili-login:terminal",
                success = snapshot.phase == WebUiBiliLoginPhase.SUCCEEDED,
                outcome = snapshot.phase.name,
                detailSummary = "sessionId=${snapshot.sessionId}",
            )
        }
        call.respond(snapshot)
    }

    delete("/api/bili-login/sessions/{sessionId}") {
        call.requireWebUiSession(authService, auditService) ?: return@delete
        call.markBiliLoginNoStore()
        val sessionId = call.parameters["sessionId"].orEmpty()
        when (facade.cancel(sessionId)) {
            WebUiBiliLoginCancelOutcome.CANCELLED -> {
                auditService.recordRiskyEvent(
                    action = "bili-login:cancel",
                    success = true,
                    outcome = "CANCELLED",
                    detailSummary = "sessionId=$sessionId",
                )
                call.respond(facade.read(sessionId) ?: WebUiBiliLoginErrorDto("登录已取消"))
            }
            WebUiBiliLoginCancelOutcome.ALREADY_TERMINAL -> {
                val snapshot = facade.read(sessionId)
                if (snapshot == null) {
                    call.respond(HttpStatusCode.NotFound, WebUiBiliLoginErrorDto("登录会话不存在"))
                } else {
                    call.respond(snapshot)
                }
            }
            WebUiBiliLoginCancelOutcome.COMMITTING -> {
                auditService.recordRiskyEvent(
                    action = "bili-login:cancel",
                    success = false,
                    outcome = "COMMITTING",
                    detailSummary = "sessionId=$sessionId",
                )
                call.respond(
                    HttpStatusCode.Conflict,
                    facade.read(sessionId) ?: WebUiBiliLoginErrorDto("登录凭据正在提交，暂时无法取消"),
                )
            }
            WebUiBiliLoginCancelOutcome.NOT_FOUND -> {
                call.respond(HttpStatusCode.NotFound, WebUiBiliLoginErrorDto("登录会话不存在"))
            }
        }
    }
}

/** 二维码响应一律禁止缓存，避免浏览器或代理保留登录图片和状态。 */
private fun ApplicationCall.markBiliLoginNoStore() {
    response.header(HttpHeaders.CacheControl, "no-store")
}

/** 终态判断集中在路由边界，避免等待态轮询重复写入完成审计。 */
private fun WebUiBiliLoginPhase.isTerminal(): Boolean {
    return this == WebUiBiliLoginPhase.SUCCEEDED ||
        this == WebUiBiliLoginPhase.EXPIRED ||
        this == WebUiBiliLoginPhase.FAILED ||
        this == WebUiBiliLoginPhase.CANCELLED
}
