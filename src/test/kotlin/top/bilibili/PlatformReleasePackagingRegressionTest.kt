package top.bilibili

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlatformReleasePackagingRegressionTest {
    // 发行链路回归测试只读取仓库文本，避免把 Gradle 任务执行副作用带入断言。
    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

    // 锁定 GitHub Release 对普通用户暴露的按平台资产名称，避免重新退回裸 jar 发布路径。
    @Test
    fun `release workflow should publish platform specific distribution assets`() {
        val workflow = read(".github/workflows/release.yml")

        assertTrue(
            workflow.contains("hoshimi-cat-bot-windows-x64-v"),
            "release workflow should upload a Windows-specific release asset",
        )
        assertTrue(
            workflow.contains("hoshimi-cat-bot-linux-x64-v"),
            "release workflow should upload a Linux-specific release asset",
        )
        assertFalse(
            workflow.contains("files: build/libs/*.jar"),
            "release workflow should stop exposing the bare jar as the primary release asset",
        )
    }

    // 锁定 Gradle 平台发行任务的公开名称与归档命名，防止混合 distribution 再次成为发布资产。
    @Test
    fun `build script should define platform specific distribution packaging`() {
        val buildScript = read("build.gradle.kts")

        assertTrue(
            buildScript.contains("windowsReleaseDistZip"),
            "build script should register a dedicated Windows distribution task",
        )
        assertTrue(
            buildScript.contains("linuxReleaseDistTar"),
            "build script should register a dedicated Linux distribution task",
        )
        assertTrue(
            buildScript.contains("hoshimi-cat-bot-windows-x64-v"),
            "Windows distribution should use a platform-specific archive name",
        )
        assertTrue(
            buildScript.contains("hoshimi-cat-bot-linux-x64-v"),
            "Linux distribution should use a platform-specific archive name",
        )
        assertTrue(
            buildScript.contains("jdk.charsets"),
            "release runtime should include jdk.charsets so ZXing QR generation keeps working in the bundled jlink image",
        )
    }

    // 锁定完整包与测试包的一体化入口：完整包内置 runtime，测试包回退到目标机器 JDK 17。
    @Test
    fun `build script should expose stable full and test package entrypoints`() {
        val buildScript = read("build.gradle.kts")

        assertTrue(
            buildScript.contains("val fullPackage = tasks.register(\"fullPackage\")"),
            "build script should expose a host-independent full package entrypoint",
        )
        assertTrue(
            buildScript.contains("val testPackage = tasks.register(\"testPackage\")"),
            "build script should expose a host-independent test package entrypoint",
        )
        assertTrue(
            buildScript.contains("hoshimi-cat-bot-windows-x64-test-v") &&
                buildScript.contains("hoshimi-cat-bot-linux-x64-test-v"),
            "test packages should keep platform-specific archive names",
        )
        assertTrue(
            buildScript.contains("command -v java") && buildScript.contains("where java"),
            "test packages should fall back to the target machine's JDK when runtime is absent",
        )
    }

    // 锁定 Windows 启动脚本的 CRLF 生成约束，避免 CMD 在 LF-only 批处理上错拆 set 行。
    @Test
    fun `windows launcher should be generated with crlf line endings`() {
        val buildScript = read("build.gradle.kts")

        assertTrue(
            buildScript.contains(".trimIndent().replace(\"\\n\", \"\\r\\n\")"),
            "start.bat must be generated with CRLF so cmd.exe parses every launcher line",
        )
    }

    // 锁定构建期堆基线，避免完整包构建再次退回 Gradle 默认的 512 MiB 上限。
    @Test
    fun `gradle properties should keep build time heap baseline`() {
        val properties = read("gradle.properties")

        assertTrue(
            properties.contains("org.gradle.jvmargs=-Xmx2048m"),
            "gradle.properties should keep the build-time heap baseline for full package builds",
        )
    }
}
