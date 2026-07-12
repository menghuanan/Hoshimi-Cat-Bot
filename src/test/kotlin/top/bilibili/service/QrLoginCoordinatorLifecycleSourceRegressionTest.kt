package top.bilibili.service

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QrLoginCoordinatorLifecycleSourceRegressionTest {
    /** 统一按 UTF-8 读取生命周期入口，避免 source regression 依赖宿主默认编码。 */
    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

    /** 核心凭据提交必须无挂起收口，网络刷新只能在成功终态发布后作为独立可超时步骤运行。 */
    @Test
    fun `credential commit should release login lock before best effort refresh`() {
        val source = read("src/main/kotlin/top/bilibili/service/QrLoginCoordinator.kt")
        val coreCommit = source.substringAfter("private fun commitQrLoginCallback")

        assertFalse(coreCommit.contains("initTagid()"), "core credential commit must not include network refresh")
        assertTrue(source.contains("postCommitRefresh"), "coordinator should own a separate post-commit refresh step")
        assertTrue(source.contains("POST_COMMIT_REFRESH_TIMEOUT_MS"), "post-commit refresh must have an explicit timeout")
    }

    /** 协调器必须暴露停机 drain 和健康快照，Core 才能在依赖关闭前显式管理 worker。 */
    @Test
    fun `coordinator should expose worker drain and runtime observability`() {
        val source = read("src/main/kotlin/top/bilibili/service/QrLoginCoordinator.kt")

        assertTrue(source.contains("suspend fun shutdownAndDrain("))
        assertTrue(source.contains("fun runtimeSnapshot()"))
        assertTrue(source.contains("commitDrainTimeoutCount"))
        assertTrue(source.contains("phaseAgeMillis"))
        assertTrue(source.contains("drainState"))
    }
}
