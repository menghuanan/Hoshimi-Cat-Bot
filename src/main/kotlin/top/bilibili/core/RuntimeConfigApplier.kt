package top.bilibili.core

import top.bilibili.BiliConfigManager
import top.bilibili.BiliConfig
import top.bilibili.BiliDataWrapper
import top.bilibili.config.BotConfig
import top.bilibili.config.ConfigManager
import top.bilibili.connector.PlatformConnectorPrepareResult
import top.bilibili.connector.PlatformConnectorReloadResult
import top.bilibili.connector.PreparedPlatformConnector
import top.bilibili.data.BiliImageQuality
import top.bilibili.data.BiliImageTheme
import top.bilibili.draw.FontManager
import top.bilibili.service.closeServiceClient
import top.bilibili.tasker.BiliCheckTasker
import top.bilibili.utils.closeUtilsClient
import top.bilibili.webui.server.WebUiReloadPlan
import top.bilibili.webui.model.WebUiConfigFileKind

/**
 * 运行期配置 applier 只负责已验证候选配置的内存态切换和缓存刷新，不直接写配置文件。
 */
class RuntimeConfigApplier(
    private val installBiliConfigRuntimeSnapshot: (BiliConfig) -> Unit = { config ->
        BiliConfigManager.installConfigRuntimeSnapshot(config)
    },
    private val installBiliDataRuntimeSnapshot: (BiliDataWrapper) -> Unit = { data ->
        BiliConfigManager.installDataRuntimeSnapshot(data)
    },
    private val installBotRuntimeSnapshot: (BotConfig) -> Unit = { config ->
        ConfigManager.installRuntimeSnapshot(config)
        BiliBiliBot.installRuntimeConfig(config)
    },
    private val reloadImageRuntime: () -> Unit = {
        BiliImageTheme.reload()
        BiliImageQuality.reload()
        FontManager.reloadRuntimeConfig()
    },
    private val closeBiliClients: () -> Unit = {
        closeUtilsClient()
        closeServiceClient()
        BiliCheckTasker.closeSharedClient()
    },
    private val refreshTaskers: () -> Unit = {
        BiliCheckTasker.refreshAllRuntimeConfig()
    },
    private val preparePlatformConnector: (BotConfig) -> PlatformConnectorPrepareResult = { config ->
        BiliBiliBot.preparePlatformConnector(config)
    },
    private val commitPlatformConnector: (PreparedPlatformConnector?) -> PlatformConnectorReloadResult = { prepared ->
        if (prepared == null) {
            PlatformConnectorReloadResult(success = true)
        } else {
            BiliBiliBot.commitPlatformConnector(prepared)
        }
    },
    private val planWebUiReload: (BotConfig, BotConfig) -> WebUiReloadPlan = { current, next ->
        BiliBiliBot.planWebUiReload(current.webui, next.webui)
    },
    private val scheduleWebUiReload: (WebUiReloadPlan) -> Unit = { plan ->
        BiliBiliBot.scheduleWebUiReload(plan)
    },
) {
    /**
     * 候选平台资源先启动验证，成功后只安装本次变更文件对应的运行切片。
     */
    fun applyBaseConfig(generation: RuntimeConfigGeneration): WebUiReloadPlan {
        val changedFiles = generation.changedFiles
        val botConfigChanged = WebUiConfigFileKind.BOT_CONFIG in changedFiles
        val platformConnectorChanged = botConfigChanged &&
            requiresPlatformConnectorReload(
                generation.oldSnapshot.botConfig,
                generation.candidateSnapshot.botConfig,
            )
        val biliConfigChanged = WebUiConfigFileKind.BILI_CONFIG in changedFiles
        val biliDataChanged = WebUiConfigFileKind.BILI_DATA in changedFiles
        val preparedConnector = if (platformConnectorChanged) {
            preparePlatformConnector(generation.candidateSnapshot.botConfig).also { result ->
                if (!result.success) {
                    error(result.message.ifBlank { "platform connector candidate prepare failed" })
                }
            }
        } else {
            PlatformConnectorPrepareResult(success = true)
        }
        val webUiPlan = if (botConfigChanged) {
            planWebUiReload(
                generation.oldSnapshot.botConfig,
                generation.candidateSnapshot.botConfig,
            )
        } else {
            WebUiReloadPlan(restartRequired = false, message = "bot config unchanged")
        }

        try {
            if (biliConfigChanged) {
                installBiliConfigRuntimeSnapshot(generation.candidateSnapshot.biliConfig)
            }
            if (biliDataChanged) {
                installBiliDataRuntimeSnapshot(generation.candidateSnapshot.biliData)
            }
            if (botConfigChanged) {
                installBotRuntimeSnapshot(generation.candidateSnapshot.botConfig)
            }
            if (biliConfigChanged) {
                BiliBiliBot.cookie.parse(generation.candidateSnapshot.biliConfig.accountConfig.cookie)
                reloadImageRuntime()
                closeBiliClients()
                refreshTaskers()
            }
            if (webUiPlan.restartRequired) {
                // WebUI 运行面切换必须先验证成功；之后平台 commit 若失败，catch 可按旧配置恢复管理入口。
                scheduleWebUiReload(webUiPlan)
            }
            if (platformConnectorChanged) {
                val commitResult = commitPlatformConnector(preparedConnector.prepared)
                if (!commitResult.success) {
                    preparedConnector.prepared?.closeUncommitted()
                    error(commitResult.message.ifBlank { "platform connector candidate commit failed" })
                }
            }
        } catch (error: Throwable) {
            preparedConnector.prepared?.closeUncommitted()
            restoreChangedRuntimeSlices(generation, changedFiles)
            rollbackWebUiReloadIfNeeded(generation, changedFiles)
            throw error
        }
        return webUiPlan
    }

    /**
     * connector 只依赖平台类型、adapter 和对应连接参数；bot.yml 其它字段不应触发断连重连。
     */
    private fun requiresPlatformConnectorReload(current: BotConfig, next: BotConfig): Boolean {
        val currentNormalized = current.normalizedBotConfig()
        val nextNormalized = next.normalizedBotConfig()
        return currentNormalized.selectedPlatformType() != nextNormalized.selectedPlatformType() ||
            currentNormalized.selectedAdapterKind() != nextNormalized.selectedAdapterKind() ||
            currentNormalized.selectedOneBot11Config() != nextNormalized.selectedOneBot11Config() ||
            currentNormalized.platform.qqOfficial != nextNormalized.platform.qqOfficial
    }

    /**
     * 候选 apply 中途失败时只恢复本次触碰过的运行切片，并同步刷新其派生缓存。
     */
    private fun restoreChangedRuntimeSlices(
        generation: RuntimeConfigGeneration,
        changedFiles: Set<WebUiConfigFileKind>,
    ) {
        if (WebUiConfigFileKind.BILI_CONFIG in changedFiles) {
            installBiliConfigRuntimeSnapshot(generation.oldSnapshot.biliConfig)
        }
        if (WebUiConfigFileKind.BILI_DATA in changedFiles) {
            installBiliDataRuntimeSnapshot(generation.oldSnapshot.biliData)
        }
        if (WebUiConfigFileKind.BOT_CONFIG in changedFiles) {
            installBotRuntimeSnapshot(generation.oldSnapshot.botConfig)
        }
        if (WebUiConfigFileKind.BILI_CONFIG in changedFiles) {
            BiliBiliBot.cookie.parse(generation.oldSnapshot.biliConfig.accountConfig.cookie)
            reloadImageRuntime()
            closeBiliClients()
            refreshTaskers()
        }
    }

    /**
     * WebUI 运行面若已切到候选后又遇到后续失败，需要按旧 bot.yml 快照恢复管理入口。
     */
    private fun rollbackWebUiReloadIfNeeded(
        generation: RuntimeConfigGeneration,
        changedFiles: Set<WebUiConfigFileKind>,
    ) {
        if (WebUiConfigFileKind.BOT_CONFIG !in changedFiles) {
            return
        }
        val rollbackPlan = planWebUiReload(
            generation.candidateSnapshot.botConfig,
            generation.oldSnapshot.botConfig,
        )
        if (rollbackPlan.restartRequired) {
            scheduleWebUiReload(rollbackPlan)
        }
    }
}
