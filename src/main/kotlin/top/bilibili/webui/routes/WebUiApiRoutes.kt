package top.bilibili.webui.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import top.bilibili.webui.auth.WebUiAuthService
import top.bilibili.webui.model.WebUiBiliConfigWriteRequestDto
import top.bilibili.webui.model.WebUiBiliDataWriteRequestDto
import top.bilibili.webui.model.WebUiBotConfigWriteRequestDto
import top.bilibili.webui.model.WebUiConfigSaveResultDto
import top.bilibili.webui.model.WebUiSaveEffectLevel
import top.bilibili.webui.service.WebUiAuditService
import top.bilibili.webui.service.WebUiConfigFacade
import top.bilibili.webui.service.WebUiConfigWriteFacade
import top.bilibili.webui.service.WebUiRuntimeFacade

/**
 * WebUI API 路由统一暴露受认证保护的运行态查询和文件级配置读写接口。
 */
fun Route.registerWebUiApiRoutes(
    authService: WebUiAuthService,
    runtimeFacade: WebUiRuntimeFacade,
    configFacade: WebUiConfigFacade,
    configWriteFacade: WebUiConfigWriteFacade,
    auditService: WebUiAuditService,
) {
    get("/api/health") {
        call.respond(
            WebUiHealthResponse(
                service = "dynamic-bot-webui",
                phase = "foundation-phase2",
                status = "ok",
            ),
        )
    }

    get("/api/runtime/summary") {
        call.requireWebUiSession(authService, auditService) ?: return@get
        call.respond(runtimeFacade.readSummary())
    }

    get("/api/config/bili-config") {
        call.requireWebUiSession(authService, auditService) ?: return@get
        call.respond(configFacade.readBiliConfig())
    }

    get("/api/config/bili-data") {
        call.requireWebUiSession(authService, auditService) ?: return@get
        call.respond(configFacade.readBiliData())
    }

    get("/api/config/bot") {
        call.requireWebUiSession(authService, auditService) ?: return@get
        call.respond(configFacade.readBotConfig())
    }

    post("/api/config/bili-config") {
        val session = call.requireWebUiSession(authService, auditService) ?: return@post
        val request = call.receive<WebUiBiliConfigWriteRequestDto>()
        if (!call.requireHighRiskConfirmation(authService, session, request.confirmationPassword, auditService)) {
            return@post
        }
        val result = configWriteFacade.saveBiliConfig(request)
        auditService.recordConfigSave("BiliConfig.yml", result, "webui save request")
        call.respondSaveResult(result)
    }

    post("/api/config/bili-data") {
        val session = call.requireWebUiSession(authService, auditService) ?: return@post
        val request = call.receive<WebUiBiliDataWriteRequestDto>()
        if (!call.requireHighRiskConfirmation(authService, session, request.confirmationPassword, auditService)) {
            return@post
        }
        val result = configWriteFacade.saveBiliData(request)
        auditService.recordConfigSave("BiliData.yml", result, "webui save request")
        call.respondSaveResult(result)
    }

    post("/api/config/bot") {
        val session = call.requireWebUiSession(authService, auditService) ?: return@post
        val request = call.receive<WebUiBotConfigWriteRequestDto>()
        if (!call.requireHighRiskConfirmation(authService, session, request.confirmationPassword, auditService)) {
            return@post
        }
        val result = configWriteFacade.saveBotConfig(request)
        auditService.recordConfigSave("bot.yml", result, "webui save request")
        call.respondSaveResult(result)
    }
}

/**
 * 占位健康响应只声明 WebUI 基础骨架已就绪，避免提前暴露敏感运行态细节。
 */
@Serializable
data class WebUiHealthResponse(
    val service: String,
    val phase: String,
    val status: String,
)

/**
 * 配置保存结果统一映射到明确的 HTTP 状态码，便于前端和 smoke test 同时断言冲突与校验失败语义。
 */
private suspend fun io.ktor.server.application.ApplicationCall.respondSaveResult(
    result: WebUiConfigSaveResultDto,
) {
    val status = when (result.effectiveLevel) {
        WebUiSaveEffectLevel.REJECTED_CONFLICT -> HttpStatusCode.Conflict
        WebUiSaveEffectLevel.REJECTED_PERSISTENCE -> HttpStatusCode.InternalServerError
        WebUiSaveEffectLevel.REJECTED_VALIDATION -> HttpStatusCode.BadRequest
        else -> HttpStatusCode.OK
    }
    respond(status, result)
}
