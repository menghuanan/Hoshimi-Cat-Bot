package top.bilibili

import com.sun.management.HotSpotDiagnosticMXBean
import org.slf4j.LoggerFactory
import java.lang.management.ManagementFactory

private val jvmRuntimeDiagnosticsLogger = LoggerFactory.getLogger("JvmRuntimeDiagnostics")

private val startupVmOptionNames = listOf(
    "MaxMetaspaceSize",
    "ReservedCodeCacheSize",
    "InitialCodeCacheSize",
    "TieredStopAtLevel",
    "CICompilerCount",
    "NativeMemoryTracking",
)

/**
 * 读取 HotSpot 当前进程的 VM option；不支持该管理接口时返回 null，让调用方显式降级。
 */
internal fun readHotSpotVmOption(optionName: String): String? = runCatching {
    ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean::class.java)
        ?.getVMOption(optionName)
        ?.value
}.getOrNull()

/**
 * 构造启动期 JVM 实际参数摘要，确保部署来源与运行 JVM 的有效值能在同一条日志中核对。
 */
internal fun buildJvmRuntimeSummary(
    deployment: String = System.getProperty("app.deployment")?.trim().orEmpty().ifBlank { "unknown" },
    vmOptionReader: (String) -> String? = ::readHotSpotVmOption,
    skikoResourceCacheLimitBytes: Long = SkikoInitializer.effectiveResourceCacheLimitBytes(),
): String {
    val optionSummary = startupVmOptionNames.joinToString(separator = ", ") { optionName ->
        "$optionName=${vmOptionReader(optionName) ?: "unavailable"}"
    }
    return "deployment=$deployment, $optionSummary, SkikoResourceCacheLimit=$skikoResourceCacheLimitBytes"
}

/**
 * 在 Skiko 初始化完成后记录当前进程真正生效的 JVM 与 native 缓存参数。
 */
internal fun logJvmRuntimeSummary() {
    jvmRuntimeDiagnosticsLogger.info("JVM effective runtime options: {}", buildJvmRuntimeSummary())
}
