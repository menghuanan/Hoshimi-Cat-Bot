package top.bilibili.webui.routes

import io.ktor.http.HttpMethod
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.header
import top.bilibili.webui.auth.WebUiAuthService
import top.bilibili.webui.auth.WebUiAuthenticatedSession
import top.bilibili.webui.model.WebUiAuthResponseDto
import top.bilibili.webui.service.WebUiAuditService

internal const val WebUiSessionCookieName = "hoshimi_cat_bot_webui_session"
internal const val WebUiCsrfCookieName = "hoshimi_cat_bot_webui_csrf"
internal const val WebUiCsrfHeaderName = "X-CSRF-Token"

/**
 * WebUI route guard 统一解析 cookie-backed session，避免每条路由自己处理认证入口。
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
        respondWebUiAuthFailure(HttpStatusCode.Unauthorized, "unauthorized")
        return null
    }
    if (request.httpMethod.requiresWebUiCsrf()) {
        val csrfToken = request.cookies[WebUiCsrfCookieName]
        val csrfHeader = request.header(WebUiCsrfHeaderName)
        if (csrfToken.isNullOrBlank() || csrfHeader.isNullOrBlank() || csrfToken != csrfHeader || csrfToken != session.csrfToken) {
            auditService?.recordDeniedAccess(
                target = request.path(),
                outcome = "FORBIDDEN",
                detailSummary = "csrf validation failed",
            )
            respondWebUiAuthFailure(HttpStatusCode.Forbidden, "forbidden")
            return null
        }
    }
    if (!allowMustChangePassword && session.mustChangePassword) {
        auditService?.recordDeniedAccess(
            target = request.path(),
            outcome = "PASSWORD_CHANGE_REQUIRED",
            detailSummary = "session requires password change before accessing protected route",
        )
        respondWebUiAuthFailure(
            status = HttpStatusCode.Forbidden,
            message = "password change required",
            mustChangePassword = true,
        )
        return null
    }
    return session
}

/**
 * token 只从同源 session cookie 读取，route guard 不再信任 bearer header。
 */
fun ApplicationCall.extractWebUiToken(): String? {
    return request.cookies[WebUiSessionCookieName]
}

/**
 * unsafe verbs 才需要 CSRF 双提交校验，避免对只读请求增加额外 Cookie 依赖。
 */
private fun HttpMethod.requiresWebUiCsrf(): Boolean {
    return this == HttpMethod.Post || this == HttpMethod.Put || this == HttpMethod.Patch || this == HttpMethod.Delete
}

/**
 * 认证失败和拒绝路径统一带 no-store，避免浏览器缓存管理面敏感响应。
 */
private suspend fun ApplicationCall.respondWebUiAuthFailure(
    status: HttpStatusCode,
    message: String,
    mustChangePassword: Boolean = false,
) {
    response.header(HttpHeaders.CacheControl, "no-store")
    respond(
        status,
        WebUiAuthResponseDto(
            success = false,
            mustChangePassword = mustChangePassword,
            message = message,
        ),
    )
}
