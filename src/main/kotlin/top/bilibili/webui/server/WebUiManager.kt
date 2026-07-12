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
import top.bilibili.core.BiliBiliBot
import top.bilibili.utils.json
import top.bilibili.webui.auth.WebUiAuthService
import top.bilibili.webui.auth.WebUiCredentialStore
import top.bilibili.webui.auth.WebUiTokenService
import top.bilibili.webui.config.WebUiSettings
import top.bilibili.webui.routes.registerWebUiActionRoutes
import top.bilibili.webui.routes.registerWebUiAuthRoutes
import top.bilibili.webui.routes.registerWebUiApiRoutes
import top.bilibili.webui.routes.registerWebUiBiliLoginRoutes
import top.bilibili.webui.routes.registerWebUiLogRoutes
import top.bilibili.webui.routes.registerWebUiStaticRoutes
import top.bilibili.webui.service.WebUiActionFacade
import top.bilibili.webui.service.WebUiAuditService
import top.bilibili.webui.service.WebUiBiliLoginFacade
import top.bilibili.webui.service.WebUiConfigFacade
import top.bilibili.webui.service.WebUiConfigHotReloadCoordinator
import top.bilibili.webui.service.WebUiConfigWriteFacade
import top.bilibili.webui.service.WebUiLogFacade
import top.bilibili.webui.service.WebUiRuntimeFacade
import top.bilibili.webui.service.WebUiSubscriptionManagementFacade
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CancellationException

/**
 * WebUI 管理器只拥有嵌入式服务器生命周期，并把本次启动时间传给日志面做会话裁切。
 */
class WebUiManager(
    internal val settings: WebUiSettings,
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
        val configWriteFacade = WebUiConfigWriteFacade(
            configFacade = configFacade,
        )
        val configHotReloadCoordinator = BiliBiliBot.requireWebUiConfigHotReloadCoordinator()
        ensureEndpointAvailable()
        val startedServer = embeddedServer(CIO, host = settings.host, port = settings.port) {
            installWebUiModule(
                settings = settings,
                authService = authService,
                runtimeFacade = WebUiRuntimeFacade(),
                configFacade = configFacade,
                configWriteFacade = configWriteFacade,
                configHotReloadCoordinator = configHotReloadCoordinator,
                subscriptionManagementFacade = WebUiSubscriptionManagementFacade(
                    submitPersistedDataReload = {
                        configHotReloadCoordinator.submitPersistedBiliDataReload()
                    },
                ),
                // 日志面按 Bot 本次启动时间裁切窗口，避免把上一轮进程残留拼进管理页。
                logFacade = WebUiLogFacade(startupEpochMillis = logWindowStartEpochMillis),
                actionFacade = WebUiActionFacade(),
                biliLoginFacade = WebUiBiliLoginFacade(),
                auditService = WebUiAuditService(),
            )
        }
        startedServer.start(wait = false)
        server = startedServer
        logger.info("WebUI 已启动: http://${settings.host}:${settings.port}/")
    }

    /**
     * Ktor CIO 的 bind 失败可能在后台协程抛出；启动前先做同步端口探测，让热重载 job 能立即失败并回滚。
     */
    private fun ensureEndpointAvailable() {
        runCatching {
            ServerSocket().use { socket ->
                socket.reuseAddress = false
                socket.bind(InetSocketAddress(settings.host, settings.port))
            }
        }.getOrElse { throwable ->
            throw IllegalStateException("WebUI endpoint is unavailable: ${settings.host}:${settings.port}", throwable)
        }
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

    /**
     * 仅比较会影响 WebUI 运行面的设置，并返回前端可能需要跳转的新地址。
     */
    fun planReload(nextSettings: WebUiSettings): WebUiReloadPlan {
        if (settings == nextSettings) {
            return WebUiReloadPlan(restartRequired = false, message = "webui unchanged")
        }
        val redirect = if (settings.host != nextSettings.host || settings.port != nextSettings.port) {
            "http://${nextSettings.host}:${nextSettings.port}/"
        } else {
            null
        }
        return WebUiReloadPlan(
            restartRequired = true,
            webUiRedirectUrl = redirect,
            message = "webui restart scheduled",
        )
    }
}

/**
 * WebUI 热重载计划把“当前响应先返回”和“监听端口稍后切换”拆开，避免保存请求被自己关闭。
 */
