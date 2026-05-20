package top.bilibili.webui.service

import top.bilibili.webui.model.WebUiActionResultDto
import top.bilibili.webui.model.WebUiActionOutcome
import top.bilibili.webui.model.WebUiConfigSaveResultDto
import top.bilibili.webui.model.WebUiRecommendedAction
import top.bilibili.webui.model.WebUiSaveEffectLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebUiAuditServiceTest {
    @Test
    fun `auth events should emit sanitized audit records`() {
        val records = mutableListOf<WebUiAuditRecord>()
        val auditService = WebUiAuditService(
            sink = { record -> records += record },
        )

        auditService.recordAuthEvent(
            target = "login",
            success = false,
            outcome = "LOGIN_FAILED",
            detailSummary = "password=Better123!@ message=invalid credentials",
        )

        assertEquals(1, records.size)
        assertEquals("auth", records.first().eventType)
        assertEquals("login", records.first().target)
        assertFalse(records.first().detailSummary.contains("Better123!@"))
        assertTrue(records.first().detailSummary.contains("password=<redacted>"))
    }

    @Test
    fun `denied access should emit failure audit records`() {
        val records = mutableListOf<WebUiAuditRecord>()
        val auditService = WebUiAuditService(
            sink = { record -> records += record },
        )

        auditService.recordDeniedAccess(
            target = "high-risk-confirmation",
            outcome = "FORBIDDEN",
            detailSummary = "path=/api/config/bili-config reason=invalid confirmation password",
        )

        assertEquals(1, records.size)
        assertEquals("access-denied", records.first().eventType)
        assertEquals("high-risk-confirmation", records.first().target)
        assertFalse(records.first().success)
        assertEquals("FORBIDDEN", records.first().outcome)
    }

    @Test
    fun `config save attempts should emit audit records without raw secrets`() {
        val records = mutableListOf<WebUiAuditRecord>()
        val auditService = WebUiAuditService(
            sink = { record -> records += record },
        )

        auditService.recordConfigSave(
            sourceFile = "BiliConfig.yml",
            result = WebUiConfigSaveResultDto(
                success = false,
                persisted = false,
                conflictDetected = false,
                validationErrors = listOf("cookie is invalid"),
                effectiveLevel = WebUiSaveEffectLevel.REJECTED_VALIDATION,
                recommendedAction = WebUiRecommendedAction.FIX_VALIDATION_ERRORS,
                snapshotToken = "snapshot-a",
            ),
            detailSummary = "cookie=raw-cookie token=raw-token password=Better123!@",
        )

        assertEquals(1, records.size)
        assertEquals("config-save", records.first().eventType)
        assertEquals(WebUiSaveEffectLevel.REJECTED_VALIDATION.name, records.first().outcome)
        assertFalse(records.first().detailSummary.contains("raw-cookie"))
        assertFalse(records.first().detailSummary.contains("raw-token"))
        assertFalse(records.first().detailSummary.contains("Better123!@"))
        assertTrue(records.first().detailSummary.contains("cookie=<redacted>"))
    }

    /**
     * 配置保存的冲突拒绝也必须留下可区分的审计结果，方便本地排查快照失配。
     */
    @Test
    fun `conflict rejected config save should emit a conflict audit outcome`() {
        val records = mutableListOf<WebUiAuditRecord>()
        val auditService = WebUiAuditService(
            sink = { record -> records += record },
        )

        auditService.recordConfigSave(
            sourceFile = "bot.yml",
            result = WebUiConfigSaveResultDto(
                success = false,
                persisted = false,
                conflictDetected = true,
                validationErrors = emptyList(),
                effectiveLevel = WebUiSaveEffectLevel.REJECTED_CONFLICT,
                recommendedAction = WebUiRecommendedAction.REFRESH_AND_RETRY,
                snapshotToken = "snapshot-b",
            ),
            detailSummary = "webui save request",
        )

        assertEquals(1, records.size)
        assertEquals(WebUiSaveEffectLevel.REJECTED_CONFLICT.name, records.first().outcome)
        assertTrue(records.first().detailSummary.contains("outcome=REJECTED_CONFLICT"))
    }

    @Test
    fun `risky action requests should emit audit records with action result context`() {
        val records = mutableListOf<WebUiAuditRecord>()
        val auditService = WebUiAuditService(
            sink = { record -> records += record },
        )

        auditService.recordRiskyAction(
            action = "request-restart",
            result = WebUiActionResultDto(
                success = true,
                action = "request-restart",
                outcome = WebUiActionOutcome.RESTART_REQUESTED_MANUAL_FALLBACK,
                message = "manual restart required",
                operatorHint = "Restart manually",
                gracefulStopScheduled = true,
                restartExpected = false,
                inProcessRestartPerformed = false,
                autoRestartSupported = false,
            ),
            detailSummary = "operator requested request-restart",
        )

        assertEquals(1, records.size)
        assertEquals("risky-action", records.first().eventType)
        assertEquals("request-restart", records.first().target)
        assertEquals(WebUiActionOutcome.RESTART_REQUESTED_MANUAL_FALLBACK.name, records.first().outcome)
        assertTrue(records.first().detailSummary.contains("manual restart required"))
    }
}
