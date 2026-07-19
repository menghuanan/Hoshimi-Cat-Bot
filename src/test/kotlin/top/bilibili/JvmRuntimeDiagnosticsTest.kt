package top.bilibili

import kotlin.test.Test
import kotlin.test.assertTrue

class JvmRuntimeDiagnosticsTest {
    /** 启动摘要必须同时给出部署来源、关键 VM option 和 Skia 实际缓存上限。 */
    @Test
    fun `startup diagnostics should include effective jvm and skia limits`() {
        val options = mapOf(
            "MaxMetaspaceSize" to "67108864",
            "ReservedCodeCacheSize" to "50331648",
            "InitialCodeCacheSize" to "33554432",
            "TieredStopAtLevel" to "4",
            "CICompilerCount" to "2",
            "NativeMemoryTracking" to "summary",
        )

        val summary = buildJvmRuntimeSummary(
            deployment = "linux-release",
            vmOptionReader = options::get,
            skikoResourceCacheLimitBytes = 50_331_648L,
        )

        assertTrue(summary.contains("deployment=linux-release"))
        options.forEach { (name, value) -> assertTrue(summary.contains("$name=$value")) }
        assertTrue(summary.contains("SkikoResourceCacheLimit=50331648"))
    }
}
