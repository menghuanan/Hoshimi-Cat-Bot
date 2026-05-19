package top.bilibili.webui.routes

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

/**
 * WebUI API 路由只暴露管理面的只读占位接口，不承载业务服务实现。
 */
fun Route.registerWebUiApiRoutes() {
    get("/api/health") {
        call.respond(
            WebUiHealthResponse(
                service = "dynamic-bot-webui",
                phase = "foundation",
                status = "ok",
            ),
        )
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
