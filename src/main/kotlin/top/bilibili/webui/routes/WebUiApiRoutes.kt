package top.bilibili.webui.routes

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import top.bilibili.webui.auth.WebUiAuthService
import top.bilibili.webui.model.WebUiBiliConfigWriteRequestDto
import top.bilibili.webui.model.WebUiBiliDataWriteRequestDto
import top.bilibili.webui.model.WebUiBotConfigWriteRequestDto
import top.bilibili.webui.model.WebUiActionConfirmationRequestDto
import top.bilibili.webui.model.WebUiConfigSaveResultDto
import top.bilibili.webui.model.WebUiSaveEffectLevel
import top.bilibili.webui.model.WebUiSubscriptionAtAllSaveRequestDto
import top.bilibili.webui.model.WebUiSubscriptionCreateRequestDto
import top.bilibili.webui.model.WebUiSubscriptionFilterSaveRequestDto
import top.bilibili.webui.model.WebUiSubscriptionTemplateRandomRequestDto
import top.bilibili.webui.model.WebUiSubscriptionTemplateSaveRequestDto
import top.bilibili.webui.model.WebUiSubscriptionTargetSaveRequestDto
import top.bilibili.webui.model.WebUiSubscriptionThemeSaveRequestDto
import top.bilibili.webui.model.WebUiSubscriptionUidSaveRequestDto
import top.bilibili.webui.service.WebUiAuditService
import top.bilibili.webui.service.WebUiConfigFacade
import top.bilibili.webui.service.WebUiConfigWriteFacade
import top.bilibili.webui.service.WebUiRuntimeFacade
import top.bilibili.webui.service.WebUiSubscriptionManagementFacade

/**
 * WebUI API 路由统一暴露受认证保护的运行态查询和文件级配置读写接口。
 */
