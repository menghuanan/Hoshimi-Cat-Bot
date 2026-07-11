package top.bilibili.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class RequiredStartupSequenceTest {
    /**
     * 任一必需启动阶段失败时必须返回失败，并阻止后续阶段继续执行。
     */
    @Test
    fun `required startup sequence should stop after a failed stage`() = runBlocking {
        var laterStageExecuted = false

        val result = RequiredStartupSequence.run(
            stages = listOf(
                RequiredStartupStage("data") { false },
                RequiredStartupStage("taskers") {
                    laterStageExecuted = true
                    true
                },
            ),
        )

        assertFalse(result.success)
        assertEquals("data", result.failedStage)
        assertFalse(laterStageExecuted)
    }

    /**
     * 阶段抛出的异常必须转换为失败结果，不能被误判为启动完成。
     */
    @Test
    fun `required startup sequence should report thrown stage failure`() = runBlocking {
        val result = RequiredStartupSequence.run(
            stages = listOf(
                RequiredStartupStage("taskers") { error("boom") },
            ),
        )

        assertFalse(result.success)
        assertEquals("taskers", result.failedStage)
        assertEquals("boom", result.cause?.message)
    }

    /**
     * 所有必需阶段完成后才允许启动序列报告成功。
     */
    @Test
    fun `required startup sequence should succeed after every stage completes`() = runBlocking {
        val executed = mutableListOf<String>()

        val result = RequiredStartupSequence.run(
            stages = listOf(
                RequiredStartupStage("data") { executed += "data"; true },
                RequiredStartupStage("taskers") { executed += "taskers"; true },
            ),
        )

        assertTrue(result.success)
        assertEquals(listOf("data", "taskers"), executed)
    }
}
