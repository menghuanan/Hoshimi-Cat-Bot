package top.bilibili.webui.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebUiRuntimeFacadeTest {
    @Test
    fun `runtime summary should return only read only snapshot values`() {
        var uptimeSeconds = 120L
        var subscriptionCount = 3
        var groupCount = 2
        val facade = WebUiRuntimeFacade(
            lifecycleStateProvider = { "RUNNING" },
            uptimeSecondsProvider = { uptimeSeconds },
            platformAdapterInitializedProvider = { true },
            webUiEnabledProvider = { true },
            restartSupportedProvider = { true },
            subscriptionCountProvider = { subscriptionCount },
            groupCountProvider = { groupCount },
        )

        val snapshot = facade.readSummary()
        uptimeSeconds = 999L
        subscriptionCount = 77
        groupCount = 66

        assertEquals("RUNNING", snapshot.lifecycleState)
        assertEquals(120L, snapshot.uptimeSeconds)
        assertEquals(3, snapshot.subscriptionCount)
        assertEquals(2, snapshot.groupCount)
        assertTrue(snapshot.platformReady)
        assertEquals("SUPERVISOR_CONTROLLED", snapshot.restartRequestMode)
        assertFalse(snapshot.platformAdapterInitialized.not())
    }
}
