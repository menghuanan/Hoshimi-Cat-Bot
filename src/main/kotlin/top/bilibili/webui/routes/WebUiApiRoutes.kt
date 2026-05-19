package top.bilibili.webui.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import top.bilibili.webui.auth.WebUiAuthService
import top.bilibili.webui.service.WebUiConfigFacade
import top.bilibili.webui.service.WebUiRuntimeFacade

/**
 * WebUI API 路由只暴露认证后的只读管理接口，不承载业务服务实现。
 */
fun Route.registerWebUiApiRoutes(
    authService: WebUiAuthService,
    runtimeFacade: WebUiRuntimeFacade,
    configFacade: WebUiConfigFacade,
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
        call.requireWebUiSession(authService) ?: return@get
        call.respond(runtimeFacade.readSummary())
    }

    get("/api/config/bili-config") {
        call.requireWebUiSession(authService) ?: return@get
        call.respond(configFacade.readBiliConfig())
    }

    get("/api/config/bili-data") {
        call.requireWebUiSession(authService) ?: return@get
        call.respond(configFacade.readBiliData())
    }

    get("/api/config/bot") {
        call.requireWebUiSession(authService) ?: return@get
        call.respond(configFacade.readBotConfig())
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
