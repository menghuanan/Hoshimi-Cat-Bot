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
    fun `frontend shell should include management sections and remain api driven`() {
        val loginPage = read("src/main/resources/webui/login.html")
        val shellPage = read("src/main/resources/webui/index.html")
        val shellScript = read("src/main/resources/webui/assets/app.js")
        val authScript = read("src/main/resources/webui/assets/auth.js")

        assertTrue(loginPage.contains("id=\"login-form\""))
        assertTrue(shellPage.contains("id=\"runtime-summary\""))
        assertTrue(shellPage.contains("id=\"bili-config-form\""))
        assertTrue(shellPage.contains("id=\"bili-data-form\""))
        assertTrue(shellPage.contains("id=\"bot-config-form\""))
        assertTrue(shellPage.contains("id=\"log-source-select\""))
        assertTrue(shellPage.contains("id=\"log-tail-select\""))
        assertTrue(shellPage.contains("id=\"log-window-meta\""))
        assertTrue(shellPage.contains("id=\"reload-config-action\""))
        assertTrue(shellPage.contains("id=\"shutdown-action\""))
        assertTrue(shellPage.contains("id=\"request-restart-action\""))
        assertTrue(shellScript.contains("/api/auth/session"))
        assertTrue(shellScript.contains("/api/runtime/summary"))
        assertTrue(shellScript.contains("/api/config/bili-config"))
        assertTrue(shellScript.contains("/api/config/bili-data"))
        assertTrue(shellScript.contains("/api/config/bot"))
        assertTrue(shellScript.contains("/api/logs/sources"))
        assertTrue(shellScript.contains("/api/logs/"))
        assertTrue(shellScript.contains("/api/actions/reload-config"))
        assertTrue(shellScript.contains("/api/actions/shutdown"))
        assertTrue(shellScript.contains("/api/actions/request-restart"))
        assertTrue(shellScript.contains("availableTailLines"))
        assertTrue(shellScript.contains("sourceMissing"))
        assertTrue(shellScript.contains("operatorHint"))
        assertFalse(shellScript.contains("/api/config/save-all"))
        assertTrue(authScript.contains("/api/auth/login"))
        assertTrue(authScript.contains("/api/auth/change-password"))
        assertTrue(shellScript.contains("restartRequestMode"))
    }

    /**
     * 日志窗口需要自动轮询并保持可滚动尾部视图，避免只靠手动刷新才能看到新日志。
     */
    @Test
    fun `log viewer should refresh itself and keep a scrollable window`() {
        val shellPage = read("src/main/resources/webui/index.html")
        val shellScript = read("src/main/resources/webui/assets/app.js")
        val shellStyle = read("src/main/resources/webui/assets/app.css")

        assertTrue(shellPage.contains("id=\"bili-config-overview\""))
        assertTrue(shellPage.contains("id=\"bili-data-overview\""))
        assertTrue(shellPage.contains("id=\"bot-config-overview\""))
        assertTrue(shellScript.contains("setInterval("))
        assertTrue(shellScript.contains("logViewer.scrollTop = logViewer.scrollHeight"))
        assertTrue(shellStyle.contains("#log-viewer"))
        assertTrue(shellStyle.contains("overflow-y: auto"))
    }
}
