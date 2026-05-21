package top.bilibili.webui.server

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory
import top.bilibili.utils.json
import top.bilibili.webui.auth.WebUiAuthService
import top.bilibili.webui.auth.WebUiCredentialStore
import top.bilibili.webui.auth.WebUiTokenService
import top.bilibili.webui.config.WebUiSettings
import top.bilibili.webui.routes.registerWebUiActionRoutes
import top.bilibili.webui.routes.registerWebUiAuthRoutes
import top.bilibili.webui.routes.registerWebUiApiRoutes
import top.bilibili.webui.routes.registerWebUiLogRoutes
import top.bilibili.webui.routes.registerWebUiStaticRoutes
import top.bilibili.webui.service.WebUiActionFacade
import top.bilibili.webui.service.WebUiAuditService
import top.bilibili.webui.service.WebUiConfigFacade
import top.bilibili.webui.service.WebUiConfigWriteFacade
import top.bilibili.webui.service.WebUiLogFacade
import top.bilibili.webui.service.WebUiRuntimeFacade
import top.bilibili.webui.service.WebUiSubscriptionManagementFacade

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

        val credentialStore = WebUiCredentialStore(settings.credentialStateFile)
        val tokenService = WebUiTokenService(settings.tokenTtlSeconds)
        val authService = WebUiAuthService(
            credentialStore = credentialStore,
            tokenService = tokenService,
        )
        val bootstrap = authService.bootstrapCredentials()
        if (bootstrap.initialPassword != null) {
            // 初始密码只在本地启动期输出一次，方便管理员完成首次登录和强制改密。
            logger.warn(
                "WebUI 初始密码已生成，请立即登录并修改密码，密码={}",
                bootstrap.initialPassword,
            )
        }

        // 服务器只安装最小 JSON 与路由能力，为后续管理接口保留清晰边界。
        val configFacade = WebUiConfigFacade()
        val startedServer = embeddedServer(CIO, host = settings.host, port = settings.port) {
            installWebUiModule(
                settings = settings,
                authService = authService,
                runtimeFacade = WebUiRuntimeFacade(),
                configFacade = configFacade,
                configWriteFacade = WebUiConfigWriteFacade(
                    configFacade = configFacade,
                ),
                subscriptionManagementFacade = WebUiSubscriptionManagementFacade(),
                logFacade = WebUiLogFacade(),
                actionFacade = WebUiActionFacade(),
                auditService = WebUiAuditService(),
            )
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

/**
 * WebUI 应用模块只负责安装内容协商并把认证、静态页和只读 API 接到同一条受控路由树上。
 */
fun Application.installWebUiModule(
    settings: WebUiSettings,
    authService: WebUiAuthService,
    runtimeFacade: WebUiRuntimeFacade,
    configFacade: WebUiConfigFacade,
    configWriteFacade: WebUiConfigWriteFacade,
    subscriptionManagementFacade: WebUiSubscriptionManagementFacade = WebUiSubscriptionManagementFacade(),
    logFacade: WebUiLogFacade,
    actionFacade: WebUiActionFacade,
    auditService: WebUiAuditService,
) {
    install(ContentNegotiation) {
        json(json)
    }
    routing {
        registerWebUiStaticRoutes(settings, authService)
        registerWebUiAuthRoutes(authService, auditService)
        registerWebUiApiRoutes(
            authService,
            runtimeFacade,
            configFacade,
            configWriteFacade,
            subscriptionManagementFacade,
            auditService,
        )
        registerWebUiLogRoutes(authService, logFacade, auditService)
        registerWebUiActionRoutes(authService, actionFacade, auditService)
    }
}
