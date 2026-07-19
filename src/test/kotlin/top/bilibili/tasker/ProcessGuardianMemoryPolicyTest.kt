package top.bilibili.tasker

import kotlin.test.Test
import kotlin.test.assertEquals

class ProcessGuardianMemoryPolicyTest {
    /** CodeCache 上限必须优先采用运行 JVM 的 ReservedCodeCacheSize，而不是部署侧硬编码。 */
    @Test
    fun `code cache limit should prefer reserved code cache vm option`() {
        val resolution = resolveCodeCacheLimitBytes(
            reservedCodeCacheSizeValue = "50331648",
            poolMaxBytes = listOf(8L * MIB, 20L * MIB, 20L * MIB),
            fallbackLimitBytes = 32L * MIB,
        )

        assertEquals(48L * MIB, resolution.maxBytes)
        assertEquals(CodeCacheLimitSource.VM_OPTION, resolution.source)
    }

    /** VM option 不可用时应汇总所有有效 CodeHeap/CodeCache pool max。 */
    @Test
    fun `code cache limit should aggregate memory pool maxima when vm option is unavailable`() {
        val resolution = resolveCodeCacheLimitBytes(
            reservedCodeCacheSizeValue = null,
            poolMaxBytes = listOf(8L * MIB, -1L, 16L * MIB, 24L * MIB),
            fallbackLimitBytes = 32L * MIB,
        )

        assertEquals(48L * MIB, resolution.maxBytes)
        assertEquals(CodeCacheLimitSource.MEMORY_POOLS, resolution.source)
    }

    /** JVM 与内存池均未报告有效上限时，才允许回退项目统一的 48 MiB 基线。 */
    @Test
    fun `code cache limit should use project fallback only as last resort`() {
        val resolution = resolveCodeCacheLimitBytes(
            reservedCodeCacheSizeValue = "unavailable",
            poolMaxBytes = listOf(-1L, 0L),
            fallbackLimitBytes = 48L * MIB,
        )

        assertEquals(48L * MIB, resolution.maxBytes)
        assertEquals(CodeCacheLimitSource.FALLBACK, resolution.source)
    }

    /** 容量告警采用 80% 告警、75% 恢复和一小时提醒冷却，避免每 30 秒重复 WARN。 */
    @Test
    fun `non heap capacity alert should use hysteresis and hourly reminder`() {
        val state = NonHeapCapacityAlertState()

        assertEquals(NonHeapCapacityAlertEvent.NONE, evaluateNonHeapCapacityAlert(state, 0.79, 0L))
        assertEquals(NonHeapCapacityAlertEvent.ENTERED, evaluateNonHeapCapacityAlert(state, 0.80, 1_000L))
        assertEquals(NonHeapCapacityAlertEvent.NONE, evaluateNonHeapCapacityAlert(state, 0.81, 31_000L))
        assertEquals(
            NonHeapCapacityAlertEvent.REMINDER,
            evaluateNonHeapCapacityAlert(state, 0.82, 1_000L + 60L * 60L * 1_000L),
        )
        assertEquals(NonHeapCapacityAlertEvent.NONE, evaluateNonHeapCapacityAlert(state, 0.76, 3_700_000L))
        assertEquals(NonHeapCapacityAlertEvent.RECOVERED, evaluateNonHeapCapacityAlert(state, 0.75, 3_730_000L))
        assertEquals(NonHeapCapacityAlertEvent.ENTERED, evaluateNonHeapCapacityAlert(state, 0.80, 3_760_000L))
    }

    private companion object {
        const val MIB = 1024L * 1024L
    }
}
