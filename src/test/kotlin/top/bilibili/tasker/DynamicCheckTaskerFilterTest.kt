package top.bilibili.tasker

import top.bilibili.data.DynamicType
import top.bilibili.data.DynamicItem
import top.bilibili.data.ModuleDynamic
import top.bilibili.data.ModuleAuthor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DynamicCheckTaskerFilterTest {
    /**
     * 动态轮询保留直播类型的排除边界，直播通知由独立直播任务负责。
     */
    @Test
    fun `live recommendation type is excluded after successful decoding`() {
        val liveRecommendation = DynamicItem(
            typeStr = DynamicType.DYNAMIC_TYPE_LIVE_RCMD.name,
            basic = DynamicItem.DynamicBasic(commentIdStr = "live-recommendation", commentType = 17, ridStr = "live-recommendation"),
            idStr = "live-recommendation",
            modules = DynamicItem.Modules(
                moduleAuthor = ModuleAuthor(mid = 1L, name = "author", face = ""),
                moduleDynamic = ModuleDynamic(),
            ),
        )
        val regularDynamic = liveRecommendation.copy(
            typeStr = DynamicType.DYNAMIC_TYPE_DRAW.name,
            idStr = "regular-dynamic",
        )

        assertEquals(listOf(regularDynamic), filterDynamicPollingItems(listOf(liveRecommendation, regularDynamic)))
        assertTrue(isExcludedFromDynamicPolling(DynamicType.DYNAMIC_TYPE_LIVE))
        assertFalse(isExcludedFromDynamicPolling(DynamicType.DYNAMIC_TYPE_DRAW))
    }
}
