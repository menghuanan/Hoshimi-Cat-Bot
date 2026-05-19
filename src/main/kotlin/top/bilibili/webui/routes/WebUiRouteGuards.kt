package top.bilibili.webui.routes

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.respond
import top.bilibili.webui.auth.WebUiAuthService
import top.bilibili.webui.auth.WebUiAuthenticatedSession
import top.bilibili.webui.model.WebUiAuthResponseDto

internal const val WebUiTokenCookieName = "dynamic_bot_webui_token"

/**
 * WebUI route guard 统一解析 header/cookie 中的 token，避免每条路由自己处理认证入口。
 */
suspend fun ApplicationCall.requireWebUiSession(
    authService: WebUiAuthService,
    allowMustChangePassword: Boolean = false,
): WebUiAuthenticatedSession? {
    val session = authService.resolveSession(extractWebUiToken())
    if (session == null) {
        respond(HttpStatusCode.Unauthorized, WebUiAuthResponseDto(success = false, message = "unauthorized"))
        return null
    }
    if (!allowMustChangePassword && session.mustChangePassword) {
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
