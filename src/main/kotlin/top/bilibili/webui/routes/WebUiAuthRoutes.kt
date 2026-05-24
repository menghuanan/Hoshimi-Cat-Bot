package top.bilibili.webui.routes

import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import top.bilibili.webui.auth.WebUiAuthService
import top.bilibili.webui.auth.WebUiLoginContext
import top.bilibili.webui.model.WebUiAuthResponseDto
import top.bilibili.webui.model.WebUiChangePasswordRequestDto
import top.bilibili.webui.model.WebUiLoginRequestDto
import top.bilibili.webui.model.WebUiSessionDto
import top.bilibili.webui.service.WebUiAuditService

/**
 * WebUI 认证路由只处理登录、改密和会话探针，不承担运行态或配置查询逻辑。
 */
fun Route.registerWebUiAuthRoutes(
    authService: WebUiAuthService,
    auditService: WebUiAuditService,
) {
    get("/api/auth/session") {
        val session = authService.resolveSession(call.extractWebUiToken())
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respond(
            WebUiSessionDto(
                authenticated = session != null,
                mustChangePassword = session?.mustChangePassword == true,
            ),
        )
    }

    post("/api/auth/login") {
        val request = call.receive<WebUiLoginRequestDto>()
        val result = authService.login(request.password, call.webUiLoginContext())
        call.response.header(HttpHeaders.CacheControl, "no-store")
        if (!result.success || result.token == null || result.csrfToken == null) {
            auditService.recordLoginFailure(
                sourceIp = call.sourceIp(),
                userAgent = call.userAgentSummary(),
                message = result.message,
            )
            call.respond(
                HttpStatusCode.Unauthorized,
                WebUiAuthResponseDto(success = false, message = result.message),
            )
            return@post
        }
        auditService.recordAuthEvent(
            target = "login",
            success = true,
            outcome = "LOGIN_SUCCEEDED",
            detailSummary = "sourceIp=${call.sourceIp()} userAgent=${call.userAgentSummary()} mustChangePassword=${result.mustChangePassword}",
        )
        // 登录成功只下发同源 session/csrf cookie，不再返回 bearer token。
        call.appendWebUiCookies(result.token, result.csrfToken)
        call.respond(
            WebUiAuthResponseDto(
                success = true,
                mustChangePassword = result.mustChangePassword,
            ),
        )
    }

    post("/api/auth/change-password") {
        call.requireWebUiSession(authService, auditService, allowMustChangePassword = true) ?: return@post
        val request = call.receive<WebUiChangePasswordRequestDto>()
        val result = authService.changePassword(
            currentPassword = request.currentPassword,
            newPassword = request.newPassword,
        )
        call.response.header(HttpHeaders.CacheControl, "no-store")
        if (!result.success) {
            auditService.recordAuthEvent(
                target = "change-password",
                success = false,
                outcome = "PASSWORD_CHANGE_FAILED",
                detailSummary = "message=${result.message}",
            )
            call.respond(HttpStatusCode.BadRequest, WebUiAuthResponseDto(success = false, message = result.message))
            return@post
        }
        auditService.recordAuthEvent(
            target = "change-password",
            success = true,
            outcome = "PASSWORD_CHANGED",
            detailSummary = "requiresReauthentication=${result.requiresReauthentication}",
        )
        // 改密成功后清理 session/csrf cookie，强制前端重新登录获取新的 cookie-backed session。
        call.clearWebUiCookies()
        call.respond(
            WebUiAuthResponseDto(
                success = true,
                mustChangePassword = false,
                message = "password changed",
            ),
        )
    }

    post("/api/auth/logout") {
        val session = call.requireWebUiSession(authService, auditService, allowMustChangePassword = true) ?: return@post
        val revoked = authService.logout(session)
        call.response.header(HttpHeaders.CacheControl, "no-store")
        auditService.recordAuthEvent(
            target = "logout",
            success = revoked,
            outcome = if (revoked) "LOGOUT_SUCCEEDED" else "LOGOUT_TOKEN_MISSING",
            detailSummary = "tokenVersion=${session.tokenVersion}",
        )
        // 登出后清理同源 cookie，前端不再依赖 sessionStorage 中的 bearer token。
        call.clearWebUiCookies()
        call.respond(WebUiAuthResponseDto(success = true, message = "logged out"))
    }
}

/**
 * 登录上下文只从请求头提取审计和节流所需的最小来源信息。
 */
private fun io.ktor.server.application.ApplicationCall.webUiLoginContext(): WebUiLoginContext {
    return WebUiLoginContext(
        sourceIp = sourceIp(),
        userAgent = userAgentSummary(),
    )
}

/**
 * Session cookie 采用 HttpOnly，CSRF cookie 供前端回填 X-CSRF-Token，二者必须同时存在。
 */
private fun io.ktor.server.application.ApplicationCall.appendWebUiCookies(
    sessionToken: String,
    csrfToken: String,
) {
    response.cookies.append(
        Cookie(
            name = WebUiSessionCookieName,
            value = sessionToken,
            path = "/",
            httpOnly = true,
        ),
    )
    response.cookies.append(
        Cookie(
            name = WebUiCsrfCookieName,
            value = csrfToken,
            path = "/",
            httpOnly = false,
        ),
    )
}

/**
 * 清理认证 Cookie 时保持同一 name/path，确保浏览器能删除旧 session 和 CSRF 值。
 */
private fun io.ktor.server.application.ApplicationCall.clearWebUiCookies() {
    response.cookies.append(
        Cookie(
            name = WebUiSessionCookieName,
            value = "",
            path = "/",
            httpOnly = true,
            maxAge = 0,
        ),
    )
    response.cookies.append(
        Cookie(
            name = WebUiCsrfCookieName,
            value = "",
            path = "/",
            httpOnly = false,
            maxAge = 0,
        ),
    )
}

/**
 * User-Agent 摘要只保留截断后的单行文本，便于审计检索而不引入大段 header 原文。
 */
private fun io.ktor.server.application.ApplicationCall.userAgentSummary(maxLength: Int = 80): String {
    return request.header(HttpHeaders.UserAgent)
        .orEmpty()
        .replace(Regex("""\s+"""), " ")
        .trim()
        .take(maxLength)
}

/**
 * 来源 IP 优先取转发头首个值，兼容本地 testApplication 和反向代理前置场景。
 */
private fun io.ktor.server.application.ApplicationCall.sourceIp(): String {
    val forwarded = request.header("X-Forwarded-For")
        ?.split(',')
        ?.firstOrNull()
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    return forwarded ?: request.local.remoteHost
}
