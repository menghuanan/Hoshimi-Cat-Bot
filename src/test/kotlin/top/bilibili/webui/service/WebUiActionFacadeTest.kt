package top.bilibili.webui.service

import top.bilibili.webui.model.WebUiActionConfirmationRequestDto
import top.bilibili.webui.model.WebUiActionRequestDto
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

        val result = facade.reloadConfig(
            WebUiActionRequestDto("reload-config"),
            WebUiActionConfirmationRequestDto("Better123!@"),
        )

        assertTrue(result.success)
        assertEquals("reload-config", result.action)
        assertFalse(result.gracefulStopScheduled)
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

        val result = facade.shutdown(
            WebUiActionRequestDto("shutdown"),
            WebUiActionConfirmationRequestDto("Better123!@"),
        )

        assertTrue(result.success)
        assertEquals("shutdown", result.action)
        assertTrue(result.gracefulStopScheduled)
        assertFalse(result.restartExpected)
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

        val result = facade.requestRestart(
            WebUiActionRequestDto("request-restart"),
            WebUiActionConfirmationRequestDto("Better123!@"),
        )

        assertTrue(result.success)
        assertEquals("request-restart", result.action)
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

        val result = facade.requestRestart(
            WebUiActionRequestDto("request-restart"),
            WebUiActionConfirmationRequestDto("Better123!@"),
        )

        assertTrue(result.success)
        assertTrue(result.gracefulStopScheduled)
        assertFalse(result.autoRestartSupported)
        assertTrue(result.message.contains("manual restart"))
        assertEquals(1, shutdownCalls)
    }
}
