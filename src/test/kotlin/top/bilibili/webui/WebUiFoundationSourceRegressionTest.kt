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
        val runtimeDtos = read("src/main/kotlin/top/bilibili/webui/model/WebUiRuntimeDtos.kt")
        val runtimeFacade = read("src/main/kotlin/top/bilibili/webui/service/WebUiRuntimeFacade.kt")

        assertTrue(apiRoutes.contains("/api/runtime/summary"))
        assertTrue(apiRoutes.contains("/api/config/bili-config"))
        assertTrue(runtimeDtos.contains("""val appVersion: String"""))
        assertTrue(runtimeFacade.contains("""appVersion = appVersionProvider()"""))
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
        assertTrue(loginPage.contains("theme.js"))
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
        assertTrue(shellPage.contains("""data-runtime-field="sidebarVersion""""))
        assertTrue(shellPage.contains("""data-runtime-list="recentPushRecords""""))
        assertTrue(shellPage.contains("""data-theme-option="dark""""))
        assertTrue(shellPage.contains("""data-theme-option="light""""))
        assertTrue(shellPage.contains("""data-theme-option="system""""))
        assertTrue(shellPage.contains("""id="theme-mode-selector""""))
        assertFalse(shellPage.contains("""class="topbar-icon-wrap""""))
        assertFalse(shellPage.contains("""class="badge""""))
        assertFalse(shellPage.contains("""class="status-chip""""))
        assertFalse(shellPage.contains("""data-runtime-field="sidebarStatus""""))
        assertFalse(shellPage.contains("查看全部"))
        assertTrue(shellPage.contains("""data-runtime-progress="cpuUsage""""))
        assertTrue(shellPage.contains("""data-runtime-progress="memoryUsage""""))
        assertTrue(shellPage.contains("""data-runtime-progress="storageUsage""""))
        assertTrue(shellPage.contains("theme.js"))
        assertTrue(shellScript.contains("activateView("))
        assertTrue(shellScript.contains("hashchange"))
        assertTrue(shellScript.contains("history.replaceState"))
        assertTrue(shellScript.contains("/api/runtime/summary"))
        assertTrue(shellScript.contains("refreshRuntimeSummary"))
        assertTrue(shellScript.contains("renderHostRuntimeStatus"))
        assertTrue(shellScript.contains("renderRecentPushRecords"))
        assertTrue(shellScript.contains("dynamic_bot_webui_theme"))
        assertTrue(shellScript.contains("applyThemePreference"))
        assertTrue(shellScript.contains("resolveThemePreference"))
        assertTrue(authScript.contains("dynamic_bot_webui_theme"))
        assertTrue(authScript.contains("applyThemePreference"))
        assertTrue(authScript.contains("/api/auth/login"))
        assertTrue(authScript.contains("/api/auth/change-password"))
    }

    /**
     * 主壳页的交互脚本和样式必须带资源版本，避免 hash 页面复用旧缓存后管理员按钮没有事件绑定。
     */
    @Test
    fun `frontend shell should version account control assets`() {
        val shellPage = read("src/main/resources/webui/index.html")

        assertTrue(shellPage.contains("""/assets/app.css?v=account-controls-layer"""))
        assertTrue(shellPage.contains("""/assets/app.js?v=account-controls-layer"""))
    }

    /**
     * 顶栏自身必须建立高层级上下文，避免 backdrop-filter 生成的层叠上下文被后续内容卡片盖住。
     */
    @Test
    fun `frontend shell should keep admin menu above dashboard cards`() {
        val shellStyle = read("src/main/resources/webui/assets/app.css")

        assertTrue(shellStyle.contains(".topbar {"))
        assertTrue(shellStyle.contains("position: relative;"))
        assertTrue(shellStyle.contains("z-index: 60;"))
    }

    /**
     * 顶栏管理员入口必须暴露菜单、退出登录和居中改密弹窗，避免视觉按钮回退成不可交互文本。
     */
    @Test
    fun `frontend shell should expose usable admin account controls`() {
        val shellPage = read("src/main/resources/webui/index.html")
        val shellScript = read("src/main/resources/webui/assets/app.js")
        val shellStyle = read("src/main/resources/webui/assets/app.css")

        assertTrue(shellPage.contains("""id="admin-menu-button""""))
        assertTrue(shellPage.contains("""id="admin-menu""""))
        assertTrue(shellPage.contains("""id="change-password-modal""""))
        assertTrue(shellPage.contains("""id="modal-current-password""""))
        assertTrue(shellPage.contains("""id="modal-new-password""""))
        assertTrue(shellPage.contains("""id="modal-confirm-password""""))
        assertTrue(shellPage.contains("退出登录"))
        assertTrue(shellPage.contains("修改密码"))
        assertTrue(shellScript.contains("/api/auth/logout"))
        assertTrue(shellScript.contains("/api/auth/change-password"))
        assertTrue(shellScript.contains("旧密码错误"))
        assertTrue(shellScript.contains("新密码和确认密码不一致"))
        assertTrue(shellStyle.contains(".admin-menu"))
        assertTrue(shellStyle.contains(".modal-backdrop"))
        assertTrue(shellStyle.contains(".password-modal"))
    }

    /**
     * 日志窗口需要自动轮询并保持可滚动尾部视图，避免只靠手动刷新才能看到新日志。
     */
    @Test
    fun `log list should stay scrollable within the static shell`() {
        val shellPage = read("src/main/resources/webui/index.html")
        val shellScript = read("src/main/resources/webui/assets/app.js")
        val shellStyle = read("src/main/resources/webui/assets/app.css")

        assertTrue(shellPage.contains("""id="log-level-filter""""))
        assertTrue(shellPage.contains("""id="log-source-filter""""))
        assertTrue(shellPage.contains("""id="log-search-input""""))
        assertTrue(shellPage.contains("""id="log-auto-refresh""""))
        assertTrue(shellPage.contains("""id="log-clear-button""""))
        assertTrue(shellPage.contains("""id="log-export-button""""))
        assertTrue(shellPage.contains("""data-log-list"""))
        assertTrue(shellStyle.contains(".log-list"))
        assertTrue(shellStyle.contains("max-height: 548px"))
        assertTrue(shellStyle.contains("overflow: auto"))
        assertTrue(shellScript.contains("views.has(viewName)"))
        assertTrue(shellScript.contains("/api/logs/sources"))
        assertTrue(shellScript.contains("/api/logs/"))
        assertTrue(shellScript.contains("renderLogRows"))
        assertTrue(shellScript.contains("clearCurrentLog"))
        assertTrue(shellScript.contains("exportCurrentLog"))
    }
}
