package top.bilibili.webui.service

import top.bilibili.BiliConfig
import top.bilibili.BiliConfigManager
import top.bilibili.BiliData
import top.bilibili.CacheConfig
import top.bilibili.CheckConfig
import top.bilibili.EnableConfig
import top.bilibili.FooterConfig
import top.bilibili.ImageConfig
import top.bilibili.LinkResolveConfig
import top.bilibili.ProxyConfig
import top.bilibili.PushConfig
import top.bilibili.TemplateConfig
import top.bilibili.TimeDisplayMode
import top.bilibili.config.BotConfig
import top.bilibili.config.ConfigManager
import top.bilibili.config.GroupAdminConfig
import top.bilibili.config.NapCatConfig
import top.bilibili.config.PlatformConfig
import top.bilibili.config.QQOfficialConfig
import top.bilibili.config.TargetConfig
import top.bilibili.connector.PlatformType
import top.bilibili.service.TriggerMode
import top.bilibili.service.normalizeGradientColorInput
import top.bilibili.utils.normalizeContactSubject
import top.bilibili.utils.CacheType
import top.bilibili.webui.config.WebUiConfig
import top.bilibili.webui.model.WebUiBiliConfigWriteRequestDto
import top.bilibili.webui.model.WebUiBiliDataWriteRequestDto
import top.bilibili.webui.model.WebUiBotConfigWriteRequestDto
import top.bilibili.webui.model.WebUiConfigSaveResultDto
import top.bilibili.webui.model.WebUiRecommendedAction
import top.bilibili.webui.model.WebUiSaveEffectLevel

/**
 * WebUI 配置写 facade 负责统一处理快照冲突、secret 保留和 manager-owned 持久化边界。
 */
