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
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val loginFailureCountBySource: MutableMap<String, Int> = mutableMapOf(),
) {
    /**
     * 登录、改密等认证事件统一记录成功或失败结果，方便排查本地管理面认证链路。
     */
    fun recordAuthEvent(
        target: String,
        success: Boolean,
        outcome: String,
        detailSummary: String,
    ) {
        sink(
            WebUiAuditRecord(
                eventType = "auth",
                target = target,
                success = success,
                outcome = outcome,
                detailSummary = sanitizeDetailSummary(detailSummary),
            ),
        )
    }

    /**
     * 受保护路由的拒绝路径也要落审计，避免只看成功事件时丢失权限或确认失败线索。
     */
    fun recordDeniedAccess(
        target: String,
        outcome: String,
        detailSummary: String,
    ) {
        sink(
            WebUiAuditRecord(
                eventType = "access-denied",
                target = target,
                success = false,
                outcome = outcome,
                detailSummary = sanitizeDetailSummary(detailSummary),
            ),
        )
    }

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
     * 非动作 facade 的高风险写操作也按 risky-action 审计，但只记录语义目标和结果。
     */
    fun recordRiskyEvent(
        action: String,
        success: Boolean,
        outcome: String,
        detailSummary: String,
    ) {
        sink(
            WebUiAuditRecord(
                eventType = "risky-action",
                target = action,
                success = success,
                outcome = outcome,
                detailSummary = sanitizeDetailSummary(detailSummary),
            ),
        )
    }

    /**
     * 登录失败审计集中补足来源上下文和失败计数，避免路由层重复拼接审计字段。
     */
    fun recordLoginFailure(
        sourceIp: String?,
        userAgent: String?,
        message: String,
    ) {
        val sourceKey = sourceIp?.trim()?.takeIf { it.isNotBlank() } ?: "unknown"
        val failureCount = (loginFailureCountBySource[sourceKey] ?: 0) + 1
        loginFailureCountBySource[sourceKey] = failureCount
        recordAuthEvent(
            target = "login",
            success = false,
            outcome = "LOGIN_FAILED",
            detailSummary = listOf(
                "sourceIp=$sourceKey",
                "userAgent=${summarizeUserAgent(userAgent)}",
                "failureCount=$failureCount",
                "occurredAtEpochMillis=${clockMillis()}",
                "message=$message",
            ).joinToString(" "),
        )
    }

    /**
     * 常见 secret 键统一替换为 `<redacted>`，避免参数串或描述文本被原样写入日志。
     */
    private fun sanitizeDetailSummary(detailSummary: String): String {
        val sensitiveKeyPattern = """(?:authorization|cookie|set-cookie|token|password|secret|app[_-]?secret|bot[_-]?token)"""
        val keyValueRedacted = Regex("""(?i)\b($sensitiveKeyPattern)\s*=\s*([^\s;]+)""").replace(detailSummary) { matchResult ->
            "${matchResult.groupValues[1]}=<redacted>"
        }
        val authorizationRedacted = Regex("""(?i)\b(Authorization)\s*:\s*[^\s]+(?:\s+[^\s;{}]+)?""").replace(keyValueRedacted) { matchResult ->
            "${matchResult.groupValues[1]}=<redacted>"
        }
        val setCookieRedacted = Regex("""(?i)\b(Set-Cookie)\s*:\s*[^\s;{}]+""").replace(authorizationRedacted) { matchResult ->
            "${matchResult.groupValues[1]}=<redacted>"
        }
        val headerRedacted = Regex("""(?i)\b(Cookie)\s*:\s*.*?(?=\s+Set-Cookie=|\s+\{|\s*$)""").replace(setCookieRedacted) { matchResult ->
            "${matchResult.groupValues[1]}=<redacted>"
        }
        return Regex("""(?i)("($sensitiveKeyPattern)"\s*:\s*")([^"]*)(")""").replace(headerRedacted) { matchResult ->
            "${matchResult.groupValues[1]}<redacted>${matchResult.groupValues[4]}"
        }
    }

    /**
     * User-Agent 只保留首个产品摘要，避免审计日志记录过长浏览器指纹。
     */
    private fun summarizeUserAgent(userAgent: String?): String {
        return userAgent
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.split(Regex("""\s+"""))
            ?.firstOrNull()
            ?.take(80)
            ?: "unknown"
    }
}
