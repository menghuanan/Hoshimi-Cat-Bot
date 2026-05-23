package top.bilibili.webui

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebUiRuleClosureVerificationTest {
    /**
     * 统一按 UTF-8 读取 phase4 相关源码和静态资源，避免宿主默认编码影响闭环检查。
     */
    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

    /**
     * WebUI 脚本入口已拆成加载器与功能脚本，闭环控件断言需要读取真实功能脚本集合。
     * 删除 plain runtime 后，闭环检查读取 React 源码集合。
     */
    private fun readReactSourceBundle(): String {
        return Files.walk(Path.of("webui-frontend/src")).use { paths ->
            paths
                .filter { path -> Files.isRegularFile(path) }
                .filter { path -> path.toString().endsWith(".ts") || path.toString().endsWith(".tsx") }
                .map { path -> read(path.toString()) }
                .toList()
                .joinToString("\n")
        }
    }

    /**
     * 路由层必须持续暴露本地调试闭环需要的认证、配置、日志和动作入口。
     */
    @Test
    fun `route surface should keep local debug closure endpoints available`() {
        val apiRoutes = read("src/main/kotlin/top/bilibili/webui/routes/WebUiApiRoutes.kt")
        val authRoutes = read("src/main/kotlin/top/bilibili/webui/routes/WebUiAuthRoutes.kt")
        val logRoutes = read("src/main/kotlin/top/bilibili/webui/routes/WebUiLogRoutes.kt")
        val actionRoutes = read("src/main/kotlin/top/bilibili/webui/routes/WebUiActionRoutes.kt")

        assertTrue(authRoutes.contains("/api/auth/login"))
        assertTrue(authRoutes.contains("/api/auth/change-password"))
        assertTrue(apiRoutes.contains("/api/runtime/summary"))
        assertTrue(apiRoutes.contains("/api/config/bili-config"))
        assertTrue(apiRoutes.contains("/api/config/bili-data"))
        assertTrue(apiRoutes.contains("/api/config/bot"))
        assertTrue(apiRoutes.contains("/api/subscriptions"))
        assertTrue(logRoutes.contains("/api/logs/sources"))
        assertTrue(actionRoutes.contains("/api/actions/reload-config"))
        assertTrue(actionRoutes.contains("/api/actions/shutdown"))
        assertTrue(actionRoutes.contains("/api/actions/request-restart"))
    }

    /**
     * 前端壳页面应持续保留本地 operator 调试当前闭环所需的最小控件集合。
     */
    @Test
    fun `frontend shell should keep closure controls visible`() {
        val reactSource = readReactSourceBundle()
        val reactShell = read("src/main/resources/webui/react/index.html")

        assertTrue(reactShell.contains("""id="root""""))
        assertTrue(reactSource.contains("data-nav-target={target}"))
        assertTrue(reactSource.contains("['home', '首页']"))
        assertTrue(reactSource.contains("['settings', '系统配置']"))
        assertTrue(reactSource.contains("['subscriptions', '订阅管理']"))
        assertTrue(reactSource.contains("['logs', '日志']"))
        assertTrue(reactSource.contains("/api/runtime/summary"))
        assertTrue(reactSource.contains("/api/config/bili-config"))
        assertTrue(reactSource.contains("/api/config/bot"))
        assertTrue(reactSource.contains("settingsCategories"))
        assertTrue(reactSource.contains("saveActiveCategory"))
        assertTrue(reactSource.contains("buildBiliConfigSavePayload"))
        assertTrue(reactSource.contains("buildBotConfigSavePayload"))
        assertTrue(reactSource.contains("recentPushRecords"))
        assertTrue(reactSource.contains("DashboardPage"))
        assertTrue(reactSource.contains("SubscriptionsPage"))
        assertTrue(reactSource.contains("LogsPage"))
        assertFalse(Files.exists(Path.of("src/main/resources/webui/index.html")))
        assertFalse(Files.exists(Path.of("src/main/resources/webui/login.html")))
        assertFalse(Files.exists(Path.of("src/main/resources/webui/assets")))
    }

    /**
     * Phase4 backend 契约必须持续输出冲突、确认过期和重启降级等本地调试语义。
     */
    @Test
    fun `backend contracts should preserve phase4 local debug semantics`() {
        val authService = read("src/main/kotlin/top/bilibili/webui/auth/WebUiAuthService.kt")
        val configWriteFacade = read("src/main/kotlin/top/bilibili/webui/service/WebUiConfigWriteFacade.kt")
        val actionDtos = read("src/main/kotlin/top/bilibili/webui/model/WebUiActionDtos.kt")
        val runtimeDtos = read("src/main/kotlin/top/bilibili/webui/model/WebUiRuntimeDtos.kt")

        assertTrue(authService.contains("confirmation expired"))
        assertTrue(configWriteFacade.contains("REJECTED_CONFLICT"))
        assertTrue(actionDtos.contains("RESTART_REQUESTED_MANUAL_FALLBACK"))
        assertTrue(runtimeDtos.contains("restartRequestMode"))
        assertTrue(runtimeDtos.contains("todayPushStats"))
        assertTrue(runtimeDtos.contains("recentPushRecords"))
        assertTrue(runtimeDtos.contains("webSocket"))
        assertTrue(runtimeDtos.contains("WebUiHostRuntimeStatusDto"))
        assertFalse(runtimeDtos.contains("WebUiDockerRuntimeStatusDto"))
    }
}
