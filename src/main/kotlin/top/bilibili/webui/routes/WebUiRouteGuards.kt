package top.bilibili.webui.routes

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.response.respond
import top.bilibili.webui.auth.WebUiAuthService
import top.bilibili.webui.auth.WebUiAuthenticatedSession
import top.bilibili.webui.model.WebUiAuthResponseDto
import top.bilibili.webui.service.WebUiAuditService

internal const val WebUiTokenCookieName = "dynamic_bot_webui_token"

/**
 * WebUI route guard 统一解析 header/cookie 中的 token，避免每条路由自己处理认证入口。
 */
suspend fun ApplicationCall.requireWebUiSession(
    authService: WebUiAuthService,
    auditService: WebUiAuditService? = null,
    allowMustChangePassword: Boolean = false,
): WebUiAuthenticatedSession? {
    val session = authService.resolveSession(extractWebUiToken())
    if (session == null) {
        auditService?.recordDeniedAccess(
            target = request.path(),
            outcome = "UNAUTHORIZED",
            detailSummary = "missing or invalid session",
        )
        respond(HttpStatusCode.Unauthorized, WebUiAuthResponseDto(success = false, message = "unauthorized"))
        return null
    }
    if (!allowMustChangePassword && session.mustChangePassword) {
        auditService?.recordDeniedAccess(
            target = request.path(),
            outcome = "PASSWORD_CHANGE_REQUIRED",
            detailSummary = "session requires password change before accessing protected route",
        )
        respond(
            HttpStatusCode.Forbidden,
            WebUiAuthResponseDto(
                success = false,
                mustChangePassword = true,
                message = "password change required",
            ),
        )
        return null
    }
    return session
}

/**
 * token 优先从 Bearer header 读取；缺失时回退到登录时下发的同源 cookie。
 */
fun ApplicationCall.extractWebUiToken(): String? {
    val bearerToken = request.header(HttpHeaders.Authorization)
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.substringAfter(' ')
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    return bearerToken ?: request.cookies[WebUiTokenCookieName]
}

/**
 * 高风险写入和动作请求必须再次确认当前密码，避免单靠登录 token 就执行破坏性操作。
 */
suspend fun ApplicationCall.requireHighRiskConfirmation(
    authService: WebUiAuthService,
    session: WebUiAuthenticatedSession,
    confirmationPassword: String,
    auditService: WebUiAuditService? = null,
): Boolean {
    val result = authService.confirmHighRiskOperation(session, confirmationPassword)
    if (!result.confirmed) {
        auditService?.recordDeniedAccess(
            target = "high-risk-confirmation",
            outcome = "FORBIDDEN",
            detailSummary = "path=${request.path()} reason=${result.message}",
        )
        respond(
            HttpStatusCode.Forbidden,
            WebUiAuthResponseDto(
                success = false,
                message = result.message,
            ),
        )
        return false
    }
    return true
}
