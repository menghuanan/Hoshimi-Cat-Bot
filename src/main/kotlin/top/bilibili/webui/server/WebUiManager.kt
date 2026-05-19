package top.bilibili.webui.server

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory
import top.bilibili.utils.json
import top.bilibili.webui.config.WebUiSettings
import top.bilibili.webui.routes.registerWebUiApiRoutes
import top.bilibili.webui.routes.registerWebUiStaticRoutes

/**
 * WebUI 管理器只拥有嵌入式服务器生命周期，不承载业务编排或配置写回职责。
 */
class WebUiManager(
    private val settings: WebUiSettings,
) {
    private val logger = LoggerFactory.getLogger(WebUiManager::class.java)
    private var server: EmbeddedServer<*, *>? = null

    /**
     * 启动 WebUI 服务器；重复调用会复用现有实例，避免在同一进程内重复占用端口。
     */
    fun start() {
        if (server != null) {
            logger.info("WebUI 已在运行，忽略重复启动")
            return
        }

        // 服务器只安装最小 JSON 与路由能力，为后续管理接口保留清晰边界。
        val startedServer = embeddedServer(CIO, host = settings.host, port = settings.port) {
            install(ContentNegotiation) {
                json(json)
            }
            routing {
                registerWebUiStaticRoutes(settings)
                registerWebUiApiRoutes()
            }
        }
        startedServer.start(wait = false)
        server = startedServer
        logger.info("WebUI 已启动: http://${settings.host}:${settings.port}/")
    }

    /**
     * 停止 WebUI 服务器，并释放管理面占用的监听端口。
     */
    fun stop(gracePeriodMillis: Long = 1_000, timeoutMillis: Long = 3_000) {
        val runningServer = server ?: return
        try {
            runningServer.stop(gracePeriodMillis, timeoutMillis)
            logger.info("WebUI 已停止")
        } finally {
            server = null
        }
    }
}
