package top.bilibili.webui.service

import top.bilibili.BiliAccountConfig
import top.bilibili.CacheConfig
import top.bilibili.CheckConfig
import top.bilibili.BiliConfig
import top.bilibili.BiliData
import top.bilibili.EnableConfig
import top.bilibili.FooterConfig
import top.bilibili.ImageConfig
import top.bilibili.LinkResolveConfig
import top.bilibili.ProxyConfig
import top.bilibili.PushConfig
import top.bilibili.TemplateConfig
import top.bilibili.TimeDisplayMode
import top.bilibili.TranslateConfig
import top.bilibili.config.BotConfig
import top.bilibili.config.GroupAdminConfig
import top.bilibili.config.NapCatConfig
import top.bilibili.config.PlatformConfig
import top.bilibili.config.QQOfficialConfig
import top.bilibili.config.TargetConfig
import top.bilibili.connector.PlatformType
import top.bilibili.service.TriggerMode
import top.bilibili.utils.CacheType
import top.bilibili.webui.config.WebUiConfig
import top.bilibili.webui.model.WebUiBiliConfigWriteRequestDto
import top.bilibili.webui.model.WebUiBiliDataWriteRequestDto
import top.bilibili.webui.model.WebUiBotConfigWriteRequestDto
import top.bilibili.webui.model.WebUiGroupAdminConfigWriteDto
import top.bilibili.webui.model.WebUiRecommendedAction
import top.bilibili.webui.model.WebUiSaveEffectLevel
import top.bilibili.webui.model.WebUiTargetConfigWriteDto
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
    fun `bili config writes should persist all system settings fields and preserve secrets`() {
        var currentConfig = BiliConfig(
            admin = 1L,
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
        val facade = WebUiConfigWriteFacade(
            configFacade = WebUiConfigFacade(
                biliConfigProvider = { currentConfig },
                biliDataProvider = { configuredBiliData(emptySet()) },
                botConfigProvider = { BotConfig() },
            ),
            biliConfigProvider = { currentConfig },
            saveBiliConfigAction = { configToSave ->
                savedBiliConfig = configToSave
                currentConfig = configToSave
                true
            },
        )

        val snapshotToken = WebUiConfigFacade(biliConfigProvider = { currentConfig }).readBiliConfig().snapshotToken
        val result = facade.saveBiliConfig(
            WebUiBiliConfigWriteRequestDto(
                snapshotToken = snapshotToken,
                admin = 42L,
                adminContact = "onebot11:private:42",
                debugMode = true,
                drawEnable = false,
                pushDrawEnable = false,
                notifyEnable = false,
                liveCloseNotifyEnable = false,
                lowSpeedEnable = false,
                translateEnable = true,
                proxyEnable = true,
                cacheClearEnable = false,
                cookie = "",
                autoFollow = false,
                followGroup = "NewGroup",
                proxies = listOf("http://proxy.local:8080"),
                lowSpeedTime = "23-7",
                lowSpeedRange = "80-180",
                normalRange = "30-60",
                checkReportInterval = 12,
                timeout = 30,
                quality = "1500w",
                theme = "v4",
                font = "Noto Sans",
                defaultColor = "#112233",
                cardOrnament = "QrCode",
                timeDisplayMode = "RELATIVE",
                hueStep = 45,
                lockSB = false,
                saturation = 0.4f,
                brightness = 0.8f,
                leftBadgeEnable = false,
                rightBadgeEnable = true,
                dynamicFooter = "dynamic footer",
                liveFooter = "live footer",
                footerAlign = "CENTER",
                downloadOriginal = false,
                cacheExpires = mapOf("DRAW" to 3, "IMAGES" to 4, "EMOJI" to 5, "USER" to 6, "OTHER" to 7),
                messageInterval = 200L,
                pushInterval = 800L,
                toShortLink = true,
                defaultDynamicPush = "TwoMsg",
                defaultLivePush = "TextOnly",
                defaultLiveClose = "ComplexMsg",
                dynamicPush = mapOf("DrawOnly" to "{draw}", "TextOnly" to "{content}"),
                livePush = mapOf("DrawOnly" to "{draw}", "TextOnly" to "{title}"),
                liveClose = mapOf("SimpleMsg" to "{name} 直播结束啦!", "ComplexMsg" to "{duration}"),
                triggerMode = "Always",
                linkResolveDrawEnable = false,
                linkResolveReturnLink = true,
                cutLine = "\n\ntranslated\n",
                baiduAppId = "new-app-id",
                baiduSecurityKey = "",
            ),
        )

        assertTrue(result.success)
        assertEquals(42L, savedBiliConfig?.admin)
        assertEquals("onebot11:private:42", savedBiliConfig?.adminContact)
        assertEquals(false, savedBiliConfig?.enableConfig?.drawEnable)
        assertEquals("raw-cookie", savedBiliConfig?.accountConfig?.cookie)
        assertEquals(false, savedBiliConfig?.accountConfig?.autoFollow)
        assertEquals(listOf("http://proxy.local:8080"), savedBiliConfig?.proxyConfig?.proxy)
        assertEquals("1500w", savedBiliConfig?.imageConfig?.quality)
        assertEquals(TimeDisplayMode.RELATIVE, savedBiliConfig?.imageConfig?.timeDisplayMode)
        assertEquals("CENTER", savedBiliConfig?.templateConfig?.footer?.footerAlign)
        assertEquals(3, savedBiliConfig?.cacheConfig?.expires?.get(CacheType.DRAW))
        assertEquals("Always", savedBiliConfig?.linkResolveConfig?.triggerMode?.name)
        assertEquals("new-app-id", savedBiliConfig?.translateConfig?.baidu?.APP_ID)
        assertEquals("raw-security-key", savedBiliConfig?.translateConfig?.baidu?.SECURITY_KEY)
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
     * 代理地址是 write-only 敏感列表；局部保存未明确提交新代理时必须保留 owner 当前值。
     */
    @Test
    fun `bili config writes should preserve existing proxies when replacement is omitted`() {
        var currentConfig = BiliConfig(
            adminContact = "onebot11:private:1",
            proxyConfig = ProxyConfig(
                proxy = listOf("http://user:password@proxy.local:8080"),
            ),
        )
        var savedBiliConfig: BiliConfig? = null
        val facade = WebUiConfigWriteFacade(
            configFacade = WebUiConfigFacade(
                biliConfigProvider = { currentConfig },
                biliDataProvider = { configuredBiliData(emptySet()) },
                botConfigProvider = { BotConfig() },
            ),
            biliConfigProvider = { currentConfig },
            saveBiliConfigAction = { configToSave ->
                savedBiliConfig = configToSave
                currentConfig = configToSave
                true
            },
        )

        val snapshotToken = WebUiConfigFacade(biliConfigProvider = { currentConfig }).readBiliConfig().snapshotToken
        val result = facade.saveBiliConfig(
            WebUiBiliConfigWriteRequestDto(
                snapshotToken = snapshotToken,
                adminContact = "onebot11:private:2",
                proxies = emptyList(),
            ),
        )

        assertTrue(result.success)
        assertEquals(listOf("http://user:password@proxy.local:8080"), savedBiliConfig?.proxyConfig?.proxy)
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

    @Test
    fun `bot config writes should persist visible fields and preserve hidden webui internals`() {
        var currentBotConfig = BotConfig(
            platform = PlatformConfig(
                type = PlatformType.ONEBOT11,
                adapter = "onebot11",
                onebot11 = NapCatConfig(
                    host = "127.0.0.1",
                    port = 3001,
                    token = "raw-onebot-token",
                ),
                qqOfficial = QQOfficialConfig(
                    appId = "raw-app-id",
                    appSecret = "raw-app-secret",
                    botToken = "raw-bot-token",
                ),
            ),
            webui = WebUiConfig(
                enabled = false,
                host = "127.0.0.1",
                port = 18080,
                credentialFile = "webui-credentials.json",
                tokenTtlSeconds = 3600L,
                staticDir = "",
            ),
            targets = mutableListOf(TargetConfig("group", 1L, "onebot11:group:1")),
            admins = mutableListOf(GroupAdminConfig(1L, mutableListOf(2L), "onebot11:group:1", mutableListOf("onebot11:private:2"))),
            firstRunFlag = 1,
        )
        var savedBotConfig: BotConfig? = null
        val facade = WebUiConfigWriteFacade(
            configFacade = WebUiConfigFacade(
                biliConfigProvider = { BiliConfig() },
                biliDataProvider = { configuredBiliData(emptySet()) },
                botConfigProvider = { currentBotConfig },
            ),
            botConfigProvider = { currentBotConfig },
            saveBotConfigAction = { botConfig ->
                savedBotConfig = botConfig
                currentBotConfig = botConfig
                true
            },
        )

        val snapshotToken = WebUiConfigFacade(botConfigProvider = { currentBotConfig }).readBotConfig().snapshotToken
        val result = facade.saveBotConfig(
            WebUiBotConfigWriteRequestDto(
                snapshotToken = snapshotToken,
                platformType = "qq_official",
                adapter = "qq_official",
                oneBot11Host = "10.0.0.2",
                oneBot11Port = 3100,
                oneBot11Token = "",
                oneBot11UseTls = true,
                oneBot11HeartbeatInterval = 45000L,
                oneBot11ReconnectInterval = 6000L,
                oneBot11MessageFormat = "array",
                oneBot11SendMode = "file",
                oneBot11MaxReconnectAttempts = 3,
                oneBot11ConnectTimeout = 12000L,
                qqOfficialAppId = "new-app-id",
                qqOfficialAppSecret = "",
                qqOfficialBotToken = "",
                webUiEnabled = true,
                webUiHost = "0.0.0.0",
                webUiPort = 19080,
                webUiCredentialFile = "custom-webui.json",
                webUiTokenTtlSeconds = 7200L,
                webUiStaticDir = "static",
                targets = listOf(WebUiTargetConfigWriteDto("group", 10086L, "onebot11:group:10086")),
                admins = listOf(
                    WebUiGroupAdminConfigWriteDto(
                        groupId = 10086L,
                        userIds = listOf(7L),
                        groupContact = "onebot11:group:10086",
                        userContacts = listOf("onebot11:private:7"),
                    ),
                ),
            ),
        )

        assertTrue(result.success)
        assertEquals(PlatformType.QQ_OFFICIAL, savedBotConfig?.platform?.type)
        assertEquals("qq_official", savedBotConfig?.platform?.adapter)
        assertEquals("raw-onebot-token", savedBotConfig?.platform?.onebot11?.token)
        assertEquals("raw-app-secret", savedBotConfig?.platform?.qqOfficial?.appSecret)
        assertEquals("raw-bot-token", savedBotConfig?.platform?.qqOfficial?.botToken)
        assertEquals(true, savedBotConfig?.webui?.enabled)
        assertEquals("0.0.0.0", savedBotConfig?.webui?.host)
        assertEquals(19080, savedBotConfig?.webui?.port)
        assertEquals("webui-credentials.json", savedBotConfig?.webui?.credentialFile)
        assertEquals("", savedBotConfig?.webui?.staticDir)
        assertEquals(1, savedBotConfig?.targets?.size)
        assertEquals(1L, savedBotConfig?.targets?.firstOrNull()?.id)
        assertEquals("onebot11:group:1", savedBotConfig?.targets?.firstOrNull()?.contact)
        assertEquals(1, savedBotConfig?.admins?.size)
        assertEquals(10086L, savedBotConfig?.admins?.firstOrNull()?.groupId)
        assertEquals(1, savedBotConfig?.firstRunFlag)
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

    /**
     * 系统配置写入入口必须拒绝 WebUI 可编辑的非法数值、区间和颜色，避免无效运行参数落盘。
     */
    @Test
    fun `bili config writes should reject invalid webui settings normalization values`() {
        var saveCalls = 0
        val currentConfig = BiliConfig(adminContact = "onebot11:private:1")
        val facade = WebUiConfigWriteFacade(
            configFacade = WebUiConfigFacade(
                biliConfigProvider = { currentConfig },
                biliDataProvider = { configuredBiliData(emptySet()) },
                botConfigProvider = { BotConfig() },
            ),
            biliConfigProvider = { currentConfig },
            saveBiliConfigAction = {
                saveCalls += 1
                true
            },
        )

        val snapshotToken = WebUiConfigFacade(biliConfigProvider = { currentConfig }).readBiliConfig().snapshotToken
        val result = facade.saveBiliConfig(
            WebUiBiliConfigWriteRequestDto(
                snapshotToken = snapshotToken,
                adminContact = "onebot11:private:-42",
                lowSpeedTime = "24-8",
                lowSpeedRange = "80-30",
                normalRange = "20-60",
                checkReportInterval = -1,
                timeout = -1,
                defaultColor = "d3edfa",
                cacheExpires = mapOf("DRAW" to -1, "IMAGES" to 7, "EMOJI" to 7, "USER" to 7, "OTHER" to 7),
                messageInterval = -1L,
                pushInterval = -1L,
            ),
        )

        assertFalse(result.success)
        assertTrue(result.validationErrors.any { it.contains("lowSpeedTime") }, result.validationErrors.toString())
        assertTrue(result.validationErrors.any { it.contains("lowSpeedRange") }, result.validationErrors.toString())
        assertTrue(result.validationErrors.any { it.contains("normalRange") }, result.validationErrors.toString())
        assertTrue(result.validationErrors.any { it.contains("defaultColor") }, result.validationErrors.toString())
        assertTrue(result.validationErrors.any { it.contains("cacheExpires") }, result.validationErrors.toString())
        assertTrue(result.validationErrors.any { it.contains("adminContact") }, result.validationErrors.toString())
        assertEquals(0, saveCalls)
    }

    /**
     * bot.yml 写入入口必须覆盖端口规范和 WebUI 数字字段，防止前端绕过时保存非法启动参数。
     */
    @Test
    fun `bot config writes should reject invalid port and positive runtime settings`() {
        var saveCalls = 0
        val currentConfig = BotConfig(
            platform = PlatformConfig(
                type = PlatformType.ONEBOT11,
                adapter = "onebot11",
                onebot11 = NapCatConfig(host = "127.0.0.1", port = 3001),
            ),
        )
        val facade = WebUiConfigWriteFacade(
            configFacade = WebUiConfigFacade(
                biliConfigProvider = { BiliConfig() },
                biliDataProvider = { configuredBiliData(emptySet()) },
                botConfigProvider = { currentConfig },
            ),
            botConfigProvider = { currentConfig },
            saveBotConfigAction = {
                saveCalls += 1
                true
            },
        )

        val snapshotToken = WebUiConfigFacade(botConfigProvider = { currentConfig }).readBotConfig().snapshotToken
        val result = facade.saveBotConfig(
            WebUiBotConfigWriteRequestDto(
                snapshotToken = snapshotToken,
                platformType = "ONEBOT11",
                adapter = "onebot11",
                oneBot11Host = "127.0.0.1",
                oneBot11Port = 70000,
                oneBot11Token = "",
                oneBot11HeartbeatInterval = -1L,
                oneBot11ReconnectInterval = -1L,
                oneBot11ConnectTimeout = -1L,
                webUiPort = 70000,
                webUiTokenTtlSeconds = -1L,
            ),
        )

        assertFalse(result.success)
        assertTrue(result.validationErrors.any { it.contains("oneBot11Port") }, result.validationErrors.toString())
        assertTrue(result.validationErrors.any { it.contains("oneBot11 intervals") }, result.validationErrors.toString())
        assertTrue(result.validationErrors.any { it.contains("webUiPort") }, result.validationErrors.toString())
        assertTrue(result.validationErrors.any { it.contains("webUiTokenTtlSeconds") }, result.validationErrors.toString())
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
