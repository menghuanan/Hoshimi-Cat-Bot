package top.bilibili.webui.routes

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.http.content.staticFiles
import io.ktor.server.http.content.staticResources
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import top.bilibili.webui.auth.WebUiAuthService
import top.bilibili.webui.config.WebUiSettings
import java.io.File

/**
 * WebUI 静态路由只负责登录页、认证后壳页与静态资源分发，不在这里处理认证业务。
 */
fun Route.registerWebUiStaticRoutes(
    settings: WebUiSettings,
    authService: WebUiAuthService,
) {
    val externalStaticRoot = settings.staticDir.takeIf { it.isNotBlank() }?.let(::File)
    get("/") {
        val session = authService.resolveSession(call.extractWebUiToken())
        if (session == null || session.mustChangePassword) {
            call.respondRedirect("/login", permanent = false)
            return@get
        }
        call.respondManagedHtmlPage(
            externalStaticRoot = externalStaticRoot,
            externalFileNames = listOf("index.html"),
            bundledResourcePath = "webui/react/index.html",
        )
    }

    get("/login") {
        val session = authService.resolveSession(call.extractWebUiToken())
        if (session != null && !session.mustChangePassword) {
            call.respondRedirect("/", permanent = false)
            return@get
        }
        call.respondManagedHtmlPage(
            externalStaticRoot = externalStaticRoot,
            externalFileNames = listOf("login.html", "index.html"),
            bundledResourcePath = "webui/react/index.html",
        )
    }

    if (externalStaticRoot != null) {
        // 外部静态目录存在时优先服务 React 构建产物，便于前端独立调试而不改变鉴权边界。
        staticFiles("/assets", externalStaticRoot.resolve("assets"))
    } else {
        staticResources("/assets", "webui/react/assets")
    }
}

/**
 * HTML 页面统一在受控入口里选择外部文件或 bundled 资源，保证登录壳与主壳走同一套 React 入口。
 */
private suspend fun ApplicationCall.respondManagedHtmlPage(
    externalStaticRoot: File?,
    externalFileNames: List<String>,
    bundledResourcePath: String,
) {
    val externalFile = externalFileNames
        .asSequence()
        .mapNotNull { fileName -> externalStaticRoot?.resolve(fileName) }
        .firstOrNull { file -> file.isFile }
    if (externalFile?.isFile == true) {
        respondFile(externalFile)
        return
    }
    val content = loadBundledTextResource(bundledResourcePath)
    respondText(content, ContentType.Text.Html)
}

/**
 * 读取打包在 jar 内的 WebUI 文本资源；缺失时直接失败，避免静默返回半初始化页面。
 */
private fun loadBundledTextResource(resourcePath: String): String {
    val classLoader = Thread.currentThread().contextClassLoader ?: object {}.javaClass.classLoader
    val stream = classLoader.getResourceAsStream(resourcePath)
        ?: error("missing bundled WebUI resource: $resourcePath")
    return stream.bufferedReader(Charsets.UTF_8).use { reader ->
        reader.readText()
    }
}
