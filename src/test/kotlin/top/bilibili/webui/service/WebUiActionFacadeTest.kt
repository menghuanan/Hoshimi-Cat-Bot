package top.bilibili.webui.service

import top.bilibili.webui.model.WebUiActionRequestDto
import top.bilibili.webui.model.WebUiActionOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebUiActionFacadeTest {
    @Test
    fun `reload action should stay distinct from shutdown and restart request`() {
        var reloadCalls = 0
        var shutdownCalls = 0
        val facade = WebUiActionFacade(
            reloadAction = { reloadCalls += 1 },
            shutdownAction = { shutdownCalls += 1 },
            restartSupportedProvider = { true },
        )

        val result = facade.reloadConfig(WebUiActionRequestDto("reload-config"))

        assertTrue(result.success)
        assertEquals("reload-config", result.action)
        assertEquals(WebUiActionOutcome.RELOAD_CONFIG_REQUESTED, result.outcome)
        assertFalse(result.gracefulStopScheduled)
        assertTrue(result.operatorHint.contains("Refresh"))
        assertEquals(1, reloadCalls)
        assertEquals(0, shutdownCalls)
    }

    @Test
    fun `shutdown action should map to graceful stop semantics only`() {
        var shutdownCalls = 0
        val facade = WebUiActionFacade(
            reloadAction = {},
            shutdownAction = { shutdownCalls += 1 },
            restartSupportedProvider = { true },
        )

        val result = facade.shutdown(WebUiActionRequestDto("shutdown"))

        assertTrue(result.success)
        assertEquals("shutdown", result.action)
        assertEquals(WebUiActionOutcome.GRACEFUL_SHUTDOWN_REQUESTED, result.outcome)
        assertTrue(result.gracefulStopScheduled)
        assertFalse(result.restartExpected)
        assertTrue(result.operatorHint.contains("Wait"))
        assertEquals(1, shutdownCalls)
    }

    @Test
    fun `restart request should never become in process self restart`() {
        var shutdownCalls = 0
        val facade = WebUiActionFacade(
            reloadAction = {},
            shutdownAction = { shutdownCalls += 1 },
            restartSupportedProvider = { true },
        )

        val result = facade.requestRestart(WebUiActionRequestDto("request-restart"))

        assertTrue(result.success)
        assertEquals("request-restart", result.action)
        assertEquals(WebUiActionOutcome.RESTART_REQUESTED_WITH_SUPERVISOR, result.outcome)
        assertTrue(result.gracefulStopScheduled)
        assertTrue(result.restartExpected)
        assertFalse(result.inProcessRestartPerformed)
        assertEquals(1, shutdownCalls)
    }

    @Test
    fun `unsupported auto restart environments should degrade to explicit graceful stop messaging`() {
        var shutdownCalls = 0
        val facade = WebUiActionFacade(
            reloadAction = {},
            shutdownAction = { shutdownCalls += 1 },
            restartSupportedProvider = { false },
        )

        val result = facade.requestRestart(WebUiActionRequestDto("request-restart"))

        assertTrue(result.success)
        assertTrue(result.gracefulStopScheduled)
        assertFalse(result.autoRestartSupported)
        assertEquals(WebUiActionOutcome.RESTART_REQUESTED_MANUAL_FALLBACK, result.outcome)
        assertTrue(result.message.contains("manual restart"))
        assertEquals(1, shutdownCalls)
    }
}