fun Route.registerWebUiApiRoutes(
    authService: WebUiAuthService,
    runtimeFacade: WebUiRuntimeFacade,
    configFacade: WebUiConfigFacade,
    configWriteFacade: WebUiConfigWriteFacade,
    subscriptionManagementFacade: WebUiSubscriptionManagementFacade,
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

    get("/api/subscriptions") {
        call.requireWebUiSession(authService, auditService) ?: return@get
        call.respond(configFacade.readSubscriptions())
    }

    post("/api/subscriptions") {
        val session = call.requireWebUiSession(authService, auditService) ?: return@post
        val request = call.receive<WebUiSubscriptionCreateRequestDto>()
        if (!call.requireHighRiskConfirmation(authService, session, request.confirmationPassword, auditService)) {
            return@post
        }
        val result = subscriptionManagementFacade.createSubscription(request)
        call.respondSubscriptionMutation(result)
    }

    delete("/api/subscriptions/{id}") {
        val session = call.requireWebUiSession(authService, auditService) ?: return@delete
        if (!call.requireHighRiskConfirmation(authService, session, call.receiveConfirmationPasswordOrEmpty(), auditService)) {
            return@delete
        }
        val id = call.parameters["id"].orEmpty()
        val result = subscriptionManagementFacade.deleteSubscription(id)
        call.respondSubscriptionMutation(result)
    }

    get("/api/subscriptions/{id}/targets") {
        call.requireWebUiSession(authService, auditService) ?: return@get
        val id = call.parameters["id"].orEmpty()
        call.respond(subscriptionManagementFacade.listSubscriptionTargets(id))
    }

    post("/api/subscriptions/{id}/targets") {
        val session = call.requireWebUiSession(authService, auditService) ?: return@post
        val id = call.parameters["id"].orEmpty()
        val request = call.receive<WebUiSubscriptionTargetSaveRequestDto>()
        if (!call.requireHighRiskConfirmation(authService, session, request.confirmationPassword, auditService)) {
            return@post
        }
        val result = subscriptionManagementFacade.saveSubscriptionTarget(id, request)
        call.respondSubscriptionMutation(result)
    }

    delete("/api/subscriptions/{id}/targets/{key}") {
        val session = call.requireWebUiSession(authService, auditService) ?: return@delete
        if (!call.requireHighRiskConfirmation(authService, session, call.receiveConfirmationPasswordOrEmpty(), auditService)) {
            return@delete
        }
        val id = call.parameters["id"].orEmpty()
        val key = call.parameters["key"].orEmpty()
        val result = subscriptionManagementFacade.deleteSubscriptionTarget(id, key)
        call.respondSubscriptionMutation(result)
    }

    get("/api/subscriptions/{id}/uids") {
        call.requireWebUiSession(authService, auditService) ?: return@get
        val id = call.parameters["id"].orEmpty()
        call.respond(subscriptionManagementFacade.listSubscriptionUids(id))
    }

    post("/api/subscriptions/{id}/uids") {
        val session = call.requireWebUiSession(authService, auditService) ?: return@post
        val id = call.parameters["id"].orEmpty()
        val request = call.receive<WebUiSubscriptionUidSaveRequestDto>()
        if (!call.requireHighRiskConfirmation(authService, session, request.confirmationPassword, auditService)) {
            return@post
        }
        val result = subscriptionManagementFacade.saveSubscriptionUid(id, request)
        call.respondSubscriptionMutation(result)
    }

    delete("/api/subscriptions/{id}/uids/{key}") {
        val session = call.requireWebUiSession(authService, auditService) ?: return@delete
        if (!call.requireHighRiskConfirmation(authService, session, call.receiveConfirmationPasswordOrEmpty(), auditService)) {
            return@delete
        }
        val id = call.parameters["id"].orEmpty()
        val key = call.parameters["key"].orEmpty()
        val result = subscriptionManagementFacade.deleteSubscriptionUid(id, key)
        call.respondSubscriptionMutation(result)
    }

    get("/api/subscriptions/{id}/filters") {
        call.requireWebUiSession(authService, auditService) ?: return@get
        val id = call.parameters["id"].orEmpty()
        call.respond(subscriptionManagementFacade.listSubscriptionFilters(id))
    }

    post("/api/subscriptions/{id}/filters") {
        val session = call.requireWebUiSession(authService, auditService) ?: return@post
        val id = call.parameters["id"].orEmpty()
        val request = call.receive<WebUiSubscriptionFilterSaveRequestDto>()
        if (!call.requireHighRiskConfirmation(authService, session, request.confirmationPassword, auditService)) {
            return@post
        }
        val result = subscriptionManagementFacade.saveSubscriptionFilter(id, request)
        call.respondSubscriptionMutation(result)
    }

    delete("/api/subscriptions/{id}/filters/{key}") {
        val session = call.requireWebUiSession(authService, auditService) ?: return@delete
        if (!call.requireHighRiskConfirmation(authService, session, call.receiveConfirmationPasswordOrEmpty(), auditService)) {
            return@delete
        }
        val id = call.parameters["id"].orEmpty()
        val key = call.parameters["key"].orEmpty()
        val result = subscriptionManagementFacade.deleteSubscriptionFilter(id, key)
        call.respondSubscriptionMutation(result)
    }

    get("/api/subscriptions/{id}/templates") {
        call.requireWebUiSession(authService, auditService) ?: return@get
        val id = call.parameters["id"].orEmpty()
        call.respond(subscriptionManagementFacade.listSubscriptionTemplates(id))
    }

    post("/api/subscriptions/{id}/templates") {
        val session = call.requireWebUiSession(authService, auditService) ?: return@post
        val id = call.parameters["id"].orEmpty()
        val request = call.receive<WebUiSubscriptionTemplateSaveRequestDto>()
        if (!call.requireHighRiskConfirmation(authService, session, request.confirmationPassword, auditService)) {
            return@post
        }
        val result = subscriptionManagementFacade.saveSubscriptionTemplate(id, request)
        call.respondSubscriptionMutation(result)
    }

    delete("/api/subscriptions/{id}/templates/{key}") {
        val session = call.requireWebUiSession(authService, auditService) ?: return@delete
        if (!call.requireHighRiskConfirmation(authService, session, call.receiveConfirmationPasswordOrEmpty(), auditService)) {
            return@delete
        }
        val id = call.parameters["id"].orEmpty()
        val key = call.parameters["key"].orEmpty()
        val result = subscriptionManagementFacade.deleteSubscriptionTemplate(id, key)
        call.respondSubscriptionMutation(result)
    }

    post("/api/subscriptions/{id}/templates/random") {
        val session = call.requireWebUiSession(authService, auditService) ?: return@post
        val id = call.parameters["id"].orEmpty()
        val request = call.receive<WebUiSubscriptionTemplateRandomRequestDto>()
        if (!call.requireHighRiskConfirmation(authService, session, request.confirmationPassword, auditService)) {
            return@post
        }
        val result = subscriptionManagementFacade.setSubscriptionTemplateRandom(id, request.enabled)
        call.respondSubscriptionMutation(result)
    }

    get("/api/subscriptions/{id}/atall") {
        call.requireWebUiSession(authService, auditService) ?: return@get
        val id = call.parameters["id"].orEmpty()
        call.respond(subscriptionManagementFacade.listSubscriptionAtAll(id))
    }

    post("/api/subscriptions/{id}/atall") {
        val session = call.requireWebUiSession(authService, auditService) ?: return@post
        val id = call.parameters["id"].orEmpty()
        val request = call.receive<WebUiSubscriptionAtAllSaveRequestDto>()
        if (!call.requireHighRiskConfirmation(authService, session, request.confirmationPassword, auditService)) {
            return@post
        }
        val result = subscriptionManagementFacade.saveSubscriptionAtAll(id, request.type, request.targetGroups)
        call.respondSubscriptionMutation(result)
    }

    delete("/api/subscriptions/{id}/atall/{key}") {
        val session = call.requireWebUiSession(authService, auditService) ?: return@delete
        if (!call.requireHighRiskConfirmation(authService, session, call.receiveConfirmationPasswordOrEmpty(), auditService)) {
            return@delete
        }
        val id = call.parameters["id"].orEmpty()
        val key = call.parameters["key"].orEmpty()
        val result = subscriptionManagementFacade.deleteSubscriptionAtAll(id, key)
        call.respondSubscriptionMutation(result)
    }

    get("/api/subscriptions/{id}/theme") {
        call.requireWebUiSession(authService, auditService) ?: return@get
        val id = call.parameters["id"].orEmpty()
        call.respond(subscriptionManagementFacade.readSubscriptionTheme(id))
    }

    post("/api/subscriptions/{id}/theme") {
        val session = call.requireWebUiSession(authService, auditService) ?: return@post
        val id = call.parameters["id"].orEmpty()
        val request = call.receive<WebUiSubscriptionThemeSaveRequestDto>()
        if (!call.requireHighRiskConfirmation(authService, session, request.confirmationPassword, auditService)) {
            return@post
        }
        val result = subscriptionManagementFacade.saveSubscriptionTheme(id, request.color, request.targetGroups)
        call.respondSubscriptionMutation(result)
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

/**
 * 订阅管理写操作使用 BadRequest 表达输入或业务校验失败，成功删除和新增都返回 OK。
 */
private suspend fun io.ktor.server.application.ApplicationCall.respondSubscriptionMutation(
    result: top.bilibili.webui.model.WebUiSubscriptionMutationResultDto,
) {
    val status = if (result.success) HttpStatusCode.OK else HttpStatusCode.BadRequest
    respond(status, result)
}

/**
 * DELETE 类写操作没有业务 DTO；缺少 JSON body 时返回空确认值，让统一确认 guard 产出 403。
 */
private suspend fun io.ktor.server.application.ApplicationCall.receiveConfirmationPasswordOrEmpty(): String {
    if (request.headers[HttpHeaders.ContentLength].orEmpty().toLongOrNull() == null ||
        request.headers[HttpHeaders.ContentLength].orEmpty().toLongOrNull() == 0L
    ) {
        return ""
    }
    return runCatching {
        receive<WebUiActionConfirmationRequestDto>().confirmationPassword
    }.getOrDefault("")
}
