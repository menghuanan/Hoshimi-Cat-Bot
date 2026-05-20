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
    REJECTED_VALIDATION,
    REJECTED_CONFLICT,
}

/**
 * 管理页推荐动作只描述下一步运营动作，不暴露底层生命周期实现细节。
 */
@Serializable
enum class WebUiRecommendedAction {
    NONE,
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
