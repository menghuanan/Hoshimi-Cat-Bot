package top.bilibili.tasker

import kotlin.test.Test
import kotlin.test.assertEquals

class NonHeapMemoryLimitTest {
    /** JVM 提供实际 Metaspace 上限时必须采用实际值，避免启动参数变更后监控仍使用旧口径。 */
    @Test
    fun `should prefer runtime memory pool maximum`() {
        assertEquals(56L, resolveNonHeapLimitMB(56L * 1024L * 1024L, fallbackLimitMB = 48L))
    }

    /** 部分 JVM 内存池以负数表示未设置上限，此时使用项目基线保证告警仍可计算。 */
    @Test
    fun `should fall back when runtime maximum is unavailable`() {
        assertEquals(56L, resolveNonHeapLimitMB(-1L, fallbackLimitMB = 56L))
    }
}
