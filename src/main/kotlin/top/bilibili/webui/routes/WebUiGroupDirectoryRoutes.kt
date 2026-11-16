package top.bilibili.webui.routes

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import top.bilibili.webui.auth.WebUiAuthService
import top.bilibili.webui.service.*

/** 群目录管理沿用统一 session/CSRF guard；审计只记录动作，不写 OpenID、备注或凭据。 */
fun Route.registerWebUiGroupDirectoryRoutes(
    authService: WebUiAuthService,
    auditService: WebUiAuditService,
    facade: WebUiGroupDirectoryFacade = WebUiGroupDirectoryFacade(),
) {
    get("/api/platform/groups") {
        call.requireWebUiSession(authService, auditService) ?: return@get
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respond(facade.read())
    }
    post("/api/platform/groups/refresh") {
        call.requireWebUiSession(authService, auditService) ?: return@post
        val request = call.receive<WebUiGroupRefreshRequest>()
        val result = directoryAction { facade.refresh(request.generation) }
        auditService.recordRiskyEvent("group-directory:refresh", result.success, if (result.success) "QUEUED" else "FAILED", "group directory refresh")
        call.respond(if (result.success) HttpStatusCode.Accepted else HttpStatusCode.Conflict, result)
    }
    post("/api/platform/groups/alias") {
        call.requireWebUiSession(authService, auditService) ?: return@post
        val request = call.receive<WebUiGroupAliasRequest>()
        val result = directoryAction { facade.rename(request) }
        auditService.recordRiskyEvent("group-directory:alias", result.success, if (result.success) "SAVED" else "FAILED", "group directory alias")
        call.respond(if (result.success) HttpStatusCode.OK else HttpStatusCode.Conflict, result)
    }
}

/** 输入错误可见，文件与内部异常仅返回通用提示，避免泄露本机路径或配置。 */
private fun directoryAction(action: () -> Unit): DirectoryActionResult = try {
    action(); DirectoryActionResult(true, "操作成功")
} catch (error: IllegalArgumentException) { DirectoryActionResult(false, error.message ?: "参数无效") }
catch (_: Exception) { DirectoryActionResult(false, "操作失败，请检查群目录状态并重试") }

/** 与 requestJson 的错误读取契约一致。 */
@Serializable
private data class DirectoryActionResult(val success: Boolean, val message: String)