data class WebUiReloadPlan(
    val restartRequired: Boolean,
    val webUiRedirectUrl: String? = null,
    val message: String = "",
    val scheduleToken: String? = null,
    // 运行期设置只留给 Bot 延迟调度使用，不作为浏览器可见配置契约返回。
    val nextSettings: WebUiSettings? = null,
)

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
    configHotReloadCoordinator: WebUiConfigHotReloadCoordinator = WebUiConfigHotReloadCoordinator.fromConfigWriteFacade(configWriteFacade),
    subscriptionManagementFacade: WebUiSubscriptionManagementFacade = WebUiSubscriptionManagementFacade(),
    logFacade: WebUiLogFacade,
    actionFacade: WebUiActionFacade,
    biliLoginFacade: WebUiBiliLoginFacade = WebUiBiliLoginFacade(),
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
            configHotReloadCoordinator,
            subscriptionManagementFacade,
            auditService,
        )
        registerWebUiLogRoutes(authService, logFacade, auditService)
        registerWebUiActionRoutes(authService, actionFacade, auditService)
        registerWebUiBiliLoginRoutes(authService, biliLoginFacade, auditService)
    }
}

private const val WebUiRequestTimeoutMillis = 30_000L
private const val WebUiMaxRequestBodyBytes = 1_048_576L
private const val WebUiContentSecurityPolicy = "default-src 'self'; img-src 'self' data:; base-uri 'self'; frame-ancestors 'none'; object-src 'none'"

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
    if (origin !in allowedWebUiOrigins(settings) && origin !in currentRequestOrigins()) {
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
 * 同源回退从当前请求和可信代理常用转发头推导入口地址，覆盖端口映射、FRP、Cloudflare Tunnel 和内网组网域名。
 */
private fun ApplicationCall.currentRequestOrigins(): Set<String> {
    val hosts = linkedSetOf<String>()
    val schemes = linkedSetOf<String>()
    forwardedHeaderParts().let { parts ->
        parts["host"]?.toOriginHost()?.let(hosts::add)
        parts["proto"]?.toOriginScheme()?.let(schemes::add)
    }
    // 反向代理通常使用 X-Forwarded-* 表达公网入口，Host 则覆盖端口映射和 Tailscale/Zerotier 直连。
    request.header("X-Forwarded-Host")?.firstForwardedValue()?.toOriginHost()?.let(hosts::add)
    request.header("X-Forwarded-Proto")?.firstForwardedValue()?.toOriginScheme()?.let(schemes::add)
    request.header(HttpHeaders.Host)?.toOriginHost()?.let(hosts::add)
    if (schemes.isEmpty()) {
        schemes += "http"
    }
    return hosts.flatMap { host -> schemes.map { scheme -> "$scheme://$host" } }.toSet()
}

/**
 * RFC Forwarded 只取最靠近客户端的第一段，避免多级代理链里的内部 hop 覆盖公网入口。
 */
private fun ApplicationCall.forwardedHeaderParts(): Map<String, String> {
    val firstForwarded = request.header(HttpHeaders.Forwarded)?.firstForwardedValue() ?: return emptyMap()
    return firstForwarded
        .split(';')
        .mapNotNull { part ->
            val key = part.substringBefore('=', missingDelimiterValue = "").trim().lowercase()
            val value = part.substringAfter('=', missingDelimiterValue = "").trim().trim('"')
            if (key.isBlank() || value.isBlank()) null else key to value
        }
        .toMap()
}

/**
 * 多级代理头按惯例以逗号分隔，WebUI 只使用第一个外部入口值。
 */
private fun String.firstForwardedValue(): String {
    return substringBefore(',').trim()
}

/**
 * Origin 主机只接受非空值并剥离代理可能保留的引号，端口保留给同源判断。
 */
private fun String.toOriginHost(): String? {
    return trim().trim('"').takeIf { it.isNotBlank() }
}

/**
 * Origin scheme 只允许浏览器 WebUI 会用到的 http/https，其他值不参与 CORS 放行。
 */
private fun String.toOriginScheme(): String? {
    return trim().trim('"').lowercase().takeIf { it == "http" || it == "https" }
}

/**
 * Header 写入集中做去重，避免 route 层已有 no-store 等头时触发重复响应值。
 */
private fun io.ktor.server.response.ResponseHeaders.appendIfAbsent(name: String, value: String) {
    if (!contains(name)) {
        append(name, value, false)
    }
}
