package top.bilibili.webui.model

import kotlinx.serialization.Serializable

/**
 * 保存结果级别统一由后端给出，避免前端自行猜测配置写入后的运行期影响。
 */
@Serializable
enum class WebUiSaveEffectLevel {
    APPLIED_IMMEDIATELY,
    RELOAD_REQUIRED,
    RESTART_REQUIRED,
    REJECTED_PERSISTENCE,
    REJECTED_VALIDATION,
    REJECTED_CONFLICT,
}

/**
 * 管理页推荐动作只描述下一步运营动作，不暴露底层生命周期实现细节。
 */
@Serializable
enum class WebUiRecommendedAction {
    NONE,
    RETRY_SAVE,
    FIX_VALIDATION_ERRORS,
    REFRESH_AND_RETRY,
    RELOAD_CONFIG,
    REQUEST_RESTART,
}

/**
 * 配置保存统一返回持久化、冲突和下一步建议，便于前端逐文件展示结果。
 */
@Serializable
data class WebUiConfigSaveResultDto(
    val success: Boolean,
    val persisted: Boolean,
    val conflictDetected: Boolean,
    val validationErrors: List<String>,
    val effectiveLevel: WebUiSaveEffectLevel,
    val recommendedAction: WebUiRecommendedAction,
    val snapshotToken: String,
    val message: String = "",
)

/**
 * 配置文件枚举固定 WebUI 可保存的三个 owner 边界，避免协调器接受任意文件名。
 */
@Serializable
enum class WebUiConfigFileKind {
    BILI_CONFIG,
    BILI_DATA,
    BOT_CONFIG,
}

/**
 * 热重载任务阶段只描述后端处理进度，不向前端暴露 connector 或 tasker 内部对象。
 */
@Serializable
enum class WebUiConfigHotReloadPhase {
    QUEUED,
    SAVING,
    APPLYING,
    APPLIED,
    FAILED,
}

/**
 * 单个文件在一次批量保存中的结果，用于前端逐文件展示冲突、校验和持久化错误。
 */
@Serializable
data class WebUiConfigFileSaveOutcomeDto(
    val file: WebUiConfigFileKind,
    val result: WebUiConfigSaveResultDto,
)

/**
 * WebUI 保存任务快照由协调器维护，前端轮询该 DTO 判断配置是否已经热生效。
 */
@Serializable
data class WebUiConfigHotReloadJobDto(
    val jobId: String,
    val phase: WebUiConfigHotReloadPhase,
    val files: List<WebUiConfigFileKind> = emptyList(),
    val outcomes: List<WebUiConfigFileSaveOutcomeDto> = emptyList(),
    val coalescedSignals: Int = 1,
    val acceptedAtEpochMillis: Long = 0L,
    val startedAtEpochMillis: Long = 0L,
    val completedAtEpochMillis: Long = 0L,
    val webUiRedirectUrl: String? = null,
    val message: String = "",
)
