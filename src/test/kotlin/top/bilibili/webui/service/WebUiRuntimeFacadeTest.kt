package top.bilibili.webui.service

import top.bilibili.connector.PlatformHttpClientSnapshot
import top.bilibili.connector.PlatformObservabilitySnapshot
import top.bilibili.connector.PlatformRuntimeStatus
import top.bilibili.webui.model.WebUiBiliAccountStatusDto
import top.bilibili.webui.model.WebUiHostRuntimeStatusDto
import top.bilibili.webui.model.WebUiResourceUsageDto
import top.bilibili.tasker.DailyPushStatsSnapshot
import top.bilibili.tasker.PushDeliveryRecordSnapshot
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
            appVersionProvider = { "v-test" },
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
            pushStatisticsProvider = {
                DailyPushStatsSnapshot(
                    date = "2026-05-20",
                    total = 8,
                    dynamic = 5,
                    live = 2,
                    liveClose = 1,
                    failed = 3,
                    lastSuccessAtEpochMillis = 1779254400000L,
                    recentRecords = emptyList(),
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
                )
            },
        )

        val snapshot = facade.readSummary()
        uptimeSeconds = 999L
        subscriptionCount = 77
        groupCount = 66

        assertEquals("RUNNING", snapshot.lifecycleState)
        assertEquals("v-test", snapshot.appVersion)
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
        assertEquals("SUPERVISOR_CONTROLLED", snapshot.restartRequestMode)
        assertFalse(snapshot.platformAdapterInitialized.not())
    }

    @Test
    fun `runtime summary should surface recent push records for the homepage`() {
        val facade = WebUiRuntimeFacade(
            lifecycleStateProvider = { "RUNNING" },
            uptimeSecondsProvider = { 42L },
            platformAdapterInitializedProvider = { true },
            webUiEnabledProvider = { true },
            appVersionProvider = { "v-test" },
            subscriptionCountProvider = { 5 },
            dynamicSubscriptionCountProvider = { 4 },
            bangumiSubscriptionCountProvider = { 1 },
            groupCountProvider = { 2 },
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
                    reconnectAttempts = 2,
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
            pushStatisticsProvider = {
                DailyPushStatsSnapshot(
                    date = "2026-05-20",
                    total = 2,
                    dynamic = 1,
                    live = 1,
                    liveClose = 0,
                    failed = 1,
                    lastSuccessAtEpochMillis = 1779254400000L,
                    recentRecords = listOf(
                        PushDeliveryRecordSnapshot(
                            timestampEpochMillis = 1779254700000L,
                            type = "LIVE",
                            success = false,
                            summary = "米哈游Official 正在直播：4.7版本前瞻特别节目",
                            target = "onebot11:group:10001",
                        ),
                        PushDeliveryRecordSnapshot(
                            timestampEpochMillis = 1779254600000L,
                            type = "DYNAMIC",
                            success = true,
                            summary = "LexBurner 发布了新动态",
                            target = "onebot11:group:10002",
                        ),
                    ),
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
                )
            },
        )

        val snapshot = facade.readSummary()

        assertEquals(2, snapshot.recentPushRecords.size)
        assertEquals("v-test", snapshot.appVersion)
        assertEquals("LIVE", snapshot.recentPushRecords.first().type)
        assertEquals("直播", snapshot.recentPushRecords.first().typeLabel)
        assertEquals(false, snapshot.recentPushRecords.first().success)
        assertEquals("发送失败", snapshot.recentPushRecords.first().statusLabel)
        assertEquals("米哈游Official 正在直播：4.7版本前瞻特别节目", snapshot.recentPushRecords.first().summary)
        assertEquals("DYNAMIC", snapshot.recentPushRecords.last().type)
        assertEquals("动态", snapshot.recentPushRecords.last().typeLabel)
        assertEquals(true, snapshot.recentPushRecords.last().success)
        assertEquals("已发送", snapshot.recentPushRecords.last().statusLabel)
    }

    @Test
    fun `host runtime collector should clamp usage percentages`() {
        val tempRoot = Files.createTempDirectory("webui-host-runtime").toFile()
        try {
            val snapshot = readHostRuntimeStatus(
                startedAtEpochMillisProvider = { 1779250800000L },
                systemTimeMillisProvider = { 1779254400000L },
                rootFileProvider = { tempRoot },
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
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
