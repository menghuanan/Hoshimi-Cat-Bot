package top.bilibili.webui.routes

import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import top.bilibili.webui.auth.WebUiAuthService
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
        call.respond(
            WebUiSessionDto(
                authenticated = session != null,
                mustChangePassword = session?.mustChangePassword == true,
            ),
        )
    }

    post("/api/auth/login") {
        val request = call.receive<WebUiLoginRequestDto>()
        val result = authService.login(request.password)
        if (!result.success || result.token == null) {
            auditService.recordAuthEvent(
                target = "login",
                success = false,
                outcome = "LOGIN_FAILED",
                detailSummary = "message=${result.message}",
            )
            call.respond(HttpStatusCode.Unauthorized, WebUiAuthResponseDto(success = false, message = result.message))
            return@post
        }
        auditService.recordAuthEvent(
            target = "login",
            success = true,
            outcome = "LOGIN_SUCCEEDED",
            detailSummary = "mustChangePassword=${result.mustChangePassword}",
        )
        // 根页面鉴权依赖同源 cookie，API 仍可继续使用 Bearer token 头。
        call.response.cookies.append(
            Cookie(
                name = WebUiTokenCookieName,
                value = result.token,
                path = "/",
                httpOnly = true,
            ),
        )
        call.respond(
            WebUiAuthResponseDto(
                success = true,
                token = result.token,
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
        // 改密成功后覆盖同名 cookie 为立即过期值，强制前端重新用新密码登录获取新 token。
        call.response.cookies.append(
            Cookie(
                name = WebUiTokenCookieName,
                value = "",
                path = "/",
                httpOnly = true,
                maxAge = 0,
            ),
        )
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
        val revoked = authService.logout(session.token)
        auditService.recordAuthEvent(
            target = "logout",
            success = revoked,
            outcome = if (revoked) "LOGOUT_SUCCEEDED" else "LOGOUT_TOKEN_MISSING",
            detailSummary = "tokenVersion=${session.tokenVersion}",
        )
        // 登出后清理同源 cookie，前端同时会清掉 sessionStorage 中的 Bearer token。
        call.response.cookies.append(
            Cookie(
                name = WebUiTokenCookieName,
                value = "",
                path = "/",
                httpOnly = true,
                maxAge = 0,
            ),
        )
        call.respond(WebUiAuthResponseDto(success = true, message = "logged out"))
    }
}
