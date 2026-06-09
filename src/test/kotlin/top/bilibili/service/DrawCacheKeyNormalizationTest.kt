package top.bilibili.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals

class DrawCacheKeyNormalizationTest {
    @Test
    fun `cache path should hash equivalent colors by normalized lower-case value`() {
        val uppercase = DrawCacheKeyService.dynamicPath(1L, "2", "group:10001", "#FF0000")
        val lowercase = DrawCacheKeyService.dynamicPath(1L, "2", "group:10001", "#ff0000")

        assertEquals(lowercase, uppercase, "cache key should deduplicate equivalent color inputs")
    }

    @Test
    fun `subject scoped normalization should be idempotent for stored color strings`() {
        val once = normalizeSubjectScopedGradientColor("#FF0000", NormalizationContext.USER_COMMAND)
        val twice = once?.normalizedColor?.let {
            normalizeSubjectScopedGradientColor(it, NormalizationContext.USER_COMMAND)
        }

        assertNotNull(once)
        assertNotNull(twice)
        assertEquals(once.normalizedColor, twice.normalizedColor)
    }

    @Test
    fun `QQ official string subjects should not collapse to global cache scope`() {
        val first = DrawCacheKeyService.dynamicPath(1L, "2", "qq_official:group:channel-a", "#ff0000")
        val second = DrawCacheKeyService.dynamicPath(1L, "2", "qq_official:group:channel-b", "#ff0000")

        assertNotEquals(first, second)
    }
}
