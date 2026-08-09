package top.bilibili.tasker

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

private const val BYTES_PER_MIB = 1024L * 1024L
private const val RSS_SOFT_LIMIT_CAPACITY_PERCENT = 90L
private const val CGROUP_V2_MEMORY_MAX_PATH = "/sys/fs/cgroup/memory.max"
private const val CGROUP_V1_MEMORY_LIMIT_PATH = "/sys/fs/cgroup/memory/memory.limit_in_bytes"
private const val CGROUP_V1_UNLIMITED_MIN_BYTES = Long.MAX_VALUE - 4095L

/** RSS 软限制的容量来源，用于区分显式覆盖、容器预算和裸机 JVM 分区预算。 */
internal enum class RssSoftLimitSource {
    EXPLICIT_ENV,
    CGROUP_V2,
    CGROUP_V1,
    JVM_PARTITIONS,
    UNAVAILABLE,
}

/** cgroup 暴露的有限内存容量及版本来源。 */
internal data class CgroupMemoryCapacity(
    val capacityBytes: Long,
    val source: RssSoftLimitSource,
)

/** RSS 软限制解析结果；阈值为空表示当前环境无法得到可信的有限容量。 */
internal data class RssSoftLimitResolution(
    val thresholdMB: Long?,
    val capacityMB: Long?,
    val source: RssSoftLimitSource,
    val detail: String,
)

/**
 * 按显式阈值、cgroup 容量、JVM 分区容量的优先级解析 RSS 软限制。
 * JVM 分区必须全部具有有限正上限，避免用部分容量制造比真实预算更低的误判阈值。
 */
internal fun resolveRssSoftLimit(
    explicitThresholdMB: String?,
    cgroupCapacity: CgroupMemoryCapacity?,
    heapMaxBytes: Long,
    metaspaceMaxBytes: Long,
    codeCacheMaxBytes: Long,
    directMemoryMaxBytes: Long,
    skiaCacheMaxBytes: Long,
): RssSoftLimitResolution {
    val explicitLimitMB = explicitThresholdMB
        ?.trim()
        ?.toLongOrNull()
        ?.takeIf { value -> value > 0L }
    if (explicitLimitMB != null) {
        return RssSoftLimitResolution(
            thresholdMB = explicitLimitMB,
            capacityMB = null,
            source = RssSoftLimitSource.EXPLICIT_ENV,
            detail = "MEMORY_THRESHOLD_MB=$explicitLimitMB",
        )
    }

    if (cgroupCapacity != null && cgroupCapacity.capacityBytes > 0L) {
        val capacityMB = cgroupCapacity.capacityBytes / BYTES_PER_MIB
        return capacityBasedRssSoftLimit(capacityMB, cgroupCapacity.source, "cgroup")
    }

    val partitionLimits = linkedMapOf(
        "heap" to heapMaxBytes,
        "metaspace" to metaspaceMaxBytes,
        "codeCache" to codeCacheMaxBytes,
        "directMemory" to directMemoryMaxBytes,
        "skiaCache" to skiaCacheMaxBytes,
    )
    val unavailablePartitions = partitionLimits.filterValues { bytes -> bytes <= 0L }.keys
    if (unavailablePartitions.isNotEmpty()) {
        return RssSoftLimitResolution(
            thresholdMB = null,
            capacityMB = null,
            source = RssSoftLimitSource.UNAVAILABLE,
            detail = "unbounded or unavailable JVM partitions: ${unavailablePartitions.joinToString()}",
        )
    }

    // 溢出意味着这些上限无法组成可信的 Long 容量，按不可用降级而不是回退静态阈值。
    val totalCapacityBytes = runCatching {
        partitionLimits.values.fold(0L) { total, bytes -> Math.addExact(total, bytes) }
    }.getOrNull() ?: return RssSoftLimitResolution(
        thresholdMB = null,
        capacityMB = null,
        source = RssSoftLimitSource.UNAVAILABLE,
        detail = "JVM partition capacity overflow",
    )
    val capacityMB = totalCapacityBytes / BYTES_PER_MIB
    val partitionDetail = partitionLimits.entries.joinToString(separator = ",") { (name, bytes) ->
        "$name=${bytes / BYTES_PER_MIB}MB"
    }
    return capacityBasedRssSoftLimit(capacityMB, RssSoftLimitSource.JVM_PARTITIONS, partitionDetail)
}

/** 按容量的 90% 计算软限制，并避免乘法在异常超大容量下溢出。 */
private fun capacityBasedRssSoftLimit(
    capacityMB: Long,
    source: RssSoftLimitSource,
    capacityDetail: String,
): RssSoftLimitResolution {
    if (capacityMB <= 0L) {
        return RssSoftLimitResolution(null, null, RssSoftLimitSource.UNAVAILABLE, "$capacityDetail capacity is unavailable")
    }
    val thresholdMB = (capacityMB / 100L) * RSS_SOFT_LIMIT_CAPACITY_PERCENT +
        ((capacityMB % 100L) * RSS_SOFT_LIMIT_CAPACITY_PERCENT) / 100L
    return RssSoftLimitResolution(
        thresholdMB = thresholdMB.coerceAtLeast(1L),
        capacityMB = capacityMB,
        source = source,
        detail = "$capacityDetail, ratio=${RSS_SOFT_LIMIT_CAPACITY_PERCENT}%",
    )
}

/**
 * 读取 Linux cgroup v2/v1 的有限内存上限；max 与 v1 的无限制哨兵值不会伪装成容量。
 */
internal fun readCgroupMemoryCapacity(
    readText: (String) -> String? = { path ->
        runCatching { Files.readString(Path.of(path), StandardCharsets.UTF_8) }.getOrNull()
    },
): CgroupMemoryCapacity? {
    val v2Bytes = readText(CGROUP_V2_MEMORY_MAX_PATH)
        ?.trim()
        ?.takeUnless { value -> value.equals("max", ignoreCase = true) }
        ?.toLongOrNull()
        ?.takeIf { value -> value > 0L }
    if (v2Bytes != null) {
        return CgroupMemoryCapacity(v2Bytes, RssSoftLimitSource.CGROUP_V2)
    }

    val v1Bytes = readText(CGROUP_V1_MEMORY_LIMIT_PATH)
        ?.trim()
        ?.toLongOrNull()
        ?.takeIf { value -> value > 0L && value < CGROUP_V1_UNLIMITED_MIN_BYTES }
    return v1Bytes?.let { bytes -> CgroupMemoryCapacity(bytes, RssSoftLimitSource.CGROUP_V1) }
}