class WebUiConfigWriteFacade(
    private val configFacade: WebUiConfigFacade = WebUiConfigFacade(),
    private val biliConfigProvider: () -> BiliConfig = { runCatching { BiliConfigManager.config }.getOrDefault(BiliConfig()) },
    private val botConfigProvider: () -> BotConfig = { runCatching { top.bilibili.config.ConfigManager.botConfig }.getOrDefault(BotConfig()) },
    private val saveBiliConfigAction: (BiliConfig) -> Boolean = { configToSave -> BiliConfigManager.saveConfig(configToSave) },
    private val saveBiliDataAction: (Set<String>) -> Boolean = { contacts ->
        BiliConfigManager.saveLinkParseBlacklistContacts(contacts)
    },
    private val saveBotConfigAction: (BotConfig) -> Boolean = { configToSave ->
        ConfigManager.saveConfig(configToSave)
    },
) {
    /**
     * 保存 `BiliConfig.yml` 的受控字段；只要快照未冲突，就通过主配置 owner 路径持久化。
     */
    fun saveBiliConfig(request: WebUiBiliConfigWriteRequestDto): WebUiConfigSaveResultDto {
        val currentDto = configFacade.readBiliConfig()
        if (currentDto.snapshotToken != request.snapshotToken) {
            return conflictResult(currentDto.snapshotToken, "configuration changed, refresh and retry")
        }

        val validationErrors = validateBiliConfigRequest(request)
        if (validationErrors.isNotEmpty()) {
            return validationResult(validationErrors, currentDto.snapshotToken)
        }

        val current = biliConfigProvider()
        val updatedConfig = current.copy(
            admin = request.admin,
            adminContact = request.adminContact.trim(),
            enableConfig = EnableConfig(
                debugMode = request.debugMode,
                drawEnable = request.drawEnable,
                pushDrawEnable = request.pushDrawEnable,
                notifyEnable = request.notifyEnable,
                liveCloseNotifyEnable = request.liveCloseNotifyEnable,
                lowSpeedEnable = request.lowSpeedEnable,
                translateEnable = request.translateEnable,
                proxyEnable = request.proxyEnable,
                cacheClearEnable = request.cacheClearEnable,
            ),
            accountConfig = current.accountConfig.copy(
                cookie = preserveSecret(current.accountConfig.cookie, request.cookie),
                autoFollow = request.autoFollow,
                followGroup = request.followGroup.trim(),
            ),
            checkConfig = CheckConfig(
                lowSpeedTime = request.lowSpeedTime.trim(),
                lowSpeedRange = request.lowSpeedRange.trim(),
                normalRange = request.normalRange.trim(),
                checkReportInterval = request.checkReportInterval,
                timeout = request.timeout,
            ),
            pushConfig = PushConfig(
                messageInterval = request.messageInterval,
                pushInterval = request.pushInterval,
                toShortLink = request.toShortLink,
            ),
            imageConfig = ImageConfig(
                quality = request.quality.trim(),
                theme = request.theme.trim(),
                font = request.font.trim(),
                defaultColor = request.defaultColor.trim(),
                cardOrnament = request.cardOrnament.trim(),
                timeDisplayMode = parseEnum<TimeDisplayMode>(request.timeDisplayMode) ?: TimeDisplayMode.ABSOLUTE,
                colorGenerator = ImageConfig.ColorGenerator(
                    hueStep = request.hueStep,
                    lockSB = request.lockSB,
                    saturation = request.saturation,
                    brightness = request.brightness,
                ),
                badgeEnable = ImageConfig.BadgeEnable(
                    left = request.leftBadgeEnable,
                    right = request.rightBadgeEnable,
                ),
            ),
            templateConfig = TemplateConfig(
                defaultDynamicPush = request.defaultDynamicPush.trim(),
                defaultLivePush = request.defaultLivePush.trim(),
                defaultLiveClose = request.defaultLiveClose.trim(),
                dynamicPush = request.dynamicPush.ifEmpty { current.templateConfig.dynamicPush }.toMutableMap(),
                livePush = request.livePush.ifEmpty { current.templateConfig.livePush }.toMutableMap(),
                liveClose = request.liveClose.ifEmpty { current.templateConfig.liveClose }.toMutableMap(),
                footer = FooterConfig(
                    dynamicFooter = request.dynamicFooter,
                    liveFooter = request.liveFooter,
                    footerAlign = request.footerAlign.trim(),
                ),
            ),
            cacheConfig = CacheConfig(
                downloadOriginal = request.downloadOriginal,
                expires = cacheExpiresFrom(request.cacheExpires, current.cacheConfig.expires),
            ),
            proxyConfig = ProxyConfig(
                proxy = request.proxies.map { it.trim() }.filter { it.isNotBlank() },
            ),
            translateConfig = current.translateConfig.copy(
                cutLine = request.cutLine,
                baidu = current.translateConfig.baidu.copy(
                    APP_ID = request.baiduAppId.trim(),
                    SECURITY_KEY = preserveSecret(current.translateConfig.baidu.SECURITY_KEY, request.baiduSecurityKey),
                ),
            ),
            linkResolveConfig = LinkResolveConfig(
                triggerMode = parseTriggerMode(request.triggerMode) ?: TriggerMode.At,
                drawEnable = request.linkResolveDrawEnable,
                returnLink = request.linkResolveReturnLink,
            ),
        )

        return runCatching {
            val saved = saveBiliConfigAction(updatedConfig)
            if (!saved) {
                return@runCatching persistenceResult(
                    snapshotToken = currentDto.snapshotToken,
                    message = "BiliConfig save failed",
                )
            }
            successResult(
                snapshotToken = snapshotTokenForBiliConfig(updatedConfig),
                effectiveLevel = WebUiSaveEffectLevel.RELOAD_REQUIRED,
                recommendedAction = WebUiRecommendedAction.RELOAD_CONFIG,
                message = "BiliConfig saved",
            )
        }.getOrElse { error ->
            persistenceResult(
                snapshotToken = currentDto.snapshotToken,
                message = error.message ?: "BiliConfig save failed",
            )
        }
    }

    /**
     * 保存 `BiliData.yml` 的当前可编辑字段；固定保持文件级边界，不混写其它配置文件。
     */
    fun saveBiliData(request: WebUiBiliDataWriteRequestDto): WebUiConfigSaveResultDto {
        val currentDto = configFacade.readBiliData()
        if (currentDto.snapshotToken != request.snapshotToken) {
            return conflictResult(currentDto.snapshotToken, "configuration changed, refresh and retry")
        }

        val normalizedContacts = request.linkParseBlacklistContacts
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { normalizeContactSubject(it) ?: it }
            .toSet()
        val persisted = runCatching {
            saveBiliDataAction(normalizedContacts)
        }.getOrElse { error ->
            return persistenceResult(
                snapshotToken = currentDto.snapshotToken,
                message = error.message ?: "BiliData save failed",
            )
        }
        if (!persisted) {
            return persistenceResult(
                snapshotToken = currentDto.snapshotToken,
                message = "BiliData save failed",
            )
        }

        val nextToken = snapshotTokenForBiliData()
        return successResult(
            snapshotToken = nextToken,
            effectiveLevel = WebUiSaveEffectLevel.APPLIED_IMMEDIATELY,
            recommendedAction = WebUiRecommendedAction.NONE,
            message = "BiliData saved",
        )
    }

    /**
     * 保存 `bot.yml` 的受控平台参数；配置持久化后明确提示重启级别影响。
     */
    fun saveBotConfig(request: WebUiBotConfigWriteRequestDto): WebUiConfigSaveResultDto {
        val currentDto = configFacade.readBotConfig()
        if (currentDto.snapshotToken != request.snapshotToken) {
            return conflictResult(currentDto.snapshotToken, "configuration changed, refresh and retry")
        }

        val current = botConfigProvider()
        val validationErrors = validateBotRequest(request, current)
        if (validationErrors.isNotEmpty()) {
            return validationResult(validationErrors, currentDto.snapshotToken)
        }

        val selectedOneBot11 = current.selectedOneBot11Config()
        val updatedConfig = current.copy(
            platform = PlatformConfig(
                type = parsePlatformType(request.platformType) ?: PlatformType.ONEBOT11,
                adapter = request.adapter.trim().lowercase(),
                onebot11 = NapCatConfig(
                    host = request.oneBot11Host.trim(),
                    port = request.oneBot11Port,
                    token = preserveSecret(selectedOneBot11.token, request.oneBot11Token),
                    useTls = request.oneBot11UseTls,
                    heartbeatInterval = request.oneBot11HeartbeatInterval,
                    reconnectInterval = request.oneBot11ReconnectInterval,
                    messageFormat = request.oneBot11MessageFormat.trim().ifBlank { selectedOneBot11.messageFormat },
                    sendMode = request.oneBot11SendMode.trim().lowercase(),
                    maxReconnectAttempts = request.oneBot11MaxReconnectAttempts,
                    connectTimeout = request.oneBot11ConnectTimeout,
                ),
                qqOfficial = QQOfficialConfig(
                    appId = request.qqOfficialAppId.trim(),
                    appSecret = preserveSecret(current.platform.qqOfficial.appSecret, request.qqOfficialAppSecret),
                    botToken = preserveSecret(current.platform.qqOfficial.botToken, request.qqOfficialBotToken),
                ),
            ),
            webui = WebUiConfig(
                enabled = request.webUiEnabled,
                host = request.webUiHost.trim(),
                port = request.webUiPort,
                credentialFile = current.webui.credentialFile,
                tokenTtlSeconds = request.webUiTokenTtlSeconds,
                staticDir = current.webui.staticDir,
            ).normalized(),
            targets = current.targets.toMutableList(),
            admins = request.admins.map { admin ->
                GroupAdminConfig(
                    groupId = admin.groupId,
                    userIds = admin.userIds.toMutableList(),
                    groupContact = admin.groupContact.trim(),
                    userContacts = admin.userContacts.map { it.trim() }.filter { it.isNotBlank() }.toMutableList(),
                )
            }.toMutableList(),
        )

        return runCatching {
            val saved = saveBotConfigAction(updatedConfig)
            if (!saved) {
                return@runCatching persistenceResult(
                    snapshotToken = currentDto.snapshotToken,
                    message = "bot.yml save failed",
                )
            }
            successResult(
                snapshotToken = snapshotTokenForBotConfig(updatedConfig),
                effectiveLevel = WebUiSaveEffectLevel.RESTART_REQUIRED,
                recommendedAction = WebUiRecommendedAction.REQUEST_RESTART,
                message = "bot.yml saved",
            )
        }.getOrElse { error ->
            persistenceResult(
                snapshotToken = currentDto.snapshotToken,
                message = error.message ?: "bot.yml save failed",
            )
        }
    }

    /**
     * bot.yml 当前只接受项目已支持的平台和 OneBot11 端口范围，避免 WebUI 写出无效启动参数。
     */
    private fun validateBotRequest(
        request: WebUiBotConfigWriteRequestDto,
        current: BotConfig,
    ): List<String> {
        val errors = mutableListOf<String>()
        val platformType = parsePlatformType(request.platformType)
        if (platformType == null) {
            errors += "platformType is invalid"
        }
        val normalizedAdapter = request.adapter.trim().lowercase()
        if (normalizedAdapter !in setOf("onebot11", "napcat", "llbot", "qq_official")) {
            errors += "adapter is invalid"
        }
        if (request.oneBot11Host.isBlank()) {
            errors += "oneBot11Host is invalid"
        }
        if (request.oneBot11Port !in 1..65535) {
            errors += "oneBot11Port is invalid"
        }
        if (request.oneBot11HeartbeatInterval <= 0L ||
            request.oneBot11ReconnectInterval <= 0L ||
            request.oneBot11ConnectTimeout <= 0L
        ) {
            errors += "oneBot11 intervals are invalid"
        }
        if (request.oneBot11SendMode.trim().lowercase() !in setOf("base64", "file")) {
            errors += "oneBot11SendMode is invalid"
        }
        if (request.webUiPort !in 1..65535) {
            errors += "webUiPort is invalid"
        }
        if (request.webUiTokenTtlSeconds <= 0L) {
            errors += "webUiTokenTtlSeconds is invalid"
        }
        if (platformType == PlatformType.QQ_OFFICIAL) {
            if (request.qqOfficialAppId.isBlank()) {
                errors += "qqOfficialAppId is invalid"
            }
            if (request.qqOfficialAppSecret.isBlank() && current.platform.qqOfficial.appSecret.isBlank()) {
                errors += "qqOfficialAppSecret is invalid"
            }
        }
        return errors
    }

    /**
     * BiliConfig 校验防止 WebUI 写入运行时代码无法解释的枚举、区间和缓存 key。
     */
    private fun validateBiliConfigRequest(request: WebUiBiliConfigWriteRequestDto): List<String> {
        val errors = mutableListOf<String>()
        validateOptionalOneBot11PrivateContact("adminContact", request.adminContact, errors)
        validateNonNegativeLong("admin", request.admin, errors)
        validateHourRange("lowSpeedTime", request.lowSpeedTime, errors)
        validateIntervalRange("lowSpeedRange", request.lowSpeedRange, errors)
        validateIntervalRange("normalRange", request.normalRange, errors)
        validatePositiveInt("checkReportInterval", request.checkReportInterval, errors)
        validatePositiveInt("timeout", request.timeout, errors)
        validatePositiveLong("messageInterval", request.messageInterval, errors)
        validatePositiveLong("pushInterval", request.pushInterval, errors)
        if (normalizeGradientColorInput(request.defaultColor.trim()) == null) {
            errors += "defaultColor is invalid"
        }
        if (request.timeDisplayMode.isNotBlank() && parseEnum<TimeDisplayMode>(request.timeDisplayMode) == null) {
            errors += "timeDisplayMode is invalid"
        }
        if (request.triggerMode.isNotBlank() && parseTriggerMode(request.triggerMode) == null) {
            errors += "triggerMode is invalid"
        }
        if (request.footerAlign.trim().uppercase() !in setOf("LEFT", "CENTER", "RIGHT")) {
            errors += "footerAlign is invalid"
        }
        if (request.cacheExpires.keys.any { key -> parseEnum<CacheType>(key) == null }) {
            errors += "cacheExpires contains invalid cache type"
        }
        if (request.cacheExpires.values.any { value -> value <= 0 }) {
            errors += "cacheExpires contains invalid expire days"
        }
        return errors
    }

    /**
     * 可选 OneBot11 私聊联系人只在填写时校验 QQ 数字，保留空管理员的旧配置兼容性。
     */
    private fun validateOptionalOneBot11PrivateContact(
        fieldName: String,
        contact: String,
        errors: MutableList<String>,
    ) {
        val normalized = contact.trim()
        if (normalized.isBlank()) {
            return
        }
        val prefix = "onebot11:private:"
        if (normalized.startsWith(prefix)) {
            val qq = normalized.removePrefix(prefix).toLongOrNull()
            if (qq == null || qq <= 0L) {
                errors += "$fieldName is invalid"
            }
        }
    }

    /**
     * 低频时段采用 24 小时制闭区间端点，允许跨午夜但不允许 24 点或相同端点造成歧义。
     */
    private fun validateHourRange(
        fieldName: String,
        value: String,
        errors: MutableList<String>,
    ) {
        val range = parseDashSeparatedRange(value)
        if (range == null || range.first !in 0..23 || range.second !in 0..23 || range.first == range.second) {
            errors += "$fieldName is invalid"
        }
    }

    /**
     * 轮询间隔统一按“最小-最大”解析，程序运行期要求最小值至少 30 秒。
     */
    private fun validateIntervalRange(
        fieldName: String,
        value: String,
        errors: MutableList<String>,
    ) {
        val range = parseDashSeparatedRange(value)
        if (range == null || range.first < 30 || range.second < 30 || range.first > range.second) {
            errors += "$fieldName is invalid"
        }
    }

    /**
     * 区间文本只接受非负整数字面量，避免负号或小数在保存后被运行期 split 误解释。
     */
    private fun parseDashSeparatedRange(value: String): Pair<Int, Int>? {
        val matched = Regex("""^\s*(\d+)\s*-\s*(\d+)\s*$""").matchEntire(value) ?: return null
        val start = matched.groupValues[1].toIntOrNull() ?: return null
        val end = matched.groupValues[2].toIntOrNull() ?: return null
        return start to end
    }

    /**
     * 正整数校验用于超时、状态报告和缓存等不可为零或负数的运行参数。
     */
    private fun validatePositiveInt(
        fieldName: String,
        value: Int,
        errors: MutableList<String>,
    ) {
        if (value <= 0) {
            errors += "$fieldName is invalid"
        }
    }

    /**
     * Long 型正整数校验覆盖毫秒级节流和 WebUI/OneBot11 长整型参数。
     */
    private fun validatePositiveLong(
        fieldName: String,
        value: Long,
        errors: MutableList<String>,
    ) {
        if (value <= 0L) {
            errors += "$fieldName is invalid"
        }
    }

    /**
     * 旧 admin 数字字段允许 0 表示未配置，但仍拒绝负数写入配置文件。
     */
    private fun validateNonNegativeLong(
        fieldName: String,
        value: Long,
        errors: MutableList<String>,
    ) {
        if (value < 0L) {
            errors += "$fieldName is invalid"
        }
    }

    /**
     * 缓存过期配置按 enum key 归一化；空提交保留当前映射，避免旧客户端清空整表。
     */
    private fun cacheExpiresFrom(
        submitted: Map<String, Int>,
        current: Map<CacheType, Int>,
    ): Map<CacheType, Int> {
        if (submitted.isEmpty()) {
            return current
        }
        return submitted.mapNotNull { (key, value) ->
            val cacheType = parseEnum<CacheType>(key) ?: return@mapNotNull null
            cacheType to value
        }.toMap()
    }

    /**
     * 平台类型兼容序列化小写值和 enum 名称，避免前端必须关心 Kotlin enum 细节。
     */
    private fun parsePlatformType(value: String): PlatformType? {
        return when (value.trim().lowercase()) {
            "onebot11" -> PlatformType.ONEBOT11
            "qq_official" -> PlatformType.QQ_OFFICIAL
            else -> parseEnum<PlatformType>(value)
        }
    }

    /**
     * 链接解析触发方式支持 enum 名称和常见大小写输入，保存前统一回到运行态 enum。
     */
    private fun parseTriggerMode(value: String): TriggerMode? {
        return TriggerMode.entries.firstOrNull { mode ->
            mode.name.equals(value.trim(), ignoreCase = true)
        }
    }

    /**
     * 通用 enum 解析集中处理大小写差异，让字段校验的失败原因保持可预期。
     */
    private inline fun <reified T : Enum<T>> parseEnum(value: String): T? {
        return enumValues<T>().firstOrNull { enumValue ->
            enumValue.name.equals(value.trim(), ignoreCase = true)
        }
    }

    /**
     * 敏感字段提交空字符串时保留旧值，避免前端因为占位或未改动而意外清空 secret。
     */
    private fun preserveSecret(previousValue: String, submittedValue: String): String {
        return if (submittedValue.isBlank()) previousValue else submittedValue
    }

    /**
     * 冲突拒绝统一返回刷新建议，让三个文件共享同一套乐观并发语义。
     */
    private fun conflictResult(snapshotToken: String, message: String): WebUiConfigSaveResultDto {
        return WebUiConfigSaveResultDto(
            success = false,
            persisted = false,
            conflictDetected = true,
            validationErrors = emptyList(),
            effectiveLevel = WebUiSaveEffectLevel.REJECTED_CONFLICT,
            recommendedAction = WebUiRecommendedAction.REFRESH_AND_RETRY,
            snapshotToken = snapshotToken,
            message = message,
        )
    }

    /**
     * 校验失败统一保持未持久化结果，避免前端把本地输入误认为已保存。
     */
    private fun validationResult(
        errors: List<String>,
        snapshotToken: String,
    ): WebUiConfigSaveResultDto {
        return WebUiConfigSaveResultDto(
            success = false,
            persisted = false,
            conflictDetected = false,
            validationErrors = errors,
            effectiveLevel = WebUiSaveEffectLevel.REJECTED_VALIDATION,
            recommendedAction = WebUiRecommendedAction.FIX_VALIDATION_ERRORS,
            snapshotToken = snapshotToken,
            message = errors.joinToString("; "),
        )
    }

    /**
     * 落盘失败统一保持原快照 token，并明确提示前端这是可重试的持久化失败而不是输入校验问题。
     */
    private fun persistenceResult(
        snapshotToken: String,
        message: String,
    ): WebUiConfigSaveResultDto {
        return WebUiConfigSaveResultDto(
            success = false,
            persisted = false,
            conflictDetected = false,
            validationErrors = emptyList(),
            effectiveLevel = WebUiSaveEffectLevel.REJECTED_PERSISTENCE,
            recommendedAction = WebUiRecommendedAction.RETRY_SAVE,
            snapshotToken = snapshotToken,
            message = message,
        )
    }

    /**
     * 成功保存统一返回明确效果等级和下一步建议，避免 route 层再做状态拼装。
     */
    private fun successResult(
        snapshotToken: String,
        effectiveLevel: WebUiSaveEffectLevel,
        recommendedAction: WebUiRecommendedAction,
        message: String,
    ): WebUiConfigSaveResultDto {
        return WebUiConfigSaveResultDto(
            success = true,
            persisted = true,
            conflictDetected = false,
            validationErrors = emptyList(),
            effectiveLevel = effectiveLevel,
            recommendedAction = recommendedAction,
            snapshotToken = snapshotToken,
            message = message,
        )
    }

    /**
     * `BiliConfig.yml` 的 token 继续绑定后端原始值，确保 secret 改动也会推进快照版本。
     */
    private fun snapshotTokenForBiliConfig(config: BiliConfig): String {
        val snapshot = buildBiliConfigSnapshot(config)
        return computeWebUiSnapshotToken(
            sourceFile = "BiliConfig.yml",
            title = "BiliConfig",
            rawSnapshot = snapshot.rawSnapshot,
        )
    }

    /**
     * `BiliData.yml` 的 token 绑定当前持久化快照，确保业务数据、模板策略和黑名单变化都会推进版本号。
     */
    private fun snapshotTokenForBiliData(): String {
        val snapshot = buildBiliDataSnapshot(BiliData)
        return computeWebUiSnapshotToken(
            sourceFile = "BiliData.yml",
            title = "BiliData",
            rawSnapshot = snapshot.rawSnapshot,
        )
    }

    /**
     * `bot.yml` 的 token 跟随平台字段和 secret 一起变化，保证连接参数外部修改能被前端感知。
     */
    private fun snapshotTokenForBotConfig(config: BotConfig): String {
        val snapshot = buildBotConfigSnapshot(config)
        return computeWebUiSnapshotToken(
            sourceFile = "bot.yml",
            title = "BotConfig",
            rawSnapshot = snapshot.rawSnapshot,
        )
    }
}
