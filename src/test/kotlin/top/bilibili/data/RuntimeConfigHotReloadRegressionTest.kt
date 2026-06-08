package top.bilibili.data

import top.bilibili.BiliConfig
import top.bilibili.BiliConfigManager
import top.bilibili.PushConfig
import top.bilibili.core.deepCopyForRuntimeSnapshot
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeConfigHotReloadRegressionTest {
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
     * 链接构造必须读取最新运行态开关，避免 WebUI 热重载后继续使用旧短链策略。
     */
    @Test
    fun `video link should follow latest short link runtime config`() {
        BiliConfigManager.installConfigRuntimeSnapshot(
            BiliConfig(pushConfig = PushConfig(toShortLink = false)),
        )
        val longLink = VIDEO_LINK("BV1abcdefghi")

        BiliConfigManager.installConfigRuntimeSnapshot(
            BiliConfig(pushConfig = PushConfig(toShortLink = true)),
        )
        val shortLink = VIDEO_LINK("BV1abcdefghi")

        assertEquals("$BASE_VIDEO/BV1abcdefghi", longLink)
        assertEquals("$BASE_SHORT/BV1abcdefghi", shortLink)
    }
}
