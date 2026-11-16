package top.bilibili

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertTrue

class CiWorkflowObservabilityTest {
    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

    // 即使开发机已缓存 Gradle，也验证首次安装会使用的摘要格式，防止云端冷缓存才暴露大小写错误。
    @Test
    fun `wrapper distribution checksum should use lowercase sha256`() {
        val properties = Properties()
        read("gradle/wrapper/gradle-wrapper.properties").reader().use { properties.load(it) }

        assertTrue(
            properties.getProperty("distributionSha256Sum", "").matches(Regex("[0-9a-f]{64}")),
            "wrapper checksum must be a nonempty lowercase SHA-256 because download verification is case-sensitive",
        )
    }

    // 环境探测失败必须保留日志并阻断后续构建，不能由 tee 的成功退出码掩盖 Wrapper 失败。
    @Test
    fun `environment diagnostics should propagate pipeline failure and report its outcome`() {
        val workflow = read(".github/workflows/ci.yml")
        val diagnosticsStep = workflow.substringAfter("- name: Environment Diagnostics")
            .substringBefore("- name: Check WebUI Frontend")

        assertTrue(
            diagnosticsStep.substringBefore("./gradlew --version").contains("set -o pipefail"),
            "environment diagnostics must enable pipefail before invoking the wrapper through tee",
        )
        assertTrue(
            diagnosticsStep.contains("id: environment_diagnostics") &&
                workflow.contains("\${{ steps.environment_diagnostics.outcome }}"),
            "CI summary should identify environment setup failures separately from compilation failures",
        )
    }

    @Test
    fun `ci workflow should emit richer diagnostics on success and failure`() {
        val text = read(".github/workflows/ci.yml")

        assertTrue(
            text.contains("name: Environment Diagnostics"),
            "workflow should log runner and toolchain diagnostics before the Gradle build",
        )
        assertTrue(
            text.contains("--stacktrace") && text.contains("--info") && text.contains("--warning-mode all"),
            "workflow should run Gradle with richer diagnostics flags",
        )
        assertTrue(
            text.contains("if: always()") && text.contains("build/reports") && text.contains("build/test-results"),
            "workflow should always upload reports and test results for post-failure inspection",
        )
        assertTrue(
            text.contains("GITHUB_STEP_SUMMARY"),
            "workflow should write a compact execution summary to the GitHub job summary",
        )
        assertTrue(
            text.contains("build/libs/*.jar"),
            "workflow should continue uploading build artifacts on successful runs",
        )
    }
}
