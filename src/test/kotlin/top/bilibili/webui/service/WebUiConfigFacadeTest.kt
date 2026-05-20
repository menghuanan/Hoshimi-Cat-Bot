package top.bilibili.webui.service

import top.bilibili.BiliConfig
import top.bilibili.BiliData
import top.bilibili.config.BotConfig
import top.bilibili.config.NapCatConfig
import top.bilibili.config.PlatformConfig
import top.bilibili.webui.model.WebUiBiliConfigWriteRequestDto
import top.bilibili.webui.model.WebUiBiliDataWriteRequestDto
import top.bilibili.webui.model.WebUiBotConfigWriteRequestDto
import top.bilibili.webui.model.WebUiFieldCapability
import top.bilibili.webui.model.WebUiRecommendedAction
import top.bilibili.webui.model.WebUiSaveEffectLevel
import top.bilibili.webui.model.WebUiConfigSaveResultDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
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

    @Test
    fun `each config snapshot should carry a backend generated snapshot token`() {
        val baselineFacade = WebUiConfigFacade(
            biliConfigProvider = {
                BiliConfig(
                    accountConfig = top.bilibili.BiliAccountConfig(cookie = "cookie-a"),
                )
            },
            biliDataProvider = {
                BiliData.apply {
                    dataVersion = 4
                    linkParseBlacklistContacts = mutableSetOf("onebot11:private:1")
                }
            },
            botConfigProvider = {
                BotConfig(
                    platform = PlatformConfig(
                        onebot11 = NapCatConfig(host = "127.0.0.1", port = 3001, token = "token-a"),
                    ),
                )
            },
        )
        val changedFacade = WebUiConfigFacade(
            biliConfigProvider = {
                BiliConfig(
                    accountConfig = top.bilibili.BiliAccountConfig(cookie = "cookie-b"),
                )
            },
            biliDataProvider = {
                BiliData.apply {
                    dataVersion = 4
                    linkParseBlacklistContacts = mutableSetOf("onebot11:private:2")
                }
            },
            botConfigProvider = {
                BotConfig(
                    platform = PlatformConfig(
                        onebot11 = NapCatConfig(host = "127.0.0.1", port = 3002, token = "token-b"),
                    ),
                )
            },
        )

        val baselineBiliConfig = baselineFacade.readBiliConfig()
        val baselineBiliData = baselineFacade.readBiliData()
        val baselineBotConfig = baselineFacade.readBotConfig()
        val changedBiliConfig = changedFacade.readBiliConfig()
        val changedBiliData = changedFacade.readBiliData()
        val changedBotConfig = changedFacade.readBotConfig()

        assertTrue(baselineBiliConfig.snapshotToken.isNotBlank())
        assertTrue(baselineBiliData.snapshotToken.isNotBlank())
        assertTrue(baselineBotConfig.snapshotToken.isNotBlank())
        assertNotEquals(baselineBiliConfig.snapshotToken, changedBiliConfig.snapshotToken)
        assertNotEquals(baselineBiliData.snapshotToken, changedBiliData.snapshotToken)
        assertNotEquals(baselineBotConfig.snapshotToken, changedBotConfig.snapshotToken)
    }

    @Test
    fun `config write requests should stay file scoped`() {
        val biliConfigRequest = WebUiBiliConfigWriteRequestDto(
            snapshotToken = "token-a",
            adminContact = "onebot11:private:1",
            cookie = "",
            baiduAppId = "app-id",
            baiduSecurityKey = "",
            debugMode = true,
        )
        val biliDataRequest = WebUiBiliDataWriteRequestDto(
            snapshotToken = "token-b",
            linkParseBlacklistContacts = listOf("onebot11:group:2"),
        )
        val botConfigRequest = WebUiBotConfigWriteRequestDto(
            snapshotToken = "token-c",
            platformType = "ONEBOT11",
            adapter = "onebot11",
            oneBot11Host = "127.0.0.1",
            oneBot11Port = 3001,
            oneBot11Token = "",
        )

        assertEquals("token-a", biliConfigRequest.snapshotToken)
        assertEquals("token-b", biliDataRequest.snapshotToken)
        assertEquals("token-c", botConfigRequest.snapshotToken)
        assertEquals("onebot11:private:1", biliConfigRequest.adminContact)
        assertEquals(listOf("onebot11:group:2"), biliDataRequest.linkParseBlacklistContacts)
        assertEquals("127.0.0.1", botConfigRequest.oneBot11Host)
    }

    @Test
    fun `save result dto should distinguish validation conflict reload and restart outcomes`() {
        val validationRejected = WebUiConfigSaveResultDto(
            success = false,
            persisted = false,
            conflictDetected = false,
            validationErrors = listOf("host is invalid"),
            effectiveLevel = WebUiSaveEffectLevel.REJECTED_VALIDATION,
            recommendedAction = WebUiRecommendedAction.FIX_VALIDATION_ERRORS,
            snapshotToken = "token-validation",
        )
        val conflictRejected = WebUiConfigSaveResultDto(
            success = false,
            persisted = false,
            conflictDetected = true,
            validationErrors = emptyList(),
            effectiveLevel = WebUiSaveEffectLevel.REJECTED_CONFLICT,
            recommendedAction = WebUiRecommendedAction.REFRESH_AND_RETRY,
            snapshotToken = "token-conflict",
        )
        val reloadRequired = WebUiConfigSaveResultDto(
            success = true,
            persisted = true,
            conflictDetected = false,
            validationErrors = emptyList(),
            effectiveLevel = WebUiSaveEffectLevel.RELOAD_REQUIRED,
            recommendedAction = WebUiRecommendedAction.RELOAD_CONFIG,
            snapshotToken = "token-reload",
        )
        val restartRequired = WebUiConfigSaveResultDto(
            success = true,
            persisted = true,
            conflictDetected = false,
            validationErrors = emptyList(),
            effectiveLevel = WebUiSaveEffectLevel.RESTART_REQUIRED,
            recommendedAction = WebUiRecommendedAction.REQUEST_RESTART,
            snapshotToken = "token-restart",
        )

        assertFalse(validationRejected.success)
        assertTrue(conflictRejected.conflictDetected)
        assertEquals(WebUiSaveEffectLevel.RELOAD_REQUIRED, reloadRequired.effectiveLevel)
        assertEquals(WebUiRecommendedAction.REQUEST_RESTART, restartRequired.recommendedAction)
        assertNotNull(restartRequired.snapshotToken)
    }
}
