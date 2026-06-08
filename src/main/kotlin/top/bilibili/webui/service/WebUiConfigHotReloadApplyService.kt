package top.bilibili.webui.service

import top.bilibili.BiliConfig
import top.bilibili.BiliConfigManager
import top.bilibili.BiliData
import top.bilibili.BiliDataWrapper
import top.bilibili.config.BotConfig
import top.bilibili.config.ConfigManager
import top.bilibili.core.RuntimeConfigApplier
import top.bilibili.core.RuntimeConfigGeneration
import top.bilibili.core.RuntimeConfigSnapshot
import top.bilibili.core.deepCopy
import top.bilibili.webui.model.WebUiConfigBatchSaveRequestDto
import top.bilibili.webui.model.WebUiConfigFileKind
import top.bilibili.webui.model.WebUiConfigFileSaveOutcomeDto
import top.bilibili.webui.model.WebUiConfigHotReloadJobDto
import top.bilibili.webui.model.WebUiConfigHotReloadPhase
import top.bilibili.webui.model.WebUiConfigSaveResultDto
import top.bilibili.webui.model.WebUiRecommendedAction
import top.bilibili.webui.model.WebUiSaveEffectLevel
import top.bilibili.webui.server.WebUiReloadPlan

/**
 * WebUI 热重载 apply 服务串联 dry-run、owner 持久化和运行态切换，便于 coordinator 保持队列职责单一。
 */
