package top.bilibili.draw

import kotlinx.coroutines.runBlocking
import top.bilibili.core.resource.SkiaDrawSceneFixtures
import top.bilibili.data.ModuleDynamic
import top.bilibili.skia.SkiaManager
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNull

class DynamicAdditionalCardRegressionTest {
    @BeforeTest
    fun setup() = runBlocking {
        SkiaDrawSceneFixtures.prepareEnvironment()
    }

    /**
     * 已撤销预约卡片只剩空字段时应跳过附加图，避免推送一张空白卡片。
     */
    @Test
    fun `revoked reserve additional card should be skipped during draw`() = runBlocking {
        val additional = ModuleDynamic.Additional(
            type = "ADDITIONAL_TYPE_RESERVE",
            reserve = ModuleDynamic.Additional.Reserve(
                rid = 0L,
                upMid = 0L,
                title = "",
                reserveTotal = 0,
                desc1 = null,
                desc2 = null,
                desc3 = null,
                premiere = null,
                state = -1,
                stype = 0,
                jumpUrl = "",
                button = null
            )
        )

        val image = SkiaManager.executeDrawing {
            additional.makeGeneral(this)
        }

        assertNull(image)
    }
}
