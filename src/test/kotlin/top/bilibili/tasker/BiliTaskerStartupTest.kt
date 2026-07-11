package top.bilibili.tasker

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class BiliTaskerStartupTest {
    /**
     * 启动确认必须等到 init 成功，不能把仅提交协程误报为任务已启动。
     */
    @Test
    fun `start confirmation should fail when task initialization throws`() = runBlocking {
        val tasker = FailingInitTasker()

        try {
            assertFalse(tasker.startAndAwaitInitialization(1_000L))
            assertFalse(tasker.healthSnapshot().active)
        } finally {
            tasker.cancel()
        }
    }

    /**
     * init 正常完成且主协程仍活跃时，启动确认才允许返回成功。
     */
    @Test
    fun `start confirmation should succeed after task initialization completes`() = runBlocking {
        val tasker = SuccessfulInitTasker()

        try {
            assertTrue(tasker.startAndAwaitInitialization(1_000L))
            assertTrue(tasker.healthSnapshot().active)
        } finally {
            tasker.cancel()
        }
    }

    /** 使用已有资源策略名称构造仅供启动生命周期验证的失败 Tasker。 */
    private class FailingInitTasker : BiliTasker("CacheClearTasker") {
        override var interval: Int = 60

        override fun init() {
            error("init failed")
        }

        override suspend fun main() = Unit
    }

    /** 使用已有资源策略名称构造仅供启动生命周期验证的成功 Tasker。 */
    private class SuccessfulInitTasker : BiliTasker("LogClearTasker") {
        override var interval: Int = 60

        override suspend fun main() = Unit
    }
}
