package top.bilibili.webui.service

import top.bilibili.BiliAccountConfig
import top.bilibili.BiliConfig
import top.bilibili.BiliConfigManager
import top.bilibili.TranslateConfig
import top.bilibili.config.BotConfig
import top.bilibili.config.ConfigManager
import top.bilibili.config.NapCatConfig
import top.bilibili.config.PlatformConfig
import top.bilibili.connector.PlatformType
import top.bilibili.utils.normalizeContactSubject
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

        val current = biliConfigProvider()
        val updatedConfig = current.copy(
            adminContact = request.adminContact.trim(),
            accountConfig = current.accountConfig.copy(
                cookie = preserveSecret(current.accountConfig.cookie, request.cookie),
            ),
            translateConfig = current.translateConfig.copy(
                baidu = current.translateConfig.baidu.copy(
                    APP_ID = request.baiduAppId.trim(),
                    SECURITY_KEY = preserveSecret(current.translateConfig.baidu.SECURITY_KEY, request.baiduSecurityKey),
                ),
            ),
            enableConfig = current.enableConfig.copy(
                debugMode = request.debugMode,
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

        val nextToken = snapshotTokenForBiliData(
            dataVersion = currentDto.fields.first { it.key == "dataVersion" }.value,
            dynamicCount = currentDto.fields.first { it.key == "dynamic.count" }.value,
            groupCount = currentDto.fields.first { it.key == "group.count" }.value,
            blacklistContacts = normalizedContacts,
        )
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

        val validationErrors = validateBotRequest(request)
        if (validationErrors.isNotEmpty()) {
            return validationResult(validationErrors, currentDto.snapshotToken)
        }

        val current = botConfigProvider()
        val selectedOneBot11 = current.selectedOneBot11Config()
        val updatedConfig = current.copy(
            platform = current.platform.copy(
                type = PlatformType.valueOf(request.platformType.trim().uppercase()),
                adapter = request.adapter.trim().lowercase(),
                onebot11 = selectedOneBot11.copy(
                    host = request.oneBot11Host.trim(),
                    port = request.oneBot11Port,
                    token = preserveSecret(selectedOneBot11.token, request.oneBot11Token),
                ),
            ),
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
    private fun validateBotRequest(request: WebUiBotConfigWriteRequestDto): List<String> {
        val errors = mutableListOf<String>()
        val platformType = request.platformType.trim().uppercase()
        if (platformType !in PlatformType.entries.map { it.name }.toSet()) {
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
        return errors
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
        return computeWebUiSnapshotToken(
            sourceFile = "BiliConfig.yml",
            title = "BiliConfig",
            rawSnapshot = mapOf(
                "adminContact" to config.normalizedAdminSubject().orEmpty(),
                "accountConfig.cookie" to config.accountConfig.cookie,
                "translateConfig.baidu.APP_ID" to config.translateConfig.baidu.APP_ID,
                "translateConfig.baidu.SECURITY_KEY" to config.translateConfig.baidu.SECURITY_KEY,
                "enableConfig.debugMode" to config.enableConfig.debugMode.toString(),
            ),
        )
    }

    /**
     * `BiliData.yml` 当前 token 只在固定字段和黑名单集合变化时推进，保持与读侧 DTO 同步。
     */
    private fun snapshotTokenForBiliData(
        dataVersion: String,
        dynamicCount: String,
        groupCount: String,
        blacklistContacts: Set<String>,
    ): String {
        return computeWebUiSnapshotToken(
            sourceFile = "BiliData.yml",
            title = "BiliData",
            rawSnapshot = mapOf(
                "dataVersion" to dataVersion,
                "dynamic.count" to dynamicCount,
                "group.count" to groupCount,
                "linkParseBlacklistContacts" to blacklistContacts.sorted().joinToString("\n"),
            ),
        )
    }

    /**
     * `bot.yml` 的 token 跟随平台字段和 secret 一起变化，保证连接参数外部修改能被前端感知。
     */
    private fun snapshotTokenForBotConfig(config: BotConfig): String {
        val oneBot11 = config.selectedOneBot11Config()
        return computeWebUiSnapshotToken(
            sourceFile = "bot.yml",
            title = "BotConfig",
            rawSnapshot = mapOf(
                "platform.type" to config.selectedPlatformType().name,
                "platform.adapter" to config.selectedAdapterKind().name,
                "platform.onebot11.host" to oneBot11.host,
                "platform.onebot11.port" to oneBot11.port.toString(),
                "platform.onebot11.token" to oneBot11.token,
                "webui.enabled" to config.webui.enabled.toString(),
                "webui.credentialFile" to config.webui.credentialFile,
                "firstRunFlag" to config.firstRunFlag.toString(),
            ),
        )
    }
}
