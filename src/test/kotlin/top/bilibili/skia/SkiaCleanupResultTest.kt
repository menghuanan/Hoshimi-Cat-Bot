package top.bilibili.skia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkiaCleanupResultTest {
    /**
     * 未执行 purge 的清理不得增加成功次数或推进紧急冷却时间。
     */
    @Test
    fun `failed cleanup should preserve success counters and cooldown`() {
        val state = SkiaCleanupState(successfulCleanupCount = 4L, lastEmergencyCleanupAt = 1_000L)

        val updated = state.afterAttempt(
            result = SkiaCleanupResult.SKIPPED,
            emergencyAttemptAt = 5_000L,
        )

        assertEquals(4L, updated.successfulCleanupCount)
        assertEquals(1_000L, updated.lastEmergencyCleanupAt)
    }

    /**
     * purge 实际执行后才记录成功次数，并在紧急路径推进冷却时间。
     */
    @Test
    fun `successful emergency cleanup should update counter and cooldown`() {
        val state = SkiaCleanupState(successfulCleanupCount = 4L, lastEmergencyCleanupAt = 1_000L)

        val updated = state.afterAttempt(
            result = SkiaCleanupResult.COMPLETED,
            emergencyAttemptAt = 5_000L,
        )

        assertEquals(5L, updated.successfulCleanupCount)
        assertEquals(5_000L, updated.lastEmergencyCleanupAt)
        assertTrue(SkiaCleanupResult.COMPLETED.completed)
        assertFalse(SkiaCleanupResult.SKIPPED.completed)
    }
}
