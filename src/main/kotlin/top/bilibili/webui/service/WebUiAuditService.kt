package top.bilibili.webui.service

import org.slf4j.LoggerFactory
import top.bilibili.webui.model.WebUiActionResultDto
import top.bilibili.webui.model.WebUiConfigSaveResultDto

/**
 * WebUI 审计记录保持最小字段集，既能追踪高风险操作，又不泄露原始 secret。
 */
data class WebUiAuditRecord(
    val eventType: String,
    val target: String,
    val success: Boolean,
    val outcome: String,
    val detailSummary: String,
)

/**
 * WebUI 审计服务统一记录配置保存和高风险动作，并在落盘前执行最小必要脱敏。
 */
class WebUiAuditService(
    private val sink: (WebUiAuditRecord) -> Unit = { record ->
        LoggerFactory.getLogger(WebUiAuditService::class.java).info(
            "webui audit eventType={}, target={}, success={}, detail={}",
            record.eventType,
            record.target,
            record.success,
            record.detailSummary,
        )
    },
) {
    /**
     * 配置保存审计统一记录结果语义和脱敏后的上下文，避免原始 token/cookie/password 进入日志。
     */
    fun recordConfigSave(
        sourceFile: String,
        result: WebUiConfigSaveResultDto,
        detailSummary: String,
    ) {
        sink(
                WebUiAuditRecord(
                    eventType = "config-save",
                    target = sourceFile,
                    success = result.success,
                    outcome = result.effectiveLevel.name,
                    detailSummary = sanitizeDetailSummary(
                        "$detailSummary outcome=${result.effectiveLevel} recommended=${result.recommendedAction}",
                    ),
                ),
        )
    }

    /**
     * 高风险动作审计保留动作结果和运维提示，便于之后排查 reload/stop/restart 请求链路。
     */
    fun recordRiskyAction(
        action: String,
        result: WebUiActionResultDto,
        detailSummary: String,
    ) {
        sink(
                WebUiAuditRecord(
                    eventType = "risky-action",
                    target = action,
                    success = result.success,
                    outcome = result.outcome.name,
                    detailSummary = sanitizeDetailSummary("$detailSummary message=${result.message}"),
                ),
            )
    }

    /**
     * 常见 secret 键统一替换为 `<redacted>`，避免参数串或描述文本被原样写入日志。
     */
    private fun sanitizeDetailSummary(detailSummary: String): String {
        val sensitivePatterns = listOf("cookie", "token", "password", "secret")
        return sensitivePatterns.fold(detailSummary) { sanitized, keyword ->
            Regex("""(?i)\b$keyword=([^\s;]+)""").replace(sanitized) { matchResult ->
                "${matchResult.groupValues[0].substringBefore('=')}=<redacted>"
            }
        }
    }
}
