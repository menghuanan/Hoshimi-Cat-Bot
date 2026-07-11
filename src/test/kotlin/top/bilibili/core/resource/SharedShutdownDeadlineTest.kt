package top.bilibili.core.resource

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedShutdownDeadlineTest {
    /** 多个慢分区必须共享一个总预算，不能分别消费完整 strictness timeout。 */
    @Test
    fun `partitions should share total shutdown deadline`() = runBlocking {
        val supervisor = ResourceSupervisor()
        repeat(2) { index ->
            supervisor.register(
                LambdaResourcePartition(
                    id = "slow-$index",
                    shutdownPhase = ShutdownPhase.WORKERS,
                    stopAction = { delay(200) },
                ),
            )
        }
        val startedAt = System.nanoTime()
        val report = supervisor.stopAll(totalTimeoutMs = 80)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L

        assertFalse(report.success)
        assertTrue(elapsedMs < 250L, "elapsed=$elapsedMs")
        assertTrue(report.failures.isNotEmpty())
    }
}
