package top.bilibili.webui.routes

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.http.content.staticFiles
import io.ktor.server.http.content.staticResources
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import top.bilibili.webui.config.WebUiSettings
import java.io.File

/**
 * WebUI 静态路由只负责页面与资源分发；页面本身仍保持纯占位状态。
 */
fun Route.registerWebUiStaticRoutes(settings: WebUiSettings) {
    val externalStaticRoot = settings.staticDir.takeIf { it.isNotBlank() }?.let(::File)
    if (externalStaticRoot != null) {
        // 外部静态目录存在时优先服务该目录，便于后续前端独立调试而不改变路由边界。
        get("/") {
            call.respondFile(externalStaticRoot.resolve("index.html"))
        }
        staticFiles("/assets", externalStaticRoot.resolve("assets"))
        return
    }

    get("/") {
        call.respondBundledTextResource("webui/index.html", ContentType.Text.Html)
    }
    staticResources("/assets", "webui/assets")
}

/**
 * 统一以 UTF-8 响应内置文本资源，确保占位页在不同宿主编码下保持稳定。
 */
private suspend fun ApplicationCall.respondBundledTextResource(
    resourcePath: String,
    contentType: ContentType,
) {
    val content = loadBundledTextResource(resourcePath)
    respondText(content, contentType)
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
