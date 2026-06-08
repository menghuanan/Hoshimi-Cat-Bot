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
import top.bilibili.webui.model.WebUiConfigBatchSaveRequestDto
import top.bilibili.webui.model.WebUiConfigHotReloadJobDto
import top.bilibili.webui.model.WebUiConfigSaveResultDto
import top.bilibili.webui.model.WebUiRecommendedAction
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
import top.bilibili.webui.service.WebUiConfigHotReloadCoordinator
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
    configHotReloadCoordinator: WebUiConfigHotReloadCoordinator,
    subscriptionManagementFacade: WebUiSubscriptionManagementFacade,
    auditService: WebUiAuditService,
) {
    get("/api/health") {
        call.respond(
            WebUiHealthResponse(
                service = "hoshimi-cat-bot-webui",
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
        // 旧单文件 URL 仍可用，但写入必须进入热重载协调器，避免前端维护第二套保存状态机。
        call.respondAcceptedHotReloadJob(
            configHotReloadCoordinator.submitAuditedConfigSave(
                request = WebUiConfigBatchSaveRequestDto(biliConfig = request),
                auditService = auditService,
                detailSummary = "path=/api/config/bili-config",
            ),
            configHotReloadCoordinator,
        )
    }

    post("/api/config/bili-data") {
        val session = call.requireWebUiSession(authService, auditService) ?: return@post
        val request = call.receive<WebUiBiliDataWriteRequestDto>()
        if (!call.requireHighRiskConfirmation(authService, session, request.confirmationPassword, auditService)) {
            return@post
        }
        // 单文件 BiliData 保存也复用队列，保证链接解析黑名单和订阅刷新不会并发应用。
        call.respondAcceptedHotReloadJob(
            configHotReloadCoordinator.submitAuditedConfigSave(
                request = WebUiConfigBatchSaveRequestDto(biliData = request),
                auditService = auditService,
                detailSummary = "path=/api/config/bili-data",
            ),
            configHotReloadCoordinator,
        )
    }

    post("/api/config/bot") {
        val session = call.requireWebUiSession(authService, auditService) ?: return@post
        val request = call.receive<WebUiBotConfigWriteRequestDto>()
        if (!call.requireHighRiskConfirmation(authService, session, request.confirmationPassword, auditService)) {
            return@post
        }
        // bot.yml 可能触发平台和 WebUI 运行面变更，因此兼容路由也只返回 job 快照。
        call.respondAcceptedHotReloadJob(
            configHotReloadCoordinator.submitAuditedConfigSave(
                request = WebUiConfigBatchSaveRequestDto(botConfig = request),
                auditService = auditService,
                detailSummary = "path=/api/config/bot",
            ),
            configHotReloadCoordinator,
        )
    }

    post("/api/config/save-batch") {
        val session = call.requireWebUiSession(authService, auditService) ?: return@post
        val request = call.receive<WebUiConfigBatchSaveRequestDto>()
        if (!call.requireHighRiskConfirmation(authService, session, request.confirmationPasswordForBatch(), auditService)) {
            return@post
        }
        val job = configHotReloadCoordinator.submitAuditedConfigSave(
            request = request,
            auditService = auditService,
            detailSummary = "path=/api/config/save-batch",
        )
        call.respondAcceptedHotReloadJob(job, configHotReloadCoordinator)
    }

    get("/api/config/save-jobs/{jobId}") {
        call.requireWebUiSession(authService, auditService) ?: return@get
        val jobId = call.parameters["jobId"].orEmpty()
        val job = configHotReloadCoordinator.readJob(jobId)
        if (job == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("message" to "job not found"))
        } else {
            call.respond(job)
        }
    }
}

/**
 * 配置保存路由提交热重载任务时注册完成审计，确保队列异步失败和成功都能留下 config-save 记录。
 */
private fun WebUiConfigHotReloadCoordinator.submitAuditedConfigSave(
    request: WebUiConfigBatchSaveRequestDto,
    auditService: WebUiAuditService,
    detailSummary: String,
): WebUiConfigHotReloadJobDto {
    return submit(request) { job ->
        auditConfigSaveJob(job, auditService, detailSummary)
    }
}

/**
 * 保存 job 的逐文件结果直接映射为审计记录；无 outcome 的异常路径按文件范围合成失败结果。
 */
private fun auditConfigSaveJob(
    job: WebUiConfigHotReloadJobDto,
    auditService: WebUiAuditService,
    detailSummary: String,
) {
    val outcomes = job.outcomes.ifEmpty {
        job.files.map { file ->
            top.bilibili.webui.model.WebUiConfigFileSaveOutcomeDto(
                file = file,
                result = WebUiConfigSaveResultDto(
                    success = false,
                    persisted = false,
                    conflictDetected = false,
                    validationErrors = emptyList(),
                    effectiveLevel = WebUiSaveEffectLevel.REJECTED_PERSISTENCE,
                    recommendedAction = WebUiRecommendedAction.RETRY_SAVE,
                    snapshotToken = "",
                    message = job.message,
                ),
            )
        }
    }
    outcomes.forEach { outcome ->
        auditService.recordConfigSave(
            sourceFile = outcome.file.auditSourceFileName(),
            result = outcome.result,
            detailSummary = "$detailSummary jobId=${job.jobId} phase=${job.phase} message=${job.message}",
        )
    }
}

/**
 * 审计目标沿用真实配置文件名，避免把内部 enum 名称暴露到运维日志里。
 */
private fun top.bilibili.webui.model.WebUiConfigFileKind.auditSourceFileName(): String {
    return when (this) {
        top.bilibili.webui.model.WebUiConfigFileKind.BILI_CONFIG -> "BiliConfig.yml"
        top.bilibili.webui.model.WebUiConfigFileKind.BILI_DATA -> "BiliData.yml"
        top.bilibili.webui.model.WebUiConfigFileKind.BOT_CONFIG -> "bot.yml"
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
 * 保存入口统一返回热重载 job DTO；worker 若已同步完成则优先返回最新快照。
 */
private suspend fun io.ktor.server.application.ApplicationCall.respondAcceptedHotReloadJob(
    job: WebUiConfigHotReloadJobDto,
    coordinator: WebUiConfigHotReloadCoordinator,
) {
    respond(HttpStatusCode.Accepted, coordinator.readJob(job.jobId) ?: job)
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
 * 批量保存只允许一个确认密码；前端必须把同一次点击的密码同步写入全部子请求。
 */
private fun WebUiConfigBatchSaveRequestDto.confirmationPasswordForBatch(): String {
    return biliConfig?.confirmationPassword
        ?: biliData?.confirmationPassword
        ?: botConfig?.confirmationPassword
        ?: ""
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
