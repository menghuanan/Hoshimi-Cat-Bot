package top.bilibili.webui.service

import top.bilibili.BiliAccountConfig
import top.bilibili.BiliConfig
import top.bilibili.BiliData
import top.bilibili.TranslateConfig
import top.bilibili.config.BotConfig
import top.bilibili.config.NapCatConfig
import top.bilibili.config.PlatformConfig
import top.bilibili.connector.PlatformType
import top.bilibili.webui.model.WebUiBiliConfigWriteRequestDto
import top.bilibili.webui.model.WebUiBiliDataWriteRequestDto
import top.bilibili.webui.model.WebUiBotConfigWriteRequestDto
import top.bilibili.webui.model.WebUiRecommendedAction
import top.bilibili.webui.model.WebUiSaveEffectLevel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebUiConfigWriteFacadeTest {
    private val originalDataVersion = BiliData.dataVersion
    private val originalDynamic = BiliData.dynamic.toMutableMap()
    private val originalGroup = BiliData.group.toMutableMap()
    private val originalBlacklist = BiliData.linkParseBlacklistContacts.toMutableSet()

    @AfterTest
    fun restoreBiliDataState() {
        BiliData.dataVersion = originalDataVersion
        BiliData.dynamic = originalDynamic.toMutableMap()
        BiliData.group = originalGroup.toMutableMap()
        BiliData.linkParseBlacklistContacts = originalBlacklist.toMutableSet()
    }

    @Test
    fun `bili config writes should go only through bili config owner and preserve masked secrets`() {
        var currentConfig = BiliConfig(
            adminContact = "onebot11:private:1",
            accountConfig = BiliAccountConfig(cookie = "raw-cookie"),
            translateConfig = TranslateConfig(
                baidu = TranslateConfig.BaiduTranslateConfig(
                    APP_ID = "raw-app-id",
                    SECURITY_KEY = "raw-security-key",
                ),
            ),
        )
        var savedBiliConfig: BiliConfig? = null
        var savedBiliDataCalls = 0
        var savedBotConfigCalls = 0
        val facade = WebUiConfigWriteFacade(
            configFacade = WebUiConfigFacade(
                biliConfigProvider = { currentConfig },
                biliDataProvider = { configuredBiliData(setOf("onebot11:private:9")) },
                botConfigProvider = { BotConfig() },
            ),
            biliConfigProvider = { currentConfig },
            botConfigProvider = { BotConfig() },
            saveBiliConfigAction = { configToSave ->
                savedBiliConfig = configToSave
                true
            },
            saveBiliDataAction = {
                savedBiliDataCalls += 1
                true
            },
            saveBotConfigAction = {
                savedBotConfigCalls += 1
                true
            },
        )

        val snapshotToken = WebUiConfigFacade(biliConfigProvider = { currentConfig }).readBiliConfig().snapshotToken
        val result = facade.saveBiliConfig(
            WebUiBiliConfigWriteRequestDto(
                snapshotToken = snapshotToken,
                adminContact = "onebot11:private:2",
                cookie = "",
                baiduAppId = "new-app-id",
                baiduSecurityKey = "",
                debugMode = true,
            ),
        )

        assertTrue(result.success)
        assertEquals(WebUiSaveEffectLevel.RELOAD_REQUIRED, result.effectiveLevel)
        assertEquals(WebUiRecommendedAction.RELOAD_CONFIG, result.recommendedAction)
        assertEquals("onebot11:private:2", savedBiliConfig?.adminContact)
        assertEquals("raw-cookie", savedBiliConfig?.accountConfig?.cookie)
        assertEquals("new-app-id", savedBiliConfig?.translateConfig?.baidu?.APP_ID)
        assertEquals("raw-security-key", savedBiliConfig?.translateConfig?.baidu?.SECURITY_KEY)
        assertEquals(0, savedBiliDataCalls)
        assertEquals(0, savedBotConfigCalls)
    }

    @Test
    fun `bili data writes should go only through bili data owner`() {
        var savedBiliConfigCalls = 0
        var savedBlacklist: Set<String>? = null
        var savedBotConfigCalls = 0
        val facade = WebUiConfigWriteFacade(
            configFacade = WebUiConfigFacade(
                biliConfigProvider = { BiliConfig() },
                biliDataProvider = { configuredBiliData(setOf("onebot11:private:1")) },
                botConfigProvider = { BotConfig() },
            ),
            biliConfigProvider = { BiliConfig() },
            botConfigProvider = { BotConfig() },
            saveBiliConfigAction = {
                savedBiliConfigCalls += 1
                true
            },
            saveBiliDataAction = { contacts ->
                savedBlacklist = contacts
                true
            },
            saveBotConfigAction = {
                savedBotConfigCalls += 1
                true
            },
        )

        val snapshotToken = WebUiConfigFacade(
            biliDataProvider = { configuredBiliData(setOf("onebot11:private:1")) },
        ).readBiliData().snapshotToken
        val result = facade.saveBiliData(
            WebUiBiliDataWriteRequestDto(
                snapshotToken = snapshotToken,
                linkParseBlacklistContacts = listOf("onebot11:private:2", "onebot11:group:3"),
            ),
        )

        assertTrue(result.success)
        assertEquals(WebUiSaveEffectLevel.APPLIED_IMMEDIATELY, result.effectiveLevel)
        assertEquals(setOf("onebot11:private:2", "onebot11:group:3"), savedBlacklist)
        assertEquals(0, savedBiliConfigCalls)
        assertEquals(0, savedBotConfigCalls)
    }

    /**
     * BiliData 写入失败时，WebUI 不应先污染全局单例内存态，再返回失败结果。
     */
    @Test
    fun `bili data writes should not mutate global state when persistence fails`() {
        var attemptedBlacklist: Set<String>? = null
        val facade = WebUiConfigWriteFacade(
            configFacade = WebUiConfigFacade(
                biliConfigProvider = { BiliConfig() },
                biliDataProvider = { configuredBiliData(setOf("onebot11:private:1")) },
                botConfigProvider = { BotConfig() },
            ),
            saveBiliDataAction = { contacts ->
                attemptedBlacklist = contacts
                false
            },
        )

        val snapshotToken = WebUiConfigFacade(
            biliDataProvider = { configuredBiliData(setOf("onebot11:private:1")) },
        ).readBiliData().snapshotToken
        val expectedBlacklistBeforeSave = BiliData.linkParseBlacklistContacts.toMutableSet()
        val result = facade.saveBiliData(
            WebUiBiliDataWriteRequestDto(
                snapshotToken = snapshotToken,
                linkParseBlacklistContacts = listOf("onebot11:private:2", "onebot11:group:3"),
            ),
        )

        assertFalse(result.success)
        assertTrue(result.validationErrors.isEmpty())
        assertEquals("REJECTED_PERSISTENCE", result.effectiveLevel.name)
        assertEquals("RETRY_SAVE", result.recommendedAction.name)
        assertEquals(expectedBlacklistBeforeSave, BiliData.linkParseBlacklistContacts)
        assertEquals(setOf("onebot11:private:2", "onebot11:group:3"), attemptedBlacklist)
    }

    /**
     * `BiliData.yml` 发生快照冲突时必须先拒绝写入，再把新的 snapshot token 回传给前端刷新。
     */
    @Test
    fun `bili data writes should reject stale snapshots before owner save runs`() {
        var saveCalls = 0
        val facade = WebUiConfigWriteFacade(
            configFacade = WebUiConfigFacade(
                biliConfigProvider = { BiliConfig() },
                biliDataProvider = { configuredBiliData(setOf("onebot11:private:1")) },
                botConfigProvider = { BotConfig() },
            ),
            saveBiliDataAction = {
                saveCalls += 1
                true
            },
        )

        val staleToken = WebUiConfigFacade(
            biliDataProvider = { configuredBiliData(setOf("onebot11:private:2")) },
        ).readBiliData().snapshotToken
        val result = facade.saveBiliData(
            WebUiBiliDataWriteRequestDto(
                snapshotToken = staleToken,
                linkParseBlacklistContacts = listOf("onebot11:private:9"),
            ),
        )

        assertFalse(result.success)
        assertTrue(result.conflictDetected)
        assertEquals(WebUiSaveEffectLevel.REJECTED_CONFLICT, result.effectiveLevel)
        assertEquals(WebUiRecommendedAction.REFRESH_AND_RETRY, result.recommendedAction)
        assertEquals(0, saveCalls)
    }

    @Test
    fun `bot config writes should go only through bot config owner and preserve masked token`() {
        var currentBotConfig = BotConfig(
            platform = PlatformConfig(
                type = PlatformType.ONEBOT11,
                adapter = "onebot11",
                onebot11 = NapCatConfig(
                    host = "127.0.0.1",
                    port = 3001,
                    token = "raw-token",
                ),
            ),
        )
        var savedBiliConfigCalls = 0
        var savedBiliDataCalls = 0
        var savedBotConfig: BotConfig? = null
        val facade = WebUiConfigWriteFacade(
            configFacade = WebUiConfigFacade(
                biliConfigProvider = { BiliConfig() },
                biliDataProvider = { configuredBiliData(emptySet()) },
                botConfigProvider = { currentBotConfig },
            ),
            biliConfigProvider = { BiliConfig() },
            botConfigProvider = { currentBotConfig },
            saveBiliConfigAction = {
                savedBiliConfigCalls += 1
                true
            },
            saveBiliDataAction = {
                savedBiliDataCalls += 1
                true
            },
            saveBotConfigAction = { botConfig ->
                savedBotConfig = botConfig
                true
            },
        )

        val snapshotToken = WebUiConfigFacade(botConfigProvider = { currentBotConfig }).readBotConfig().snapshotToken
        val result = facade.saveBotConfig(
            WebUiBotConfigWriteRequestDto(
                snapshotToken = snapshotToken,
                platformType = "ONEBOT11",
                adapter = "onebot11",
                oneBot11Host = "10.0.0.2",
                oneBot11Port = 3100,
                oneBot11Token = "",
            ),
        )

        assertTrue(result.success)
        assertEquals(WebUiSaveEffectLevel.RESTART_REQUIRED, result.effectiveLevel)
        assertEquals(WebUiRecommendedAction.REQUEST_RESTART, result.recommendedAction)
        assertEquals("10.0.0.2", savedBotConfig?.selectedOneBot11Config()?.host)
        assertEquals(3100, savedBotConfig?.selectedOneBot11Config()?.port)
        assertEquals("raw-token", savedBotConfig?.selectedOneBot11Config()?.token)
        assertEquals(0, savedBiliConfigCalls)
        assertEquals(0, savedBiliDataCalls)
    }

    /**
     * bot.yml 持久化失败时必须向上返回失败，而不是把写盘异常误标成校验失败。
     */
    @Test
    fun `bot config writes should report persistence failure separately from validation`() {
        val facade = WebUiConfigWriteFacade(
            configFacade = WebUiConfigFacade(
                biliConfigProvider = { BiliConfig() },
                biliDataProvider = { configuredBiliData(emptySet()) },
                botConfigProvider = {
                    BotConfig(
                        platform = PlatformConfig(
                            type = PlatformType.ONEBOT11,
                            adapter = "onebot11",
                            onebot11 = NapCatConfig(host = "127.0.0.1", port = 3001, token = "raw-token"),
                        ),
                    )
                },
            ),
            botConfigProvider = {
                BotConfig(
                    platform = PlatformConfig(
                        type = PlatformType.ONEBOT11,
                        adapter = "onebot11",
                        onebot11 = NapCatConfig(host = "127.0.0.1", port = 3001, token = "raw-token"),
                    ),
                )
            },
            saveBotConfigAction = {
                throw IllegalStateException("disk write failed")
            },
        )

        val snapshotToken = WebUiConfigFacade(
            botConfigProvider = {
                BotConfig(
                    platform = PlatformConfig(
                        type = PlatformType.ONEBOT11,
                        adapter = "onebot11",
                        onebot11 = NapCatConfig(host = "127.0.0.1", port = 3001, token = "raw-token"),
                    ),
                )
            },
        ).readBotConfig().snapshotToken
        val result = facade.saveBotConfig(
            WebUiBotConfigWriteRequestDto(
                snapshotToken = snapshotToken,
                platformType = "ONEBOT11",
                adapter = "onebot11",
                oneBot11Host = "10.0.0.2",
                oneBot11Port = 3100,
                oneBot11Token = "",
            ),
        )

        assertFalse(result.success)
        assertTrue(result.validationErrors.isEmpty())
        assertEquals("REJECTED_PERSISTENCE", result.effectiveLevel.name)
        assertEquals("RETRY_SAVE", result.recommendedAction.name)
    }

    /**
     * BiliConfig 持久化失败时也必须返回持久化失败语义，而不是校验失败语义。
     */
    @Test
    fun `bili config writes should report persistence failure separately from validation`() {
        val facade = WebUiConfigWriteFacade(
            configFacade = WebUiConfigFacade(
                biliConfigProvider = {
                    BiliConfig(
                        adminContact = "onebot11:private:1",
                        accountConfig = BiliAccountConfig(cookie = "raw-cookie"),
                    )
                },
                biliDataProvider = { configuredBiliData(emptySet()) },
                botConfigProvider = { BotConfig() },
            ),
            biliConfigProvider = {
                BiliConfig(
                    adminContact = "onebot11:private:1",
                    accountConfig = BiliAccountConfig(cookie = "raw-cookie"),
                )
            },
            saveBiliConfigAction = {
                throw IllegalStateException("disk write failed")
            },
        )

        val snapshotToken = WebUiConfigFacade(
            biliConfigProvider = {
                BiliConfig(
                    adminContact = "onebot11:private:1",
                    accountConfig = BiliAccountConfig(cookie = "raw-cookie"),
                )
            },
        ).readBiliConfig().snapshotToken
        val result = facade.saveBiliConfig(
            WebUiBiliConfigWriteRequestDto(
                snapshotToken = snapshotToken,
                adminContact = "onebot11:private:2",
                cookie = "",
                baiduAppId = "new-app-id",
                baiduSecurityKey = "",
                debugMode = true,
            ),
        )

        assertFalse(result.success)
        assertTrue(result.validationErrors.isEmpty())
        assertEquals("REJECTED_PERSISTENCE", result.effectiveLevel.name)
        assertEquals("RETRY_SAVE", result.recommendedAction.name)
    }

    /**
     * bot.yml 的校验失败必须在 owner 写入前返回，避免无效平台参数被落盘。
     */
    @Test
    fun `bot config writes should reject invalid values without invoking owner save`() {
        var saveCalls = 0
        val facade = WebUiConfigWriteFacade(
            configFacade = WebUiConfigFacade(
                biliConfigProvider = { BiliConfig() },
                biliDataProvider = { configuredBiliData(emptySet()) },
                botConfigProvider = {
                    BotConfig(
                        platform = PlatformConfig(
                            type = PlatformType.ONEBOT11,
                            adapter = "onebot11",
                            onebot11 = NapCatConfig(host = "127.0.0.1", port = 3001),
                        ),
                    )
                },
            ),
            botConfigProvider = {
                BotConfig(
                    platform = PlatformConfig(
                        type = PlatformType.ONEBOT11,
                        adapter = "onebot11",
                        onebot11 = NapCatConfig(host = "127.0.0.1", port = 3001),
                    ),
                )
            },
            saveBotConfigAction = {
                saveCalls += 1
                true
            },
        )

        val snapshotToken = WebUiConfigFacade(
            botConfigProvider = {
                BotConfig(
                    platform = PlatformConfig(
                        type = PlatformType.ONEBOT11,
                        adapter = "onebot11",
                        onebot11 = NapCatConfig(host = "127.0.0.1", port = 3001),
                    ),
                )
            },
        ).readBotConfig().snapshotToken
        val result = facade.saveBotConfig(
            WebUiBotConfigWriteRequestDto(
                snapshotToken = snapshotToken,
                platformType = "INVALID",
                adapter = "invalid",
                oneBot11Host = "",
                oneBot11Port = 0,
                oneBot11Token = "",
            ),
        )

        assertFalse(result.success)
        assertTrue(result.validationErrors.isNotEmpty())
        assertEquals(WebUiSaveEffectLevel.REJECTED_VALIDATION, result.effectiveLevel)
        assertEquals(WebUiRecommendedAction.FIX_VALIDATION_ERRORS, result.recommendedAction)
        assertEquals(0, saveCalls)
    }

    @Test
    fun `stale snapshot tokens should be rejected before any owner save runs`() {
        var savedBiliConfigCalls = 0
        var currentConfig = BiliConfig(adminContact = "onebot11:private:1")
        val facade = WebUiConfigWriteFacade(
            configFacade = WebUiConfigFacade(
                biliConfigProvider = { currentConfig },
                biliDataProvider = { configuredBiliData(emptySet()) },
                botConfigProvider = { BotConfig() },
            ),
            biliConfigProvider = { currentConfig },
            botConfigProvider = { BotConfig() },
            saveBiliConfigAction = {
                savedBiliConfigCalls += 1
                true
            },
        )

        val staleToken = WebUiConfigFacade(
            biliConfigProvider = { BiliConfig(adminContact = "onebot11:private:999") },
        ).readBiliConfig().snapshotToken
        val result = facade.saveBiliConfig(
            WebUiBiliConfigWriteRequestDto(
                snapshotToken = staleToken,
                adminContact = "onebot11:private:2",
                cookie = "",
                baiduAppId = "",
                baiduSecurityKey = "",
                debugMode = false,
            ),
        )

        assertFalse(result.success)
        assertTrue(result.conflictDetected)
        assertEquals(WebUiSaveEffectLevel.REJECTED_CONFLICT, result.effectiveLevel)
        assertEquals(WebUiRecommendedAction.REFRESH_AND_RETRY, result.recommendedAction)
        assertEquals(0, savedBiliConfigCalls)
    }

    private fun configuredBiliData(blacklistContacts: Set<String>) = BiliData.apply {
        dataVersion = 4
        dynamic = mutableMapOf()
        group = mutableMapOf()
        linkParseBlacklistContacts = blacklistContacts.toMutableSet()
    }
}
