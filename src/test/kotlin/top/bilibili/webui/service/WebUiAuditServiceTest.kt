package top.bilibili.webui.service

import top.bilibili.webui.model.WebUiActionResultDto
import top.bilibili.webui.model.WebUiConfigSaveResultDto
import top.bilibili.webui.model.WebUiRecommendedAction
import top.bilibili.webui.model.WebUiSaveEffectLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebUiAuditServiceTest {
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
        assertFalse(records.first().detailSummary.contains("raw-cookie"))
        assertFalse(records.first().detailSummary.contains("raw-token"))
        assertFalse(records.first().detailSummary.contains("Better123!@"))
        assertTrue(records.first().detailSummary.contains("cookie=<redacted>"))
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
                message = "manual restart required",
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
        assertTrue(records.first().detailSummary.contains("manual restart required"))
    }
}
