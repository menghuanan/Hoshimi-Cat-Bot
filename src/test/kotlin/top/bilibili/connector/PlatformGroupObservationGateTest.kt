package top.bilibili.connector

import kotlin.test.*

class PlatformGroupObservationGateTest {
    /** 候选启动事件等到 commit 才可见，状态合并保留拒收事实，旧连接关闭后不能再写。 */
    @Test fun `buffers until activation and drops events after close`() {
        val received = mutableListOf<PlatformGroupObservation>()
        val gate = PlatformGroupObservationGate("app-a") { account, event -> assertEquals("app-a", account); received += event }
        gate.observe(PlatformGroupObservation("id", acceptsProactive = false, observedAt = 1))
        gate.observe(PlatformGroupObservation("id", observedAt = 2))
        assertTrue(received.isEmpty()); gate.activate()
        assertFalse(received.single().acceptsProactive!!)
        gate.close(); gate.observe(PlatformGroupObservation("another", observedAt = 3)); gate.activate()
        assertEquals(1, received.size)
    }

    /** 准备阶段回滚的连接不能污染已提交目录，即使错误地再次激活也保持关闭。 */
    @Test fun `rollback discards all pending observations`() {
        val gate = PlatformGroupObservationGate("app-a") { _, _ -> fail("rolled back candidate emitted an event") }
        gate.observe(PlatformGroupObservation("id", observedAt = 1)); gate.close(); gate.activate()
    }
}