class WebUiConfigHotReloadApplyService(
    private val batchSaveService: WebUiConfigBatchSaveService = defaultBatchSaveService(),
    private val captureRuntimeSnapshot: () -> RuntimeConfigSnapshot = {
        val (biliConfig, biliData) = BiliConfigManager.runtimeSnapshot()
        RuntimeConfigSnapshot(
            biliConfig = biliConfig,
            biliData = biliData,
            botConfig = ConfigManager.runtimeSnapshot(),
        ).deepCopy()
    },
    private val persistBiliConfig: (BiliConfig) -> Boolean = { snapshot -> BiliConfigManager.persistConfigSnapshot(snapshot) },
    private val persistBiliData: (BiliDataWrapper) -> Boolean = { snapshot ->
        BiliConfigManager.saveDataSnapshot(snapshot, installAfterSave = false)
    },
    private val persistBotConfig: (BotConfig) -> Boolean = { snapshot -> ConfigManager.persistConfigSnapshot(snapshot) },
    private val restoreRuntime: (RuntimeConfigSnapshot) -> Unit = { snapshot ->
        // 回滚内存态不能再准备平台候选资源；旧 connector/WebUI 入口仍应保持原样服务。
        BiliConfigManager.installRuntimeSnapshot(snapshot.biliConfig, snapshot.biliData)
        ConfigManager.installRuntimeSnapshot(snapshot.botConfig)
        top.bilibili.core.BiliBiliBot.installRuntimeConfig(snapshot.botConfig)
        top.bilibili.core.BiliBiliBot.cookie.parse(snapshot.biliConfig.accountConfig.cookie)
    },
    private val applyRuntime: (RuntimeConfigGeneration) -> WebUiReloadPlan = { generation ->
        RuntimeConfigApplier().applyBaseConfig(generation)
    },
) {
    /**
     * 执行一次前端保存任务；候选未通过校验或写盘失败时不进入运行态 apply。
     */
    suspend fun apply(
        jobId: String,
        request: WebUiConfigBatchSaveRequestDto,
    ): WebUiConfigHotReloadJobDto {
        val oldSnapshot = captureRuntimeSnapshot()
        val preparedResult = batchSaveService.prepare(request, oldSnapshot.toWebUiConfigCandidateSnapshot())
        if (!preparedResult.success || preparedResult.prepared == null) {
            return failedJob(
                jobId = jobId,
                files = request.fileKinds(),
                outcomes = preparedResult.outcomes,
                message = preparedResult.outcomes.firstOrNull { outcome -> !outcome.result.success }?.result?.message
                    ?: "save validation failed",
            )
        }

        val persistenceResult = persistCandidateBatch(request.fileKinds(), preparedResult.prepared.candidateSnapshot)
        if (!persistenceResult.success) {
            // 持久化失败时即使 persist-only 没污染内存，也显式恢复旧运行态来兜住 owner 实现差异。
            restoreRuntime(oldSnapshot)
            val rollbackSucceeded = rollbackPersistedFiles(persistenceResult.persistedFiles, oldSnapshot)
            return failedJob(
                jobId = jobId,
                files = request.fileKinds(),
                outcomes = preparedResult.outcomes + persistenceResult.outcomes,
                message = rollbackAwareMessage(
                    baseMessage = persistenceResult.outcomes.first { outcome -> !outcome.result.success }.result.message,
                    rollbackSucceeded = rollbackSucceeded,
                ),
            )
        }

        val candidateSnapshot = preparedResult.prepared.candidateSnapshot.toRuntimeConfigSnapshot()
        val webUiPlan = runCatching {
            applyRuntime(
                RuntimeConfigGeneration(
                    oldSnapshot = oldSnapshot,
                    candidateSnapshot = candidateSnapshot,
                    changedFiles = request.fileKinds().toSet(),
                ),
            )
        }.getOrElse { error ->
            // apply 失败后先恢复旧内存入口，再尝试回写已落盘文件，保证旧代际优先生存。
            restoreRuntime(oldSnapshot)
            val rollbackSucceeded = rollbackPersistedFiles(persistenceResult.persistedFiles, oldSnapshot)
            return failedJob(
                jobId = jobId,
                files = request.fileKinds(),
                outcomes = preparedResult.outcomes + persistenceResult.outcomes,
                message = rollbackAwareMessage(
                    baseMessage = error.message ?: "config hot reload apply failed",
                    rollbackSucceeded = rollbackSucceeded,
                ),
            )
        }
        return WebUiConfigHotReloadJobDto(
            jobId = jobId,
            phase = WebUiConfigHotReloadPhase.APPLIED,
            files = request.fileKinds(),
            outcomes = preparedResult.outcomes + persistenceResult.outcomes,
            webUiRedirectUrl = webUiPlan.webUiRedirectUrl,
            message = "configuration saved and hot reloaded",
        )
    }

    /**
     * 订阅页已完成 BiliData.yml 持久化时，只基于当前内存数据刷新运行代际，不再重复写盘。
     */
    suspend fun applyAlreadyPersistedBiliData(jobId: String): WebUiConfigHotReloadJobDto {
        val oldSnapshot = captureRuntimeSnapshot()
        val candidateSnapshot = oldSnapshot.copy(
            biliData = BiliDataWrapper.deepCopyFrom(BiliData),
        ).deepCopy()
        val webUiPlan = runCatching {
            applyRuntime(
                RuntimeConfigGeneration(
                    oldSnapshot = oldSnapshot,
                    candidateSnapshot = candidateSnapshot,
                    changedFiles = setOf(WebUiConfigFileKind.BILI_DATA),
                ),
            )
        }.getOrElse { error ->
            restoreRuntime(oldSnapshot)
            val rollbackSucceeded = persistBiliData(oldSnapshot.biliData)
            return failedJob(
                jobId = jobId,
                files = listOf(WebUiConfigFileKind.BILI_DATA),
                outcomes = emptyList(),
                message = rollbackAwareMessage(
                    baseMessage = error.message ?: "BiliData hot reload apply failed",
                    rollbackSucceeded = rollbackSucceeded,
                ),
            )
        }
        return WebUiConfigHotReloadJobDto(
            jobId = jobId,
            phase = WebUiConfigHotReloadPhase.APPLIED,
            files = listOf(WebUiConfigFileKind.BILI_DATA),
            webUiRedirectUrl = webUiPlan.webUiRedirectUrl,
            message = "BiliData saved and hot reloaded",
        )
    }

    /**
     * 候选快照持久化只通过各文件 owner 保存入口；调用方负责失败后的旧快照回写。
     */
    private fun persistCandidateBatch(
        files: List<WebUiConfigFileKind>,
        candidate: WebUiConfigCandidateSnapshot,
    ): PersistenceBatchResult {
        val outcomes = mutableListOf<WebUiConfigFileSaveOutcomeDto>()
        val persistedFiles = mutableListOf<WebUiConfigFileKind>()
        for (file in files) {
            // 持久化按 owner 顺序短路执行；后续失败不能继续写其它文件扩大半提交面。
            val saved = when (file) {
                WebUiConfigFileKind.BILI_CONFIG -> persistBiliConfig(candidate.biliConfig)
                WebUiConfigFileKind.BILI_DATA -> persistBiliData(candidate.biliData)
                WebUiConfigFileKind.BOT_CONFIG -> persistBotConfig(candidate.botConfig)
            }
            outcomes += WebUiConfigFileSaveOutcomeDto(file = file, result = persistenceResult(file, saved))
            if (!saved) {
                break
            }
            persistedFiles += file
        }
        return PersistenceBatchResult(
            success = outcomes.all { outcome -> outcome.result.success },
            persistedFiles = persistedFiles,
            outcomes = outcomes,
        )
    }

    /**
     * 只回写本批已经成功落盘的文件，避免失败文件或未触碰文件被补偿逻辑额外改写。
     */
    private fun rollbackPersistedFiles(
        files: List<WebUiConfigFileKind>,
        oldSnapshot: RuntimeConfigSnapshot,
    ): Boolean {
        return files.all { file ->
            when (file) {
                WebUiConfigFileKind.BILI_CONFIG -> persistBiliConfig(oldSnapshot.biliConfig)
                WebUiConfigFileKind.BILI_DATA -> persistBiliData(oldSnapshot.biliData)
                WebUiConfigFileKind.BOT_CONFIG -> persistBotConfig(oldSnapshot.botConfig)
            }
        }
    }

    /**
     * 失败消息必须让前端区分“旧运行态已恢复”和“磁盘回滚还需要人工检查”。
     */
    private fun rollbackAwareMessage(baseMessage: String, rollbackSucceeded: Boolean): String {
        return if (rollbackSucceeded) {
            "$baseMessage; old runtime is still working"
        } else {
            "$baseMessage; old runtime is still working, but disk rollback failed"
        }
    }

    /**
     * 失败 job 统一保留文件范围和逐文件 outcome，便于前端展示错误来源。
     */
    private fun failedJob(
        jobId: String,
        files: List<WebUiConfigFileKind>,
        outcomes: List<WebUiConfigFileSaveOutcomeDto>,
        message: String,
    ): WebUiConfigHotReloadJobDto {
        return WebUiConfigHotReloadJobDto(
            jobId = jobId,
            phase = WebUiConfigHotReloadPhase.FAILED,
            files = files,
            outcomes = outcomes,
            message = message,
        )
    }

    /**
     * 持久化结果只说明 owner 写盘是否完成，最终热生效由 APPLIED/FAILED job phase 表达。
     */
    private fun persistenceResult(file: WebUiConfigFileKind, saved: Boolean): WebUiConfigSaveResultDto {
        return WebUiConfigSaveResultDto(
            success = saved,
            persisted = saved,
            conflictDetected = false,
            validationErrors = emptyList(),
            effectiveLevel = if (saved) WebUiSaveEffectLevel.RELOAD_REQUIRED else WebUiSaveEffectLevel.REJECTED_PERSISTENCE,
            recommendedAction = if (saved) WebUiRecommendedAction.NONE else WebUiRecommendedAction.RETRY_SAVE,
            snapshotToken = "",
            message = if (saved) "${file.name} saved" else "${file.name} save failed",
        )
    }

    /**
     * 持久化批次结果保留已成功写盘的文件列表，供失败补偿按精确范围回滚。
     */
    private data class PersistenceBatchResult(
        val success: Boolean,
        val persistedFiles: List<WebUiConfigFileKind>,
        val outcomes: List<WebUiConfigFileSaveOutcomeDto>,
    )
}

