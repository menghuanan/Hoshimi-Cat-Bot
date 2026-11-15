package top.bilibili.webui.routes

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import top.bilibili.core.BiliBiliBot
import top.bilibili.service.QQAdminBindingService
import top.bilibili.webui.auth.WebUiAuthService
import top.bilibili.webui.service.WebUiAuditService

/** 绑定管理入口复用 session、CSRF 和审计；绑定码不进入 URL 或审计详情。 */
fun Route.registerWebUiQQAdminBindingRoutes(
    authService: WebUiAuthService,
    auditService: WebUiAuditService,
    binding: QQAdminBindingService = BiliBiliBot.qqAdminBinding,
) {
    post("/api/qq-admin-binding") {
        call.requireWebUiSession(authService, auditService) ?: return@post
        call.response.header(HttpHeaders.CacheControl, "no-store")
        val result = runCatching { binding.begin() }
        auditService.recordRiskyEvent(
            action = "qq-admin-binding:create",
            success = result.isSuccess,
            outcome = if (result.isSuccess) "WAITING" else "UNAVAILABLE",
            detailSummary = "official QQ administrator binding",
        )
        val session = result.getOrNull()
        if (session == null) {
            call.respond(HttpStatusCode.Conflict, BindingError(result.exceptionOrNull()?.message ?: "暂时无法绑定"))
        } else {
            call.respond(session.toDto())
        }
    }
    get("/api/qq-admin-binding/{sessionId}") {
        call.requireWebUiSession(authService, auditService) ?: return@get
        call.response.header(HttpHeaders.CacheControl, "no-store")
        val session = binding.read(call.parameters["sessionId"].orEmpty())
        if (session == null) call.respond(HttpStatusCode.NotFound, BindingError("绑定会话已失效，请重新获取"))
        else call.respond(session.toDto())
    }
}

/** HTTP 快照只包含操作所需字段，不输出应用配置和内部管理员基线。 */
@Serializable
private data class BindingSessionDto(
    val sessionId: String,
    val code: String?,
    val expiresAtEpochMillis: Long,
    val phase: String,
)

/** 统一失败响应供前端 requestJson 提取可见提示。 */
@Serializable
private data class BindingError(val message: String)

/** 显式映射脱敏快照，防止未来服务内部字段自动变成 API 契约。 */
private fun QQAdminBindingService.Session.toDto() = BindingSessionDto(id, code, expiresAtEpochMillis, phase.name)
