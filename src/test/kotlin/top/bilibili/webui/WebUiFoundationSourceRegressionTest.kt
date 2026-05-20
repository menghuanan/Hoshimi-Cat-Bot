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

        assertTrue(apiRoutes.contains("/api/runtime/summary"))
        assertTrue(apiRoutes.contains("/api/config/bili-config"))
        assertTrue(authRoutes.contains("/api/auth/login"))
        assertTrue(authRoutes.contains("/api/auth/change-password"))
        assertTrue(staticRoutes.contains("""get("/")"""))
        assertTrue(staticRoutes.contains("""get("/login")"""))
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
    fun `frontend shell should expose the static dashboard sections`() {
        val loginPage = read("src/main/resources/webui/login.html")
        val shellPage = read("src/main/resources/webui/index.html")
        val shellScript = read("src/main/resources/webui/assets/app.js")
        val authScript = read("src/main/resources/webui/assets/auth.js")

        assertTrue(loginPage.contains("id=\"login-form\""))
        assertTrue(loginPage.contains("id=\"change-password-form\""))
        assertTrue(shellPage.contains("data-nav-target=\"home\""))
        assertTrue(shellPage.contains("data-nav-target=\"settings\""))
        assertTrue(shellPage.contains("data-nav-target=\"features\""))
        assertTrue(shellPage.contains("data-nav-target=\"subscriptions\""))
        assertTrue(shellPage.contains("data-nav-target=\"logs\""))
        assertTrue(shellPage.contains("class=\"metric-grid\""))
        assertTrue(shellPage.contains("class=\"dashboard-grid\""))
        assertTrue(shellPage.contains("class=\"config-grid\""))
        assertTrue(shellPage.contains("class=\"feature-grid\""))
        assertTrue(shellPage.contains("class=\"subscription-grid\""))
        assertTrue(shellPage.contains("class=\"log-list\""))
        assertTrue(shellPage.contains("Bot 运行状态"))
        assertTrue(shellPage.contains("系统配置"))
        assertTrue(shellPage.contains("功能开关"))
        assertTrue(shellPage.contains("订阅管理"))
        assertTrue(shellPage.contains("日志"))
        assertTrue(shellPage.contains("""data-runtime-field="startedAt""""))
        assertTrue(shellPage.contains("""data-runtime-field="runtimeDuration""""))
        assertTrue(shellPage.contains("""data-runtime-field="systemTime""""))
        assertTrue(shellPage.contains("""data-runtime-field="systemLoad""""))
        assertTrue(shellPage.contains("""data-runtime-field="cpuUsage""""))
        assertTrue(shellPage.contains("""data-runtime-field="memoryUsage""""))
        assertTrue(shellPage.contains("""data-runtime-field="storageUsage""""))
        assertTrue(shellPage.contains("""data-runtime-field="dockerStatus""""))
        assertTrue(shellPage.contains("""data-runtime-list="recentPushRecords""""))
        assertFalse(shellPage.contains("查看全部"))
        assertTrue(shellPage.contains("""data-runtime-progress="cpuUsage""""))
        assertTrue(shellPage.contains("""data-runtime-progress="memoryUsage""""))
        assertTrue(shellPage.contains("""data-runtime-progress="storageUsage""""))
        assertTrue(shellScript.contains("activateView("))
        assertTrue(shellScript.contains("hashchange"))
        assertTrue(shellScript.contains("history.replaceState"))
        assertTrue(shellScript.contains("/api/runtime/summary"))
        assertTrue(shellScript.contains("refreshRuntimeSummary"))
        assertTrue(shellScript.contains("renderHostRuntimeStatus"))
        assertTrue(shellScript.contains("renderRecentPushRecords"))
        assertTrue(authScript.contains("/api/auth/login"))
        assertTrue(authScript.contains("/api/auth/change-password"))
    }

    /**
     * 日志窗口需要自动轮询并保持可滚动尾部视图，避免只靠手动刷新才能看到新日志。
     */
    @Test
    fun `log list should stay scrollable within the static shell`() {
        val shellPage = read("src/main/resources/webui/index.html")
        val shellScript = read("src/main/resources/webui/assets/app.js")
        val shellStyle = read("src/main/resources/webui/assets/app.css")

        assertTrue(shellPage.contains("class=\"log-row\""))
        assertTrue(shellPage.contains("class=\"log-level log-level-info\""))
        assertTrue(shellPage.contains("class=\"log-level log-level-warn\""))
        assertTrue(shellPage.contains("class=\"log-level log-level-error\""))
        assertTrue(shellStyle.contains(".log-list"))
        assertTrue(shellStyle.contains("max-height: 548px"))
        assertTrue(shellStyle.contains("overflow: auto"))
        assertTrue(shellScript.contains("views.has(viewName)"))
    }
}
