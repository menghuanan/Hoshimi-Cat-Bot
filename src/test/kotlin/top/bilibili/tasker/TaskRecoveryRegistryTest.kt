package top.bilibili.tasker

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskRecoveryRegistryTest {
    /** 每个用例恢复注册表运行预算，避免熔断状态跨测试。 */
    @AfterTest
    fun cleanup() = runBlocking {
        BiliTasker.cancelAll(1_000L)
        BiliTasker.taskers.clear()
        TaskRecoveryRegistry.resetRuntimeState()
        TaskRecoveryRegistry.install(emptyList())
    }

    /** 主作业异常结束后按 5 秒首退避恢复，主动取消则不具备恢复资格。 */
    @Test
    fun `failed main job should recover but cancelled job should not`() = runBlocking {
        val tasker = RecoverableTasker()
        TaskRecoveryRegistry.install(listOf(TaskRecoveryRegistration("CacheClearTasker", { tasker }, true, true)))
        val delays = mutableListOf<Long>()
        TaskRecoveryRegistry.delayAction = { delays += it }
        tasker.forceFailedForTest()

        val result = TaskRecoveryRegistry.recover("CacheClearTasker")
        assertTrue(result is TaskRecoveryResult.Restarted)
        assertEquals(listOf(5_000L), delays)

        tasker.cancel()
        assertTrue(TaskRecoveryRegistry.recover("CacheClearTasker") is TaskRecoveryResult.NotEligible)
    }

    /** 30 分钟内五次恢复后打开熔断，并保持进程与注册表在线。 */
    @Test
    fun `recovery budget should open circuit after five attempts`() = runBlocking {
        val tasker = RecoverableTasker()
        TaskRecoveryRegistry.install(listOf(TaskRecoveryRegistration("CacheClearTasker", { tasker }, true, true)))
        TaskRecoveryRegistry.clock = Clock.fixed(Instant.ofEpochMilli(1_000L), ZoneOffset.UTC)
        TaskRecoveryRegistry.delayAction = {}

        repeat(5) {
            tasker.forceFailedForTest()
            assertTrue(TaskRecoveryRegistry.recover("CacheClearTasker") is TaskRecoveryResult.Restarted)
            tasker.cancelForNextFailureTest()
        }
        tasker.forceFailedForTest()
        assertTrue(TaskRecoveryRegistry.recover("CacheClearTasker") is TaskRecoveryResult.CircuitOpen)
        assertEquals(TaskMainState.CIRCUIT_OPEN, tasker.healthSnapshot().mainState)
        assertFalse(tasker.healthSnapshot().healthy)
    }

    /** 使用已有策略名的可控 Tasker，只测试注册表主作业状态机。 */
    private class RecoverableTasker : BiliTasker("CacheClearTasker") {
        override var interval: Int = 60
        override suspend fun main() = Unit

        /** 通过反射设置主状态，避免测试等待十轮真实失败退避。 */
        fun forceFailedForTest() {
            val field = BiliTasker::class.java.getDeclaredField("mainState").apply { isAccessible = true }
            field.set(this, TaskMainState.FAILED)
        }

        /** 清理本轮 Job 后保留后续模拟失败能力。 */
        fun cancelForNextFailureTest() {
            cancel()
        }
    }
}
