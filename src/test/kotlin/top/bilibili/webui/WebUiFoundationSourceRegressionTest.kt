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

        assertTrue(apiRoutes.contains("/api/health"))
        assertTrue(staticRoutes.contains("""staticResources("/assets", "webui/assets")"""))
        assertTrue(staticRoutes.contains("""get("/")"""))
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
}