/**
 * 当前运行态快照可作为 WebUI dry-run 的基线，避免 prepare 阶段读取半提交全局对象。
 */
private fun RuntimeConfigSnapshot.toWebUiConfigCandidateSnapshot(): WebUiConfigCandidateSnapshot {
    return WebUiConfigCandidateSnapshot(
        biliConfig = biliConfig,
        biliData = biliData,
        botConfig = botConfig,
    )
}

/**
 * WebUI 候选快照在进入 core apply 前转换为运行代际快照。
 */
private fun WebUiConfigCandidateSnapshot.toRuntimeConfigSnapshot(): RuntimeConfigSnapshot {
    return RuntimeConfigSnapshot(
        biliConfig = biliConfig,
        biliData = biliData,
        botConfig = botConfig,
    )
}

/**
 * 默认批量保存服务复用 WebUiConfigWriteFacade 的 dry-run 逻辑，避免热重载路径重新实现校验规则。
 */
private fun defaultBatchSaveService(): WebUiConfigBatchSaveService {
    val writeFacade = WebUiConfigWriteFacade()
    return WebUiConfigBatchSaveService(
        prepareBiliConfig = writeFacade::prepareBiliConfig,
        prepareBiliData = writeFacade::prepareBiliData,
        prepareBotConfig = writeFacade::prepareBotConfig,
    )
}
