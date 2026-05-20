package top.bilibili.webui.service

import top.bilibili.connector.PlatformHttpClientSnapshot
import top.bilibili.connector.PlatformObservabilitySnapshot
import top.bilibili.connector.PlatformRuntimeStatus
import top.bilibili.webui.model.WebUiBiliAccountStatusDto
import top.bilibili.webui.model.WebUiDockerRuntimeStatusDto
import top.bilibili.webui.model.WebUiHostRuntimeStatusDto
import top.bilibili.webui.model.WebUiResourceUsageDto
import top.bilibili.webui.model.WebUiTodayPushStatsDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.nio.file.Files

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
            dynamicSubscriptionCountProvider = { 2 },
            bangumiSubscriptionCountProvider = { 1 },
            groupCountProvider = { groupCount },
            accountStatusProvider = {
                WebUiBiliAccountStatusDto(
                    loggedIn = true,
                    uid = 2233L,
                    cookieConfigured = true,
                )
            },
            platformRuntimeStatusProvider = {
                PlatformRuntimeStatus(
                    connected = true,
                    reconnectAttempts = 4,
                )
            },
            platformObservabilityProvider = {
                PlatformObservabilitySnapshot(
                    clients = listOf(
                        PlatformHttpClientSnapshot(
                            adapterName = "onebot11",
                            transportName = "napcat",
                            webSocketSessionActive = true,
                        ),
                    ),
                )
            },
            todayPushStatsProvider = {
                WebUiTodayPushStatsDto(
                    date = "2026-05-20",
                    total = 8,
                    dynamic = 5,
                    live = 2,
                    liveClose = 1,
                    failed = 3,
                    lastSuccessAtEpochMillis = 1779254400000L,
                )
            },
            hostStatusProvider = {
                WebUiHostRuntimeStatusDto(
                    startedAtEpochMillis = 1779250800000L,
                    systemTimeEpochMillis = 1779254400000L,
                    systemLoadAverage = 0.42,
                    cpuUsagePercent = 23.5,
                    memory = WebUiResourceUsageDto(
                        usedBytes = 384L * 1024L * 1024L,
                        totalBytes = 1024L * 1024L * 1024L,
                        usagePercent = 37.5,
                    ),
                    storage = WebUiResourceUsageDto(
                        usedBytes = 40L * 1024L * 1024L * 1024L,
                        totalBytes = 100L * 1024L * 1024L * 1024L,
                        usagePercent = 40.0,
                    ),
                    docker = WebUiDockerRuntimeStatusDto(
                        detected = true,
                        evidence = ".dockerenv",
                    ),
                )
            },
        )

        val snapshot = facade.readSummary()
        uptimeSeconds = 999L
        subscriptionCount = 77
        groupCount = 66

        assertEquals("RUNNING", snapshot.lifecycleState)
        assertEquals(120L, snapshot.uptimeSeconds)
        assertEquals(3, snapshot.subscriptionCount)
        assertEquals(2, snapshot.groupCount)
        assertEquals(2, snapshot.dynamicSubscriptionCount)
        assertEquals(1, snapshot.bangumiSubscriptionCount)
        assertTrue(snapshot.platformReady)
        assertTrue(snapshot.account.loggedIn)
        assertEquals(2233L, snapshot.account.uid)
        assertTrue(snapshot.webSocket.connected)
        assertEquals(4, snapshot.webSocket.reconnectAttempts)
        assertEquals(1, snapshot.webSocket.activeSessionCount)
        assertEquals(listOf("onebot11/napcat"), snapshot.webSocket.transports)
        assertEquals(8, snapshot.todayPushStats.total)
        assertEquals(5, snapshot.todayPushStats.dynamic)
        assertEquals(1779250800000L, snapshot.host.startedAtEpochMillis)
        assertEquals(1779254400000L, snapshot.host.systemTimeEpochMillis)
        assertEquals(0.42, snapshot.host.systemLoadAverage)
        assertEquals(23.5, snapshot.host.cpuUsagePercent)
        assertEquals(37.5, snapshot.host.memory.usagePercent)
        assertEquals(40.0, snapshot.host.storage.usagePercent)
        assertTrue(snapshot.host.docker.detected)
        assertEquals(".dockerenv", snapshot.host.docker.evidence)
        assertEquals("SUPERVISOR_CONTROLLED", snapshot.restartRequestMode)
        assertFalse(snapshot.platformAdapterInitialized.not())
    }

    @Test
    fun `host runtime collector should detect docker and clamp usage percentages`() {
        val tempRoot = Files.createTempDirectory("webui-host-runtime").toFile()
        try {
            val snapshot = readHostRuntimeStatus(
                startedAtEpochMillisProvider = { 1779250800000L },
                systemTimeMillisProvider = { 1779254400000L },
                rootFileProvider = { tempRoot },
                dockerEnvExistsProvider = { true },
                cgroupTextProvider = { "0::/docker/abcdef" },
                cpuLoadProvider = { 1.25 },
                systemLoadAverageProvider = { 0.75 },
                memoryUsageProvider = {
                    WebUiResourceUsageDto(
                        usedBytes = 2048L,
                        totalBytes = 1024L,
                        usagePercent = 200.0,
                    )
                },
            )

            assertEquals(1779250800000L, snapshot.startedAtEpochMillis)
            assertEquals(1779254400000L, snapshot.systemTimeEpochMillis)
            assertEquals(0.75, snapshot.systemLoadAverage)
            assertEquals(100.0, snapshot.cpuUsagePercent)
            assertNotNull(snapshot.storage.usagePercent)
            assertTrue(snapshot.storage.usagePercent!! in 0.0..100.0)
            assertEquals(100.0, snapshot.memory.usagePercent)
            assertTrue(snapshot.docker.detected)
            assertEquals(".dockerenv", snapshot.docker.evidence)
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
