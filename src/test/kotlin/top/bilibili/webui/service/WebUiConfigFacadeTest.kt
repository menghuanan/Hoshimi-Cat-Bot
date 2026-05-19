package top.bilibili.webui.service

import top.bilibili.BiliConfig
import top.bilibili.BiliData
import top.bilibili.config.BotConfig
import top.bilibili.config.NapCatConfig
import top.bilibili.config.PlatformConfig
import top.bilibili.webui.model.WebUiFieldCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WebUiConfigFacadeTest {
    @Test
    fun `config responses should stay split by file ownership`() {
        val facade = WebUiConfigFacade(
            biliConfigProvider = { BiliConfig() },
            biliDataProvider = { BiliData.apply { dataVersion = 4 } },
            botConfigProvider = { BotConfig() },
        )

        assertEquals("BiliConfig.yml", facade.readBiliConfig().sourceFile)
        assertEquals("BiliData.yml", facade.readBiliData().sourceFile)
        assertEquals("bot.yml", facade.readBotConfig().sourceFile)
    }

    @Test
    fun `sensitive fields should be masked in read only config dto`() {
        val biliConfig = BiliConfig(
            accountConfig = top.bilibili.BiliAccountConfig(cookie = "SESSDATA=raw-cookie"),
            translateConfig = top.bilibili.TranslateConfig(
                baidu = top.bilibili.TranslateConfig.BaiduTranslateConfig(
                    APP_ID = "app-id",
                    SECURITY_KEY = "raw-secret",
                ),
            ),
        )
        val botConfig = BotConfig(
            platform = PlatformConfig(
                onebot11 = NapCatConfig(token = "napcat-token"),
            ),
        )
        val facade = WebUiConfigFacade(
            biliConfigProvider = { biliConfig },
            biliDataProvider = { BiliData },
            botConfigProvider = { botConfig },
        )

        val biliConfigDto = facade.readBiliConfig()
        val botConfigDto = facade.readBotConfig()
        val cookieField = biliConfigDto.fields.first { it.key == "accountConfig.cookie" }
        val baiduSecretField = biliConfigDto.fields.first { it.key == "translateConfig.baidu.SECURITY_KEY" }
        val tokenField = botConfigDto.fields.first { it.key == "platform.onebot11.token" }

        assertEquals(WebUiFieldCapability.MASKED, cookieField.capability)
        assertEquals(WebUiFieldCapability.MASKED, baiduSecretField.capability)
        assertEquals(WebUiFieldCapability.MASKED, tokenField.capability)
        assertNotEquals("SESSDATA=raw-cookie", cookieField.value)
        assertNotEquals("raw-secret", baiduSecretField.value)
        assertNotEquals("napcat-token", tokenField.value)
    }

    @Test
    fun `system maintained fields should be marked non editable`() {
        val facade = WebUiConfigFacade(
            biliConfigProvider = { BiliConfig() },
            biliDataProvider = {
                BiliData.apply {
                    dataVersion = 4
                    dynamic.clear()
                    group.clear()
                }
            },
            botConfigProvider = { BotConfig(firstRunFlag = 1) },
        )

        val biliDataField = facade.readBiliData().fields.first { it.key == "dataVersion" }
        val botConfigField = facade.readBotConfig().fields.first { it.key == "firstRunFlag" }

        assertEquals(WebUiFieldCapability.SYSTEM_MANAGED, biliDataField.capability)
        assertEquals(WebUiFieldCapability.SYSTEM_MANAGED, botConfigField.capability)
        assertTrue(!biliDataField.editable)
        assertTrue(!botConfigField.editable)
    }
}
