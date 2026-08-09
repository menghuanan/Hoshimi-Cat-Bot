package top.bilibili.tasker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

    /** 显式 RSS 阈值必须覆盖 cgroup 与 JVM 分区推导，确保外部 supervisor 可以给出最终策略。 */
    @Test
    fun `rss soft limit should prefer explicit environment threshold`() {
        val resolution = resolveRssSoftLimit(
            explicitThresholdMB = "640",
            cgroupCapacity = CgroupMemoryCapacity(512L * MIB, RssSoftLimitSource.CGROUP_V2),
            heapMaxBytes = 160L * MIB,
            metaspaceMaxBytes = 64L * MIB,
            codeCacheMaxBytes = 48L * MIB,
            directMemoryMaxBytes = 32L * MIB,
            skiaCacheMaxBytes = 48L * MIB,
        )

        assertEquals(640L, resolution.thresholdMB)
        assertEquals(RssSoftLimitSource.EXPLICIT_ENV, resolution.source)
        assertNull(resolution.capacityMB)
    }

    /** 未显式覆盖时，容器按 cgroup 实际容量的 90% 保留 OOM 前安全余量。 */
    @Test
    fun `rss soft limit should use ninety percent of cgroup capacity`() {
        val resolution = resolveRssSoftLimit(
            explicitThresholdMB = null,
            cgroupCapacity = CgroupMemoryCapacity(512L * MIB, RssSoftLimitSource.CGROUP_V2),
            heapMaxBytes = 160L * MIB,
            metaspaceMaxBytes = 64L * MIB,
            codeCacheMaxBytes = 48L * MIB,
            directMemoryMaxBytes = 32L * MIB,
            skiaCacheMaxBytes = 48L * MIB,
        )

        assertEquals(460L, resolution.thresholdMB)
        assertEquals(512L, resolution.capacityMB)
        assertEquals(RssSoftLimitSource.CGROUP_V2, resolution.source)
    }

    /** 裸机按实际 JVM 分区上限合计的 90% 计算，外部启动参数扩容后阈值必须同比更新。 */
    @Test
    fun `rss soft limit should scale with runtime jvm partition capacities`() {
        val resolution = resolveRssSoftLimit(
            explicitThresholdMB = null,
            cgroupCapacity = null,
            heapMaxBytes = 512L * MIB,
            metaspaceMaxBytes = 128L * MIB,
            codeCacheMaxBytes = 96L * MIB,
            directMemoryMaxBytes = 64L * MIB,
            skiaCacheMaxBytes = 128L * MIB,
        )

        assertEquals(835L, resolution.thresholdMB)
        assertEquals(928L, resolution.capacityMB)
        assertEquals(RssSoftLimitSource.JVM_PARTITIONS, resolution.source)
    }

    /** 任一裸机分区无有限上限时必须停用自动退出，不能用部分容量形成偏低阈值。 */
    @Test
    fun `rss soft limit should be unavailable when a jvm partition is unbounded`() {
        val resolution = resolveRssSoftLimit(
            explicitThresholdMB = null,
            cgroupCapacity = null,
            heapMaxBytes = 160L * MIB,
            metaspaceMaxBytes = -1L,
            codeCacheMaxBytes = 48L * MIB,
            directMemoryMaxBytes = 32L * MIB,
            skiaCacheMaxBytes = 48L * MIB,
        )

        assertNull(resolution.thresholdMB)
        assertEquals(RssSoftLimitSource.UNAVAILABLE, resolution.source)
    }

    /** cgroup v2 优先于 v1，v2 无限制时才允许回退到 v1 的有限容量。 */
    @Test
    fun `cgroup memory reader should prefer finite v2 and fall back to v1`() {
        val v2 = readCgroupMemoryCapacity { path ->
            when (path) {
                "/sys/fs/cgroup/memory.max" -> (768L * MIB).toString()
                "/sys/fs/cgroup/memory/memory.limit_in_bytes" -> (512L * MIB).toString()
                else -> null
            }
        }
        val v1 = readCgroupMemoryCapacity { path ->
            when (path) {
                "/sys/fs/cgroup/memory.max" -> "max"
                "/sys/fs/cgroup/memory/memory.limit_in_bytes" -> (512L * MIB).toString()
                else -> null
            }
        }

        assertEquals(768L * MIB, v2?.capacityBytes)
        assertEquals(RssSoftLimitSource.CGROUP_V2, v2?.source)
        assertEquals(512L * MIB, v1?.capacityBytes)
        assertEquals(RssSoftLimitSource.CGROUP_V1, v1?.source)
    }

    private companion object {
        const val MIB = 1024L * 1024L
    }
}
