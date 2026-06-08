package top.bilibili.webui.service

import top.bilibili.BiliConfig
import top.bilibili.BiliDataWrapper
import top.bilibili.config.BotConfig
import top.bilibili.webui.model.WebUiBiliConfigWriteRequestDto
import top.bilibili.webui.model.WebUiBiliDataWriteRequestDto
import top.bilibili.webui.model.WebUiBotConfigWriteRequestDto
import top.bilibili.webui.model.WebUiConfigBatchSaveRequestDto
import top.bilibili.webui.model.WebUiConfigFileKind
import top.bilibili.webui.model.WebUiConfigFileSaveOutcomeDto
import top.bilibili.webui.model.WebUiConfigSaveResultDto

/**
 * 批量保存服务先构建候选快照，再由上层统一决定是否写盘和热重载，避免多文件半提交。
 */
class WebUiConfigBatchSaveService(
    private val prepareBiliConfig: (
        WebUiBiliConfigWriteRequestDto,
        BiliConfig,
    ) -> PreparedBiliConfigWrite = { _, _ -> error("BiliConfig prepare action is not configured") },
    private val prepareBiliData: (
        WebUiBiliDataWriteRequestDto,
        BiliDataWrapper,
    ) -> PreparedBiliDataWrite = { _, _ -> error("BiliData prepare action is not configured") },
    private val prepareBotConfig: (
        WebUiBotConfigWriteRequestDto,
        BotConfig,
    ) -> PreparedBotConfigWrite = { _, _ -> error("BotConfig prepare action is not configured") },
) {
    /**
     * prepare 阶段只执行冲突与字段校验，并组装候选快照；任何失败都会阻止 prepared 批次产生。
     */
    fun prepare(
        request: WebUiConfigBatchSaveRequestDto,
        current: WebUiConfigCandidateSnapshot,
    ): WebUiPreparedBatchResult {
        var candidate = current
        val outcomes = mutableListOf<WebUiConfigFileSaveOutcomeDto>()
        request.biliConfig?.let { input ->
            val prepared = prepareBiliConfig(input, candidate.biliConfig)
            outcomes += prepared.toOutcome()
            prepared.config?.let { config -> candidate = candidate.copy(biliConfig = config) }
        }
        request.biliData?.let { input ->
            val prepared = prepareBiliData(input, candidate.biliData)
            outcomes += prepared.toOutcome()
            prepared.data?.let { data -> candidate = candidate.copy(biliData = data) }
        }
        request.botConfig?.let { input ->
            val prepared = prepareBotConfig(input, candidate.botConfig)
            outcomes += prepared.toOutcome()
            prepared.config?.let { config -> candidate = candidate.copy(botConfig = config) }
        }
        val success = outcomes.all { outcome -> outcome.result.success }
        return WebUiPreparedBatchResult(
            success = success,
            outcomes = outcomes,
            prepared = if (success) WebUiPreparedConfigBatch(candidateSnapshot = candidate, outcomes = outcomes) else null,
        )
    }

    /**
     * 单文件 prepare 结果转换成浏览器可见 outcome，避免上层重复拼装文件名。
     */
    private fun PreparedConfigWrite<*>.toOutcome(): WebUiConfigFileSaveOutcomeDto {
        return WebUiConfigFileSaveOutcomeDto(file = file, result = result)
    }
}

/**
 * 候选快照承载三个配置 owner 的深度隔离对象；后续任务会在此基础上扩展运行代际。
 */
data class WebUiConfigCandidateSnapshot(
    val biliConfig: BiliConfig,
    val biliData: BiliDataWrapper,
    val botConfig: BotConfig,
)

/**
 * 批量 prepare 成功后返回候选快照和逐文件结果，持久化阶段只能消费这个对象。
 */
data class WebUiPreparedConfigBatch(
    val candidateSnapshot: WebUiConfigCandidateSnapshot,
    val outcomes: List<WebUiConfigFileSaveOutcomeDto>,
)

/**
 * 批量 prepare 的外层结果保留失败 outcomes；失败时 prepared 必须为空，防止误写盘。
 */
data class WebUiPreparedBatchResult(
    val success: Boolean,
    val outcomes: List<WebUiConfigFileSaveOutcomeDto>,
    val prepared: WebUiPreparedConfigBatch?,
)

/**
 * 单文件 dry-run 结果封装候选对象和保存结果；candidate 为空表示该文件未通过校验。
 */
open class PreparedConfigWrite<T>(
    val file: WebUiConfigFileKind,
    val candidate: T?,
    val result: WebUiConfigSaveResultDto,
)

/**
 * BiliConfig 写入预备结果只包含候选配置和保存结果，不执行磁盘写入。
 */
class PreparedBiliConfigWrite(
    val config: BiliConfig?,
    result: WebUiConfigSaveResultDto,
) : PreparedConfigWrite<BiliConfig>(
    file = WebUiConfigFileKind.BILI_CONFIG,
    candidate = config,
    result = result,
)

/**
 * BiliData 写入预备结果返回完整 wrapper 候选，便于后续统一持久化与回滚。
 */
class PreparedBiliDataWrite(
    val data: BiliDataWrapper?,
    result: WebUiConfigSaveResultDto,
) : PreparedConfigWrite<BiliDataWrapper>(
    file = WebUiConfigFileKind.BILI_DATA,
    candidate = data,
    result = result,
)

/**
 * bot.yml 写入预备结果只包含候选 BotConfig，不在 prepare 阶段触碰 ConfigManager。
 */
class PreparedBotConfigWrite(
    val config: BotConfig?,
    result: WebUiConfigSaveResultDto,
) : PreparedConfigWrite<BotConfig>(
    file = WebUiConfigFileKind.BOT_CONFIG,
    candidate = config,
    result = result,
)
