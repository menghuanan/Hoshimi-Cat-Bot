package top.bilibili.draw

import top.bilibili.BiliConfig
import top.bilibili.BiliConfigManager
import top.bilibili.FooterConfig
import top.bilibili.ImageConfig
import top.bilibili.TemplateConfig
import top.bilibili.core.deepCopyForRuntimeSnapshot
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DynamicDrawRuntimeConfigRegressionTest {
    private val originalConfig = runCatching {
        BiliConfigManager.config.deepCopyForRuntimeSnapshot()
    }.getOrNull()

    @AfterTest
    fun restoreRuntimeConfig() {
        originalConfig?.let { snapshot ->
            BiliConfigManager.installConfigRuntimeSnapshot(snapshot)
        }
    }

    /**
     * 绘图派生尺寸必须跟随最新运行态配置，避免热重载后仍沿用旧 quality lazy。
     */
    @Test
    fun `draw quality should follow latest runtime config`() {
        BiliConfigManager.installConfigRuntimeSnapshot(
            BiliConfig(imageConfig = ImageConfig(quality = "800w")),
        )
        val firstWidth = quality.imageWidth

        BiliConfigManager.installConfigRuntimeSnapshot(
            BiliConfig(imageConfig = ImageConfig(quality = "1200w")),
        )
        val secondWidth = quality.imageWidth

        assertEquals(800, firstWidth)
        assertEquals(1200, secondWidth)
    }

    /**
     * 页脚对齐属于绘图派生样式，必须在运行态配置切换后重新计算。
     */
    @Test
    fun `footer paragraph style should follow latest runtime config`() {
        BiliConfigManager.installConfigRuntimeSnapshot(
            BiliConfig(templateConfig = TemplateConfig(footer = FooterConfig(footerAlign = "LEFT"))),
        )
        val left = footerAlignmentName()

        BiliConfigManager.installConfigRuntimeSnapshot(
            BiliConfig(templateConfig = TemplateConfig(footer = FooterConfig(footerAlign = "RIGHT"))),
        )
        val right = footerAlignmentName()

        assertEquals("LEFT", left)
        assertEquals("RIGHT", right)
    }
}
