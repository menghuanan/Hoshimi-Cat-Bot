package top.bilibili.webui

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebUiFoundationSourceRegressionTest {
    /**
     * 统一按 UTF-8 读取源码，避免 source regression 受宿主默认编码影响。
     */
    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

    /**
     * React 源码 bundle 汇总前端页面、hook、API 和元数据，替代已删除的 plain runtime 对照资源。
     */
    private fun readReactSourceBundle(): String {
        val sourceRoot = Path.of("webui-frontend/src")
        return Files.walk(sourceRoot).use { paths ->
            paths
                .filter { path -> Files.isRegularFile(path) }
                .filter { path -> path.toString().endsWith(".ts") || path.toString().endsWith(".tsx") || path.toString().endsWith(".css") }
                .map { path -> read(path.toString()) }
                .toList()
                .joinToString("\n")
        }
    }

    @Test
    fun `webui config should stay disabled by default and remain part of bot runtime config`() {
        val configSource = read("src/main/kotlin/top/bilibili/config/NapCatConfig.kt")
        val webUiConfigSource = read("src/main/kotlin/top/bilibili/webui/config/WebUiConfig.kt")

        assertTrue(configSource.contains("val webui: WebUiConfig = WebUiConfig()"))
        assertTrue(webUiConfigSource.contains("""val enabled: Boolean = false"""))
        assertTrue(webUiConfigSource.contains("""val host: String = "127.0.0.1""""))
        assertTrue(webUiConfigSource.contains("""val port: Int = 18080"""))
        assertTrue(webUiConfigSource.contains("""@SerialName("static_dir")"""))
    }

    @Test
    fun `webui routes should reserve root assets and api boundaries`() {
        val apiRoutes = read("src/main/kotlin/top/bilibili/webui/routes/WebUiApiRoutes.kt")
        val staticRoutes = read("src/main/kotlin/top/bilibili/webui/routes/WebUiStaticRoutes.kt")
        val authRoutes = read("src/main/kotlin/top/bilibili/webui/routes/WebUiAuthRoutes.kt")
        val runtimeDtos = read("src/main/kotlin/top/bilibili/webui/model/WebUiRuntimeDtos.kt")
        val runtimeFacade = read("src/main/kotlin/top/bilibili/webui/service/WebUiRuntimeFacade.kt")

        assertTrue(apiRoutes.contains("/api/runtime/summary"))
        assertTrue(apiRoutes.contains("/api/config/bili-config"))
        assertTrue(apiRoutes.contains("/api/subscriptions"))
        assertTrue(runtimeDtos.contains("""val appVersion: String"""))
        assertTrue(runtimeFacade.contains("""appVersion = appVersionProvider()"""))
        assertTrue(authRoutes.contains("/api/auth/login"))
        assertTrue(authRoutes.contains("/api/auth/change-password"))
        assertTrue(staticRoutes.contains("""get("/login")"""))
        assertTrue(staticRoutes.contains("webui/react/index.html"))
        assertTrue(staticRoutes.contains("webui/react/assets"))
        assertTrue(staticRoutes.contains("reactShellRoutes"))
        assertTrue(staticRoutes.contains(""""/""""))
        assertTrue(staticRoutes.contains("settings"))
        assertTrue(staticRoutes.contains("subscriptions"))
        assertTrue(staticRoutes.contains("logs"))
    }

    /**
     * React 迁移必须由独立前端工程产出 WebUI 静态资源，避免继续只维护旧 plain script shell。
     */
    @Test
    fun `webui react frontend workspace should be wired into bundled assets`() {
        val buildSource = read("build.gradle.kts")
        val reactShellPath = Path.of("src/main/resources/webui/react/index.html")

        assertTrue(buildSource.contains("webui-frontend"))
        assertTrue(buildSource.contains("buildWebUiFrontend"))
        assertTrue(Files.exists(reactShellPath))

        val reactShell = read(reactShellPath.toString())
        assertTrue(reactShell.contains("webui-frontend"))
        assertTrue(reactShell.contains("./assets/app.js"))
        assertTrue(reactShell.contains("./assets/app.css"))

        val staticRoutes = read("src/main/kotlin/top/bilibili/webui/routes/WebUiStaticRoutes.kt")
        assertTrue(staticRoutes.contains("webui/react/index.html"))
        assertTrue(staticRoutes.contains("webui/react/assets"))
    }

    /**
     * React 运行时和旧 plain baseline 分开断言，避免最终壳层来源和对照资源互相覆盖。
     * 删除旧 baseline 后，这个检查改为证明 React 是唯一 bundled runtime。
     */
    @Test
    fun `webui should keep only react runtime resources as bundled webui shell`() {
        val reactShellPath = Path.of("src/main/resources/webui/react/index.html")
        val reactScriptPath = Path.of("src/main/resources/webui/react/assets/app.js")
        val reactStylePath = Path.of("src/main/resources/webui/react/assets/app.css")
        val plainShellPath = Path.of("src/main/resources/webui/index.html")
        val plainLoginPath = Path.of("src/main/resources/webui/login.html")
        val plainAssetsPath = Path.of("src/main/resources/webui/assets")

        assertTrue(Files.exists(reactShellPath))
        assertTrue(Files.exists(reactScriptPath))
        assertTrue(Files.exists(reactStylePath))
        assertFalse(Files.exists(plainShellPath))
        assertFalse(Files.exists(plainLoginPath))
        assertFalse(Files.exists(plainAssetsPath))

        val reactShell = read(reactShellPath.toString())
        val reactScript = read(reactScriptPath.toString())

        assertTrue(reactShell.contains("""id="root""""))
        assertTrue(reactShell.contains("./assets/app.js"))
        assertFalse(reactScript.contains("window.confirm("))
        assertFalse(reactScript.contains("window.prompt("))
        assertFalse(reactScript.contains("window.alert("))
        assertFalse(Regex("""\bnew\s+Notification\b|\bNotification\.""").containsMatchIn(reactScript))
    }

    @Test
    fun `webui server and route logic should stay out of core lifecycle wiring`() {
        val botSource = read("src/main/kotlin/top/bilibili/core/BiliBiliBot.kt")
        val managerSource = read("src/main/kotlin/top/bilibili/webui/server/WebUiManager.kt")

        assertTrue(botSource.contains("WebUiManager"))
        assertFalse(botSource.contains("embeddedServer("))
        assertFalse(botSource.contains("/api/health"))
        assertTrue(managerSource.contains("embeddedServer("))
    }

    @Test
    fun `react source should expose dashboard settings subscriptions logs and auth controls`() {
        val reactSource = readReactSourceBundle()

        assertTrue(reactSource.contains("DashboardPage"))
        assertTrue(reactSource.contains("SettingsPage"))
        assertTrue(reactSource.contains("SubscriptionsPage"))
        assertTrue(reactSource.contains("LogsPage"))
        assertTrue(reactSource.contains("LoginPage"))
        assertTrue(reactSource.contains("运行概览"))
        assertTrue(reactSource.contains("系统配置"))
        assertTrue(reactSource.contains("订阅管理"))
        assertTrue(reactSource.contains("日志"))
        assertTrue(reactSource.contains("Admin"))
        assertTrue(reactSource.contains("修改密码"))
        assertTrue(reactSource.contains("requestHighRiskConfirmation"))
        assertFalse(reactSource.contains("requestCenteredConfirmation"))
        assertTrue(reactSource.contains("proxyUpdateMode"))
        assertTrue(reactSource.contains("randomEnabled"))
        assertTrue(reactSource.contains("buildLogClearPayload"))
    }

    @Test
    fun `react router should support direct path refresh and hash navigation`() {
        val routerSource = read("webui-frontend/src/router/webuiRouter.ts")
        val staticRoutes = read("src/main/kotlin/top/bilibili/webui/routes/WebUiStaticRoutes.kt")

        assertTrue(routerSource.contains("directPath"))
        assertTrue(routerSource.contains("'/settings'"))
        assertTrue(routerSource.contains("'/subscriptions'"))
        assertTrue(routerSource.contains("'/logs'"))
        assertTrue(routerSource.contains("hash.replace"))
        assertTrue(staticRoutes.contains(""""/settings""""))
        assertTrue(staticRoutes.contains(""""/subscriptions""""))
        assertTrue(staticRoutes.contains(""""/logs""""))
    }

    /**
     * React 源码不得重新引入浏览器原生确认、提示或通知 API。
     */
    @Test
    fun `react production source should avoid native browser dialogs and notifications`() {
        val reactSource = readReactSourceBundle()

        assertFalse(reactSource.contains("window.confirm("))
        assertFalse(reactSource.contains("window.prompt("))
        assertFalse(reactSource.contains("window.alert("))
        assertFalse(Regex("""\bnew\s+Notification\b|\bNotification\.""").containsMatchIn(reactSource))
    }
}
