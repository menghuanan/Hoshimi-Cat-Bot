package top.bilibili.webui.service

import top.bilibili.AtAllType
import top.bilibili.Bangumi
import top.bilibili.BiliAccountConfig
import top.bilibili.BiliConfig
import top.bilibili.BiliData
import top.bilibili.CacheConfig
import top.bilibili.DynamicFilter
import top.bilibili.DynamicFilterType
import top.bilibili.EnableConfig
import top.bilibili.FilterMode
import top.bilibili.FooterConfig
import top.bilibili.Group
import top.bilibili.ImageConfig
import top.bilibili.LinkResolveConfig
import top.bilibili.PushConfig
import top.bilibili.RegularFilter
import top.bilibili.SubData
import top.bilibili.TemplateConfig
import top.bilibili.TemplatePolicy
import top.bilibili.TimeDisplayMode
import top.bilibili.TranslateConfig
import top.bilibili.TypeFilter
import top.bilibili.CheckConfig
import top.bilibili.ProxyConfig
import top.bilibili.config.BotConfig
import top.bilibili.config.GroupAdminConfig
import top.bilibili.config.NapCatConfig
import top.bilibili.config.PlatformConfig
import top.bilibili.config.QQOfficialConfig
import top.bilibili.config.TargetConfig
import top.bilibili.connector.PlatformType
import top.bilibili.service.TriggerMode
import top.bilibili.utils.CacheType
import top.bilibili.webui.model.WebUiBiliConfigWriteRequestDto
import top.bilibili.webui.model.WebUiBiliDataWriteRequestDto
import top.bilibili.webui.model.WebUiBotConfigWriteRequestDto
import top.bilibili.webui.model.WebUiGroupAdminConfigWriteDto
import top.bilibili.webui.model.WebUiTargetConfigWriteDto
import top.bilibili.webui.model.WebUiFieldCapability
import top.bilibili.webui.model.WebUiRecommendedAction
import top.bilibili.webui.model.WebUiSaveEffectLevel
import top.bilibili.webui.model.WebUiConfigSaveResultDto
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WebUiConfigFacadeTest {
    private val originalDataVersion = BiliData.dataVersion
    private val originalDynamic = BiliData.dynamic.toMutableMap()
    private val originalFilter = BiliData.filter.toMutableMap()
    private val originalDynamicTemplatePolicies = BiliData.dynamicTemplatePolicyByScope.toMutableMap()
    private val originalLiveTemplatePolicies = BiliData.liveTemplatePolicyByScope.toMutableMap()
    private val originalLiveCloseTemplatePolicies = BiliData.liveCloseTemplatePolicyByScope.toMutableMap()
    private val originalDynamicColorByUid = BiliData.dynamicColorByUid.toMutableMap()
    private val originalAtAll = BiliData.atAll.toMutableMap()
    private val originalAtAllCooldownUntil = BiliData.atAllCooldownUntil.toMutableMap()
    private val originalSubscriptionCardUpdatedAt = BiliData.subscriptionCardUpdatedAt.toMutableMap()
    private val originalGroup = BiliData.group.toMutableMap()
    private val originalBangumi = BiliData.bangumi.toMutableMap()
    private val originalLinkParseBlacklist = BiliData.linkParseBlacklist.toMutableSet()
    private val originalLinkParseBlacklistContacts = BiliData.linkParseBlacklistContacts.toMutableSet()

    @AfterTest
    fun restoreBiliDataState() {
        BiliData.dataVersion = originalDataVersion
        BiliData.dynamic = originalDynamic.toMutableMap()
        BiliData.filter = originalFilter.toMutableMap()
        BiliData.dynamicTemplatePolicyByScope = originalDynamicTemplatePolicies.toMutableMap()
        BiliData.liveTemplatePolicyByScope = originalLiveTemplatePolicies.toMutableMap()
        BiliData.liveCloseTemplatePolicyByScope = originalLiveCloseTemplatePolicies.toMutableMap()
        BiliData.dynamicColorByUid = originalDynamicColorByUid.toMutableMap()
        BiliData.atAll = originalAtAll.toMutableMap()
        BiliData.atAllCooldownUntil = originalAtAllCooldownUntil.toMutableMap()
        BiliData.subscriptionCardUpdatedAt = originalSubscriptionCardUpdatedAt.toMutableMap()
        BiliData.group = originalGroup.toMutableMap()
        BiliData.bangumi = originalBangumi.toMutableMap()
        BiliData.linkParseBlacklist = originalLinkParseBlacklist.toMutableSet()
        BiliData.linkParseBlacklistContacts = originalLinkParseBlacklistContacts.toMutableSet()
    }

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
                qqOfficial = QQOfficialConfig(
                    appSecret = "qq-secret",
                    botToken = "qq-bot-token",
                ),
            ),
            napcat = NapCatConfig(token = "legacy-token"),
            webui = top.bilibili.webui.config.WebUiConfig(
                credentialFile = "custom-webui.json",
                staticDir = "static-root",
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
        val qqSecretField = botConfigDto.fields.first { it.key == "platform.qqOfficial.appSecret" }
        val qqBotTokenField = botConfigDto.fields.first { it.key == "platform.qqOfficial.botToken" }

        assertEquals(WebUiFieldCapability.MASKED, cookieField.capability)
        assertEquals(WebUiFieldCapability.MASKED, baiduSecretField.capability)
        assertEquals(WebUiFieldCapability.MASKED, tokenField.capability)
        assertEquals(WebUiFieldCapability.MASKED, qqSecretField.capability)
        assertEquals(WebUiFieldCapability.MASKED, qqBotTokenField.capability)
        assertNotEquals("SESSDATA=raw-cookie", cookieField.value)
        assertNotEquals("raw-secret", baiduSecretField.value)
        assertNotEquals("napcat-token", tokenField.value)
        assertNotEquals("qq-secret", qqSecretField.value)
        assertNotEquals("qq-bot-token", qqBotTokenField.value)
        assertFalse(botConfigDto.fields.any { it.key == "napcat.token" })
        assertFalse(botConfigDto.fields.any { it.key == "webui.credentialFile" })
        assertFalse(botConfigDto.fields.any { it.key == "webui.staticDir" })
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
        assertTrue(baselineBiliConfig.snapshotToken.startsWith("v1-sha256:"))
        assertTrue(baselineBiliData.snapshotToken.startsWith("v1-sha256:"))
        assertTrue(baselineBotConfig.snapshotToken.startsWith("v1-sha256:"))
        assertFalse(Regex("""^[0-9a-f]{32}$""").matches(baselineBiliConfig.snapshotToken))
        assertFalse(baselineBiliConfig.snapshotToken.contains("cookie-a"))
        assertFalse(baselineBotConfig.snapshotToken.contains("token-a"))
        assertNotEquals(baselineBiliConfig.snapshotToken, changedBiliConfig.snapshotToken)
        assertNotEquals(baselineBiliData.snapshotToken, changedBiliData.snapshotToken)
        assertNotEquals(baselineBotConfig.snapshotToken, changedBotConfig.snapshotToken)
    }

    @Test
    fun `config write requests should stay file scoped`() {
        val biliConfigRequest = WebUiBiliConfigWriteRequestDto(
            snapshotToken = "token-a",
            admin = 42L,
            adminContact = "onebot11:private:42",
            debugMode = true,
            drawEnable = true,
            pushDrawEnable = true,
            notifyEnable = true,
            liveCloseNotifyEnable = true,
            lowSpeedEnable = true,
            translateEnable = true,
            proxyEnable = true,
            cacheClearEnable = false,
            cookie = "",
            autoFollow = false,
            followGroup = "Bot关注",
            proxies = listOf("http://127.0.0.1:8080"),
            lowSpeedTime = "22-8",
            lowSpeedRange = "60-240",
            normalRange = "30-120",
            checkReportInterval = 10,
            timeout = 10,
            quality = "1000w",
            theme = "v3",
            font = "Noto Sans CJK SC",
            defaultColor = "#d3edfa",
            cardOrnament = "FanCard",
            timeDisplayMode = "ABSOLUTE",
            hueStep = 30,
            lockSB = true,
            saturation = 0.25f,
            brightness = 1.0f,
            leftBadgeEnable = true,
            rightBadgeEnable = false,
            dynamicFooter = "",
            liveFooter = "",
            footerAlign = "LEFT",
            downloadOriginal = true,
            cacheExpires = mapOf("DRAW" to 7, "IMAGES" to 7, "EMOJI" to 7, "USER" to 7, "OTHER" to 7),
            messageInterval = 100L,
            pushInterval = 500L,
            toShortLink = false,
            defaultDynamicPush = "OneMsg",
            defaultLivePush = "OneMsg",
            defaultLiveClose = "SimpleMsg",
            dynamicPush = mapOf("DrawOnly" to "{draw}"),
            livePush = mapOf("DrawOnly" to "{draw}"),
            liveClose = mapOf("SimpleMsg" to "{name} 直播结束啦!"),
            triggerMode = "At",
            linkResolveDrawEnable = true,
            linkResolveReturnLink = false,
            cutLine = "\n\n翻译\n",
            baiduAppId = "app-id",
            baiduSecurityKey = "",
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
            oneBot11UseTls = false,
            oneBot11HeartbeatInterval = 30000L,
            oneBot11ReconnectInterval = 5000L,
            oneBot11MessageFormat = "array",
            oneBot11SendMode = "base64",
            oneBot11MaxReconnectAttempts = -1,
            oneBot11ConnectTimeout = 10000L,
            qqOfficialAppId = "",
            qqOfficialAppSecret = "",
            qqOfficialBotToken = "",
            webUiEnabled = true,
            webUiHost = "127.0.0.1",
            webUiPort = 18080,
            webUiCredentialFile = "webui-credentials.json",
            webUiTokenTtlSeconds = 3600L,
            webUiStaticDir = "",
            targets = listOf(WebUiTargetConfigWriteDto("group", 10086L, "onebot11:group:10086")),
            admins = listOf(
                WebUiGroupAdminConfigWriteDto(
                    groupId = 10086L,
                    userIds = listOf(7L),
                    groupContact = "onebot11:group:10086",
                    userContacts = listOf("onebot11:private:7"),
                ),
            ),
        )

        assertEquals("token-a", biliConfigRequest.snapshotToken)
        assertEquals("token-b", biliDataRequest.snapshotToken)
        assertEquals("token-c", botConfigRequest.snapshotToken)
        assertEquals("onebot11:private:42", biliConfigRequest.adminContact)
        assertEquals(listOf("onebot11:group:2"), biliDataRequest.linkParseBlacklistContacts)
        assertEquals("127.0.0.1", botConfigRequest.oneBot11Host)
    }

    @Test
    fun `system settings editable fields should expose expected capabilities`() {
        val facade = WebUiConfigFacade(
            biliConfigProvider = { BiliConfig() },
            biliDataProvider = { BiliData },
            botConfigProvider = { BotConfig() },
        )

        val biliFields = facade.readBiliConfig().fields.associateBy { it.key }
        val botFields = facade.readBotConfig().fields.associateBy { it.key }

        assertEquals(WebUiFieldCapability.EDITABLE, biliFields["enableConfig.drawEnable"]?.capability)
        assertEquals(WebUiFieldCapability.EDITABLE, biliFields["imageConfig.quality"]?.capability)
        assertEquals(WebUiFieldCapability.EDITABLE, biliFields["templateConfig.dynamicPush"]?.capability)
        assertEquals(WebUiFieldCapability.MASKED, biliFields["accountConfig.cookie"]?.capability)
        assertEquals(WebUiFieldCapability.EDITABLE, botFields["platform.onebot11.useTls"]?.capability)
        assertEquals(WebUiFieldCapability.MASKED, botFields["platform.qqOfficial.appSecret"]?.capability)
        assertEquals(WebUiFieldCapability.SYSTEM_MANAGED, botFields["firstRunFlag"]?.capability)
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

    /**
     * WebUI 读侧只返回可展示的配置字段，BiliData 运行态字段和本地路径必须留在响应之外。
     */
    @Test
    fun `config responses should expose controlled field trees for all three files`() {
        val biliConfig = BiliConfig(
            admin = 42L,
            adminContact = "onebot11:private:42",
            enableConfig = EnableConfig(
                debugMode = true,
                drawEnable = false,
                pushDrawEnable = false,
                notifyEnable = false,
                liveCloseNotifyEnable = false,
                lowSpeedEnable = false,
                translateEnable = true,
                proxyEnable = true,
                cacheClearEnable = false,
            ),
            accountConfig = BiliAccountConfig(
                cookie = "SESSDATA=raw-cookie",
                autoFollow = false,
                followGroup = "Focus",
            ),
            checkConfig = CheckConfig(
                lowSpeedTime = "21-8",
                lowSpeedRange = "10-20",
                normalRange = "30-40",
                checkReportInterval = 15,
                timeout = 20,
            ),
            pushConfig = PushConfig(
                messageInterval = 250,
                pushInterval = 600,
                toShortLink = true,
            ),
            imageConfig = ImageConfig(
                quality = "720w",
                theme = "v4",
                font = "Noto Sans",
                defaultColor = "#112233",
                cardOrnament = "Wave",
                timeDisplayMode = TimeDisplayMode.RELATIVE,
                colorGenerator = ImageConfig.ColorGenerator(
                    hueStep = 20,
                    lockSB = false,
                    saturation = 0.3f,
                    brightness = 0.9f,
                ),
                badgeEnable = ImageConfig.BadgeEnable(
                    left = false,
                    right = true,
                ),
            ),
            templateConfig = TemplateConfig(
                defaultDynamicPush = "TextOnly",
                defaultLivePush = "OneMsg",
                defaultLiveClose = "ComplexMsg",
                footer = FooterConfig(
                    dynamicFooter = "dyn footer",
                    liveFooter = "live footer",
                    footerAlign = "CENTER",
                ),
            ),
            cacheConfig = CacheConfig(
                downloadOriginal = false,
                expires = mapOf(
                    CacheType.DRAW to 1,
                    CacheType.IMAGES to 2,
                ),
            ),
            proxyConfig = ProxyConfig(
                proxy = listOf("http://proxy.local:8080"),
            ),
            translateConfig = TranslateConfig(
                cutLine = "---",
                baidu = TranslateConfig.BaiduTranslateConfig(
                    APP_ID = "app-id",
                    SECURITY_KEY = "secret-key",
                ),
            ),
            linkResolveConfig = LinkResolveConfig(
                triggerMode = TriggerMode.Always,
                drawEnable = false,
                returnLink = true,
            ),
        )
        BiliData.apply {
            dataVersion = 4
            dynamic = mutableMapOf(
                123L to SubData(
                    name = "Alice",
                    last = 111L,
                    lastLive = 222L,
                    contacts = mutableSetOf("onebot11:private:1"),
                    sourceRefs = mutableSetOf("direct:onebot11:private:1"),
                    banList = mutableMapOf("keyword" to "blocked"),
                ),
            )
            filter = mutableMapOf(
                "onebot11:private:1" to mutableMapOf(
                    123L to DynamicFilter(
                        typeSelect = TypeFilter(
                            mode = FilterMode.WHITE_LIST,
                            list = mutableListOf(DynamicFilterType.DYNAMIC),
                        ),
                        regularSelect = RegularFilter(
                            mode = FilterMode.BLACK_LIST,
                            list = mutableListOf(".*spam.*"),
                        ),
                    ),
                ),
            )
            dynamicTemplatePolicyByScope = mutableMapOf(
                "scope-a" to mutableMapOf(
                    123L to TemplatePolicy(
                        templates = mutableListOf("OneMsg"),
                        randomEnabled = true,
                    ),
                ),
            )
            liveTemplatePolicyByScope = mutableMapOf(
                "scope-a" to mutableMapOf(
                    123L to TemplatePolicy(
                        templates = mutableListOf("TwoMsg"),
                        randomEnabled = false,
                    ),
                ),
            )
            liveCloseTemplatePolicyByScope = mutableMapOf(
                "scope-a" to mutableMapOf(
                    123L to TemplatePolicy(
                        templates = mutableListOf("SimpleMsg"),
                        randomEnabled = false,
                    ),
                ),
            )
            dynamicColorByUid = mutableMapOf(
                "scope-a" to mutableMapOf(123L to "#112233"),
            )
            atAll = mutableMapOf(
                "scope-a" to mutableMapOf(123L to mutableSetOf(AtAllType.ALL)),
            )
            atAllCooldownUntil = mutableMapOf("scope-a.123.ALL" to 999L)
            group = mutableMapOf(
                "group-a" to Group(
                    name = "group-a",
                    creator = 42L,
                    admin = mutableSetOf(7L),
                    creatorContact = "onebot11:private:42",
                    adminContacts = mutableSetOf("onebot11:private:7"),
                    contacts = mutableSetOf("onebot11:private:1"),
                ),
            )
            bangumi = mutableMapOf(
                100L to Bangumi(
                    title = "Bangumi",
                    seasonId = 100L,
                    mediaId = 200L,
                    type = "bangumi",
                    isEnd = false,
                    color = "#334455",
                    contacts = mutableSetOf("onebot11:group:1"),
                ),
            )
            linkParseBlacklist = mutableSetOf(99L)
            linkParseBlacklistContacts = mutableSetOf("onebot11:private:99")
        }
        val botConfig = BotConfig(
            platform = PlatformConfig(
                type = PlatformType.QQ_OFFICIAL,
                adapter = "qq_official",
                onebot11 = NapCatConfig(
                    host = "127.0.0.1",
                    port = 3001,
                    token = "raw-token",
                ),
                qqOfficial = QQOfficialConfig(
                    appId = "app-id",
                    appSecret = "secret",
                    botToken = "bot-token",
                ),
            ),
            napcat = NapCatConfig(
                host = "192.168.0.9",
                port = 3002,
                token = "legacy-token",
            ),
            webui = top.bilibili.webui.config.WebUiConfig(
                enabled = true,
                host = "0.0.0.0",
                port = 19080,
                credentialFile = "custom-webui.json",
                tokenTtlSeconds = 7200L,
                staticDir = "static",
            ),
            targets = mutableListOf(
                TargetConfig(
                    type = "group",
                    id = 10086,
                    contact = "onebot11:group:10086",
                ),
            ),
            admins = mutableListOf(
                GroupAdminConfig(
                    groupId = 10086,
                    userIds = mutableListOf(7L),
                    groupContact = "onebot11:group:10086",
                    userContacts = mutableListOf("onebot11:private:7"),
                ),
            ),
            firstRunFlag = 1,
        )
        val facade = WebUiConfigFacade(
            biliConfigProvider = { biliConfig },
            biliDataProvider = { BiliData },
            botConfigProvider = { botConfig },
        )

        val biliConfigFields = facade.readBiliConfig().fields.associateBy { it.key }
        val biliDataFields = facade.readBiliData().fields.associateBy { it.key }
        val botConfigFields = facade.readBotConfig().fields.associateBy { it.key }

        assertTrue(biliConfigFields.containsKey("checkConfig.lowSpeedTime"))
        assertTrue(biliConfigFields.containsKey("pushConfig.messageInterval"))
        assertTrue(biliConfigFields.containsKey("imageConfig.badgeEnable.left"))
        assertTrue(biliConfigFields.containsKey("templateConfig.footer.footerAlign"))
        assertTrue(biliConfigFields.containsKey("cacheConfig.expires"))
        assertTrue(biliConfigFields.containsKey("proxyConfig.proxy"))
        assertEquals(WebUiFieldCapability.MASKED, biliConfigFields["proxyConfig.proxy"]?.capability)
        assertNotEquals("http://proxy.local:8080", biliConfigFields["proxyConfig.proxy"]?.value)
        assertTrue(biliConfigFields.containsKey("linkResolveConfig.triggerMode"))
        assertTrue(biliDataFields.containsKey("dataVersion"))
        assertTrue(biliDataFields.containsKey("dynamic.count"))
        assertTrue(biliDataFields.containsKey("group.count"))
        assertTrue(biliDataFields.containsKey("bangumi.count"))
        assertTrue(biliDataFields.containsKey("linkParseBlacklistContacts"))
        assertFalse(biliDataFields.containsKey("dynamic.123.last"))
        assertFalse(biliDataFields.containsKey("dynamic.123.lastLive"))
        assertFalse(biliDataFields.containsKey("filter.onebot11:private:1.123.typeSelect.mode"))
        assertFalse(biliDataFields.containsKey("dynamicTemplatePolicyByScope.scope-a.123.templates"))
        assertFalse(biliDataFields.containsKey("atAllCooldownUntil.scope-a.123.ALL"))
        assertTrue(botConfigFields.containsKey("platform.qqOfficial.appId"))
        assertTrue(botConfigFields.containsKey("webui.tokenTtlSeconds"))
        assertFalse(botConfigFields.containsKey("napcat.host"))
        assertFalse(botConfigFields.containsKey("napcat.token"))
        assertFalse(botConfigFields.containsKey("webui.credentialFile"))
        assertFalse(botConfigFields.containsKey("webui.staticDir"))
        assertTrue(botConfigFields.containsKey("targets.0.type"))
        assertTrue(botConfigFields.containsKey("admins.0.userContacts.0"))
        assertTrue(botConfigFields.containsKey("firstRunFlag"))
    }

    /**
     * 订阅管理卡片需要消费 BiliData 的聚合视图，避免前端从字段树里反向拼装业务对象。
     */
    @Test
    fun `subscription overview should expose card ready dynamic and bangumi rows`() {
        BiliData.apply {
            dataVersion = 4
            dynamic = mutableMapOf(
                123L to SubData(
                    name = "Alice",
                    last = 111L,
                    lastLive = 222L,
                    contacts = mutableSetOf("onebot11:group:1", "onebot11:group:2"),
                    sourceRefs = mutableSetOf("direct:onebot11:group:1", "groupRef:team-a"),
                ),
                789L to SubData(
                    name = "Bob",
                    last = 333L,
                    lastLive = 0L,
                    contacts = mutableSetOf("onebot11:group:2"),
                    sourceRefs = mutableSetOf("groupRef:team-a"),
                ),
            )
            filter = mutableMapOf(
                "onebot11:group:1" to mutableMapOf(
                    123L to DynamicFilter(
                        typeSelect = TypeFilter(
                            mode = FilterMode.BLACK_LIST,
                            list = mutableListOf(DynamicFilterType.FORWARD),
                        ),
                        regularSelect = RegularFilter(
                            mode = FilterMode.WHITE_LIST,
                            list = mutableListOf("hello"),
                        ),
                    ),
                ),
                "onebot11:group:2" to mutableMapOf(
                    123L to DynamicFilter(),
                ),
            )
            dynamicTemplatePolicyByScope = mutableMapOf(
                "groupRef:team-a" to mutableMapOf(
                    123L to TemplatePolicy(templates = mutableListOf("DyOneMsg", "DyTwoMsg")),
                ),
            )
            liveTemplatePolicyByScope = mutableMapOf(
                "groupRef:team-a" to mutableMapOf(
                    123L to TemplatePolicy(templates = mutableListOf("LiveOneMsg")),
                ),
            )
            liveCloseTemplatePolicyByScope = mutableMapOf()
            dynamicColorByUid = mutableMapOf(
                "onebot11:group:1" to mutableMapOf(123L to "#112233"),
                "onebot11:group:2" to mutableMapOf(123L to "#223344"),
            )
            atAll = mutableMapOf(
                "onebot11:group:1" to mutableMapOf(123L to mutableSetOf(AtAllType.LIVE)),
            )
            subscriptionCardUpdatedAt = mutableMapOf(
                "dynamic:123" to 333L,
                "group:team-a" to 444L,
                "bangumi:456" to 555L,
            )
            group = mutableMapOf(
                "team-a" to Group(
                    name = "team-a",
                    creator = 1L,
                    contacts = mutableSetOf("onebot11:group:1072150397", "onebot11:group:12344555"),
                ),
            )
            bangumi = mutableMapOf(
                456L to Bangumi(
                    title = "Bangumi A",
                    seasonId = 456L,
                    mediaId = 789L,
                    type = "bangumi",
                    color = "#334455",
                    contacts = mutableSetOf("onebot11:group:3"),
                ),
            )
        }
        val facade = WebUiConfigFacade(
            biliConfigProvider = { BiliConfig() },
            biliDataProvider = { BiliData },
            botConfigProvider = { BotConfig() },
        )

        val overview = facade.readSubscriptions()
        val dynamicCard = overview.items.first { it.id == "dynamic:123" }
        val bangumiCard = overview.items.first { it.id == "bangumi:456" }
        val groupCard = overview.items.first { it.id == "group:team-a" }

        assertEquals(4, overview.items.size)
        assertEquals(2, overview.dynamicCount)
        assertEquals(1, overview.bangumiCount)
        assertEquals(1, overview.groupCount)
        assertEquals(listOf("订阅"), dynamicCard.tags)
        assertEquals(333L, dynamicCard.lastUpdatedEpochMillis)
        assertEquals(listOf("onebot11:group:1", "onebot11:group:2"), dynamicCard.targets)
        assertTrue(dynamicCard.filterInfo.contains("group:1"))
        assertTrue(dynamicCard.templateNames.contains("DyOneMsg"))
        assertTrue(dynamicCard.templateNames.contains("LiveOneMsg"))
        assertEquals(2, dynamicCard.filterCount)
        assertEquals(3, dynamicCard.templateCount)
        assertEquals(2, dynamicCard.themeColorCount)
        assertEquals("直播", dynamicCard.atAllInfo)
        assertEquals("#112233", dynamicCard.themeColor)
        assertEquals(listOf("番剧"), bangumiCard.tags)
        assertEquals("#334455", bangumiCard.themeColor)
        assertEquals(555L, bangumiCard.lastUpdatedEpochMillis)
        assertEquals("推送目标", groupCard.targetSectionTitle)
        assertEquals(listOf("onebot11:group:1072150397", "onebot11:group:12344555"), groupCard.targets)
        assertEquals("订阅UID: 123、789", groupCard.identifierLabel)
        assertEquals(444L, groupCard.lastUpdatedEpochMillis)
    }

    /**
     * 旧数据没有卡片级更新时间时，使用 BiliData 文件修改时间兜底，避免继续展示推送时间或暂无更新。
     */
    @Test
    fun `subscription overview should fall back to data file update time when card timestamp is missing`() {
        BiliData.apply {
            dynamic = mutableMapOf(
                123L to SubData(
                    name = "Alice",
                    last = 222L,
                    lastLive = 111L,
                    contacts = mutableSetOf("onebot11:group:1"),
                ),
            )
            filter = mutableMapOf()
            dynamicTemplatePolicyByScope = mutableMapOf()
            liveTemplatePolicyByScope = mutableMapOf()
            liveCloseTemplatePolicyByScope = mutableMapOf()
            dynamicColorByUid = mutableMapOf()
            atAll = mutableMapOf()
            subscriptionCardUpdatedAt = mutableMapOf()
            group = mutableMapOf()
            bangumi = mutableMapOf()
        }
        val facade = WebUiConfigFacade(
            biliConfigProvider = { BiliConfig() },
            biliDataProvider = { BiliData },
            botConfigProvider = { BotConfig() },
            biliDataModifiedAtProvider = { 999L },
        )

        val dynamicCard = facade.readSubscriptions().items.first { it.id == "dynamic:123" }

        assertEquals(999L, dynamicCard.lastUpdatedEpochMillis)
    }

    /**
     * 分组卡片头部只展示前两个 UID，剩余数量用 +N 标记，避免卡片头部被长 UID 列表撑开。
     */
    @Test
    fun `subscription overview should summarize extra group uids in card header`() {
        BiliData.apply {
            dynamic = mutableMapOf(
                123L to SubData(name = "Alice", sourceRefs = mutableSetOf("groupRef:team-a")),
                456L to SubData(name = "Carol", sourceRefs = mutableSetOf("groupRef:team-a")),
                789L to SubData(name = "Bob", sourceRefs = mutableSetOf("groupRef:team-a")),
            )
            group = mutableMapOf(
                "team-a" to Group(
                    name = "team-a",
                    creator = 1L,
                    contacts = mutableSetOf("onebot11:group:10001"),
                ),
            )
            bangumi = mutableMapOf()
        }
        val facade = WebUiConfigFacade(
            biliConfigProvider = { BiliConfig() },
            biliDataProvider = { BiliData },
            botConfigProvider = { BotConfig() },
        )

        val groupCard = facade.readSubscriptions().items.first { it.id == "group:team-a" }

        assertEquals("订阅UID: 123、456 +1", groupCard.identifierLabel)
        assertEquals(listOf("onebot11:group:10001"), groupCard.targets)
    }
}
