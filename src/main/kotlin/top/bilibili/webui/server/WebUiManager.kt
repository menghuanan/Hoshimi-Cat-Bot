package top.bilibili.webui.server

import io.ktor.serialization.kotlinx.json.json
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
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
import java.util.concurrent.CancellationException

/**
 * WebUI 管理器只拥有嵌入式服务器生命周期，并把本次启动时间传给日志面做会话裁切。
 */
class WebUiManager(
    private val settings: WebUiSettings,
    private val logWindowStartEpochMillis: Long = 0L,
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
                // 日志面按 Bot 本次启动时间裁切窗口，避免把上一轮进程残留拼进管理页。
                logFacade = WebUiLogFacade(startupEpochMillis = logWindowStartEpochMillis),
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
 * WebUI 应用模块只负责安装内容协商并把认证、静态页和只读 API 接到同一条受控路由树上；
 * 请求超时和请求体上限默认在 hardening 层收口，但可在测试里收紧以验证边界。
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
    requestTimeoutMillis: Long = WebUiRequestTimeoutMillis,
    requestBodyLimitBytes: Long = WebUiMaxRequestBodyBytes,
) {
    installWebUiHardening(settings, requestTimeoutMillis, requestBodyLimitBytes)
    install(ContentNegotiation) {
        json(json)
    }
    routing {
        installWebUiRequestBoundaries(requestBodyLimitBytes)
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

private const val WebUiRequestTimeoutMillis = 30_000L
private const val WebUiMaxRequestBodyBytes = 1_048_576L
private const val WebUiContentSecurityPolicy = "default-src 'self'; base-uri 'self'; frame-ancestors 'none'; object-src 'none'"

/**
 * WebUI 外围安全在进入路由前统一处理响应头、显式 Origin CORS、应用级超时和异常脱敏。
 */
private fun Application.installWebUiHardening(
    settings: WebUiSettings,
    requestTimeoutMillis: Long,
    requestBodyLimitBytes: Long,
) {
    intercept(ApplicationCallPipeline.Setup) {
        call.applyWebUiSecurityHeaders()
        if (!call.applyWebUiCors(settings)) {
            call.respond(HttpStatusCode.Forbidden)
            finish()
            return@intercept
        }
        if (call.request.httpMethod == HttpMethod.Options && call.request.headers[HttpHeaders.Origin] != null) {
            call.respond(HttpStatusCode.OK)
            finish()
            return@intercept
        }
        if (call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let { it > requestBodyLimitBytes } == true) {
            call.respond(HttpStatusCode.PayloadTooLarge, mapOf("message" to "request body too large"))
            finish()
            return@intercept
        }
        try {
            // Ktor 3.0.3 的 CIO 引擎只有连接空闲超时，没有现成的请求处理超时插件，因此在管线外层收口。
            withTimeout(requestTimeoutMillis) {
                proceed()
            }
        } catch (error: TimeoutCancellationException) {
            this@installWebUiHardening.environment.log.warn(
                "WebUI request timed out after {} ms: {}",
                requestTimeoutMillis,
                call.request.path(),
            )
            if (!call.response.isSent) {
                call.respond(HttpStatusCode.RequestTimeout, mapOf("message" to "request timed out"))
            }
            finish()
        } catch (error: PayloadTooLargeException) {
            this@installWebUiHardening.environment.log.warn(
                "WebUI request body exceeded the configured limit: {}",
                error.message,
            )
            if (!call.response.isSent) {
                call.respond(HttpStatusCode.PayloadTooLarge, mapOf("message" to "request body too large"))
            }
            finish()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            this@installWebUiHardening.environment.log.error("WebUI request failed: ${error.message}", error)
            if (!call.response.isSent) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "internal server error"))
            }
            finish()
        }
    }
}

/**
 * 路由根节点安装 Ktor 官方请求体边界插件，让超限 body 在进入具体路由前就被拒绝。
 */
private fun Route.installWebUiRequestBoundaries(requestBodyLimitBytes: Long) {
    install(RequestBodyLimit) {
        bodyLimit { _ -> requestBodyLimitBytes }
    }
}

/**
 * 安全响应头全部使用固定值，确保 HTML、API 和错误响应不被浏览器缓存或嵌入。
 */
private fun ApplicationCall.applyWebUiSecurityHeaders() {
    response.headers.appendIfAbsent("Content-Security-Policy", WebUiContentSecurityPolicy)
    response.headers.appendIfAbsent("X-Frame-Options", "DENY")
    response.headers.appendIfAbsent("X-Content-Type-Options", "nosniff")
    response.headers.appendIfAbsent("Referrer-Policy", "no-referrer")
    response.headers.appendIfAbsent("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
    response.headers.appendIfAbsent(HttpHeaders.CacheControl, "no-store")
}

/**
 * CORS 只回显显式允许的本机 Origin；无 Origin 的普通同源请求继续按浏览器默认模型处理。
 */
private fun ApplicationCall.applyWebUiCors(settings: WebUiSettings): Boolean {
    response.headers.appendIfAbsent(HttpHeaders.Vary, HttpHeaders.Origin)
    val origin = request.headers[HttpHeaders.Origin] ?: return true
    // 0.0.0.0 允许浏览器按实际访问地址发起同源请求，避免内网 IP 或主机名被误当成跨域。
    if (origin !in allowedWebUiOrigins(settings) && origin != currentRequestOrigin()) {
        return false
    }
    response.headers.appendIfAbsent(HttpHeaders.AccessControlAllowOrigin, origin)
    response.headers.appendIfAbsent(HttpHeaders.AccessControlAllowCredentials, "true")
    response.headers.appendIfAbsent(HttpHeaders.AccessControlAllowMethods, "GET, POST, DELETE, OPTIONS")
    response.headers.appendIfAbsent(HttpHeaders.AccessControlAllowHeaders, "Content-Type, X-CSRF-Token")
    return true
}

/**
 * 允许列表从当前监听地址派生；0.0.0.0 仍不使用通配符，实际访问地址由同源回退单独放行。
 */
private fun allowedWebUiOrigins(settings: WebUiSettings): Set<String> {
    val hosts = linkedSetOf(settings.host.trim())
    if (settings.host == "127.0.0.1" || settings.host.equals("localhost", ignoreCase = true) || settings.host == "0.0.0.0") {
        hosts += "127.0.0.1"
        hosts += "localhost"
    }
    return hosts
        .filter { it.isNotBlank() && it != "0.0.0.0" }
        .flatMap { host -> listOf("http://$host:${settings.port}", "https://$host:${settings.port}") }
        .toSet()
}

/**
 * 同源回退只认当前请求实际使用的 Host，确保用 NAS 内网 IP 或主机名访问时静态资源仍可加载。
 */
private fun ApplicationCall.currentRequestOrigin(): String? {
    val host = request.header(HttpHeaders.Host)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: return null
    return "http://$host"
}

/**
 * Header 写入集中做去重，避免 route 层已有 no-store 等头时触发重复响应值。
 */
private fun io.ktor.server.response.ResponseHeaders.appendIfAbsent(name: String, value: String) {
    if (!contains(name)) {
        append(name, value, false)
    }
}
