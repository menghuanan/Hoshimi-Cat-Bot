package top.bilibili

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReleaseWorkflowTagPolicyTest {
    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

    @Test
    fun `tag release workflow should publish only exact tag and latest`() {
        val text = read(".github/workflows/release.yml")

        assertTrue(
            text.contains("type=raw,value=\${{ github.ref_name }}"),
            "workflow should publish the exact git tag",
        )
        assertTrue(
            text.contains("type=raw,value=latest"),
            "workflow should publish latest for release tags",
        )
        assertFalse(
            text.contains("MAJOR_MINOR_TAG"),
            "workflow should not derive a movable major-minor Docker tag",
        )
        assertFalse(
            text.contains("type=raw,value=\${{ env.MAJOR_MINOR_TAG }}"),
            "workflow should not publish a major-minor Docker tag alias",
        )
        // 发布构建现在分成 verify、Windows package、Linux package 和 release 聚合四段，避免单点平台打包失败。
        assertTrue(
            text.contains("name: Compile with Gradle") &&
                text.contains("name: Test with Gradle") &&
                text.contains("name: Package Windows Release") &&
                text.contains("name: Package Linux Release"),
            "workflow should split release build into platform-specific packaging stages for clearer diagnostics",
        )
        assertTrue(
            text.contains("--stacktrace") && text.contains("--info") && text.contains("--warning-mode all"),
            "workflow should run Gradle with detailed diagnostic flags in release builds",
        )
    }
}
