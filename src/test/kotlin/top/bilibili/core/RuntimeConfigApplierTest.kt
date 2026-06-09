package top.bilibili.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import top.bilibili.BiliConfig
import top.bilibili.BiliConfigManager
import top.bilibili.BiliDataWrapper
import top.bilibili.config.BotConfig
import top.bilibili.config.NapCatConfig
import top.bilibili.config.PlatformConfig
import top.bilibili.connector.OutgoingPart
import top.bilibili.connector.PlatformAdapter
import top.bilibili.connector.PlatformCapability
import top.bilibili.connector.PlatformChatType
import top.bilibili.connector.PlatformConnectorManager
import top.bilibili.connector.PlatformConnectorPrepareResult
import top.bilibili.connector.PlatformConnectorReloadResult
import top.bilibili.connector.PlatformContact
import top.bilibili.connector.PlatformInboundMessage
import top.bilibili.connector.PlatformRuntimeStatus
import top.bilibili.connector.PlatformType
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import top.bilibili.webui.config.WebUiConfig
import top.bilibili.webui.model.WebUiConfigFileKind
import top.bilibili.webui.server.WebUiReloadPlan

class RuntimeConfigApplierTest {
    /**
     * applier 只安装已验证候选快照并刷新运行缓存，不负责候选配置写盘。
     */
    @Test
    fun `applier should install candidate snapshot and refresh runtime caches`() {
        var imageReloads = 0
        var clientCloses = 0
        var taskerRefreshes = 0
        val applier = RuntimeConfigApplier(
            reloadImageRuntime = { imageReloads += 1 },
            closeBiliClients = { clientCloses += 1 },
            refreshTaskers = { taskerRefreshes += 1 },
            installBiliConfigRuntimeSnapshot = { config ->
                BiliConfigManager.installConfigRuntimeSnapshot(config)
            },
            installBiliDataRuntimeSnapshot = { data ->
                BiliConfigManager.installDataRuntimeSnapshot(data)
            },
            preparePlatformConnector = {
                PlatformConnectorPrepareResult(success = true, prepared = null)
            },
        )
        val old = RuntimeConfigSnapshot(BiliConfig(admin = 1L), BiliDataWrapper(dataVersion = 4), BotConfig(firstRunFlag = 1))
        val candidate = RuntimeConfigSnapshot(
            BiliConfig(admin = 2L),
            BiliDataWrapper(dataVersion = 4),
            BotConfig(firstRunFlag = 2, platform = testPlatformConfig(host = "10.0.0.2")),
        )

        applier.applyBaseConfig(
            RuntimeConfigGeneration(
                oldSnapshot = old,
                candidateSnapshot = candidate,
                changedFiles = setOf(WebUiConfigFileKind.BILI_CONFIG, WebUiConfigFileKind.BILI_DATA, WebUiConfigFileKind.BOT_CONFIG),
            ),
        )

        assertEquals(2L, BiliConfigManager.config.admin)
        assertEquals(2, BiliBiliBot.requireConfig().firstRunFlag)
        assertEquals(1, imageReloads)
        assertEquals(1, clientCloses)
        assertEquals(1, taskerRefreshes)
    }

    /**
     * 候选平台连接必须先 prepare，提交失败时不能继续刷新其它运行缓存。
     */
    @Test
    fun `applier should stop before installing runtime when platform prepare fails`() {
        var installed = false
        var imageReloads = 0
        val applier = RuntimeConfigApplier(
            installBiliConfigRuntimeSnapshot = { installed = true },
            installBiliDataRuntimeSnapshot = { installed = true },
            installBotRuntimeSnapshot = { installed = true },
            reloadImageRuntime = { imageReloads += 1 },
            preparePlatformConnector = {
                PlatformConnectorPrepareResult(success = false, message = "candidate failed")
            },
        )
        val old = RuntimeConfigSnapshot(BiliConfig(admin = 1L), BiliDataWrapper(dataVersion = 4), BotConfig(firstRunFlag = 1))
        val candidate = RuntimeConfigSnapshot(
            BiliConfig(admin = 2L),
            BiliDataWrapper(dataVersion = 4),
            BotConfig(firstRunFlag = 2, platform = testPlatformConfig(host = "10.0.0.2")),
        )

        assertFailsWith<IllegalStateException> {
            applier.applyBaseConfig(
                RuntimeConfigGeneration(
                    oldSnapshot = old,
                    candidateSnapshot = candidate,
                    changedFiles = setOf(WebUiConfigFileKind.BOT_CONFIG),
                ),
            )
        }

        assertEquals(false, installed)
        assertEquals(0, imageReloads)
    }

    /**
     * 平台提交发生在内存态安装之后；成功提交前旧 connector 仍由 manager 保持活动。
     */
    @Test
    fun `applier should prepare and commit platform connector during candidate apply`() {
        val calls = mutableListOf<String>()
        val applier = RuntimeConfigApplier(
            installBiliConfigRuntimeSnapshot = { calls += "install-bili" },
            installBiliDataRuntimeSnapshot = { calls += "install-data" },
            installBotRuntimeSnapshot = { calls += "install-bot" },
            reloadImageRuntime = { calls += "reload-image" },
            closeBiliClients = { calls += "close-client" },
            refreshTaskers = { calls += "refresh-taskers" },
            preparePlatformConnector = {
                calls += "prepare-platform"
                PlatformConnectorPrepareResult(success = true, prepared = null)
            },
            commitPlatformConnector = {
                calls += "commit-platform"
                PlatformConnectorReloadResult(success = true)
            },
        )
        val old = RuntimeConfigSnapshot(BiliConfig(admin = 1L), BiliDataWrapper(dataVersion = 4), BotConfig(firstRunFlag = 1))
        val candidate = RuntimeConfigSnapshot(
            BiliConfig(admin = 2L),
            BiliDataWrapper(dataVersion = 4),
            BotConfig(firstRunFlag = 2, platform = testPlatformConfig(host = "10.0.0.2")),
        )

        applier.applyBaseConfig(
            RuntimeConfigGeneration(
                oldSnapshot = old,
                candidateSnapshot = candidate,
                changedFiles = setOf(WebUiConfigFileKind.BILI_CONFIG, WebUiConfigFileKind.BOT_CONFIG),
            ),
        )

        assertEquals(
            listOf("prepare-platform", "install-bili", "install-bot", "reload-image", "close-client", "refresh-taskers", "commit-platform"),
            calls,
        )
    }

    /**
     * WebUI 运行面变更只生成响应后调度计划，不能在候选 apply 期间直接 stop/start 当前 server。
     */
    @Test
    fun `applier should plan webui reload before install and schedule it after platform commit`() {
        val calls = mutableListOf<String>()
        val old = RuntimeConfigSnapshot(
            BiliConfig(admin = 1L),
            BiliDataWrapper(dataVersion = 4),
            BotConfig(webui = WebUiConfig(enabled = true, port = 18080)),
        )
        val candidate = RuntimeConfigSnapshot(
            BiliConfig(admin = 2L),
            BiliDataWrapper(dataVersion = 4),
            BotConfig(webui = WebUiConfig(enabled = true, port = 18081)),
        )
        val applier = RuntimeConfigApplier(
            installBiliConfigRuntimeSnapshot = { calls += "install-bili" },
            installBiliDataRuntimeSnapshot = { calls += "install-data" },
            installBotRuntimeSnapshot = { calls += "install-bot" },
            reloadImageRuntime = { calls += "reload-image" },
            closeBiliClients = { calls += "close-client" },
            refreshTaskers = { calls += "refresh-taskers" },
            preparePlatformConnector = {
                calls += "prepare-platform"
                PlatformConnectorPrepareResult(success = true, prepared = null)
            },
            commitPlatformConnector = {
                calls += "commit-platform"
                PlatformConnectorReloadResult(success = true)
            },
            planWebUiReload = { current, next ->
                calls += "plan-webui:${current.webui.port}->${next.webui.port}"
                WebUiReloadPlan(restartRequired = true, webUiRedirectUrl = "http://127.0.0.1:${next.webui.port}/")
            },
            scheduleWebUiReload = {
                calls += "schedule-webui:${it.webUiRedirectUrl}"
            },
        )

        val plan = applier.applyBaseConfig(
            RuntimeConfigGeneration(
                oldSnapshot = old,
                candidateSnapshot = candidate,
                changedFiles = setOf(WebUiConfigFileKind.BOT_CONFIG),
            ),
        )

        assertEquals("http://127.0.0.1:18081/", plan.webUiRedirectUrl)
        assertEquals(
            listOf(
                "plan-webui:18080->18081",
                "install-bot",
                "schedule-webui:http://127.0.0.1:18081/",
            ),
            calls,
        )
    }

    /**
     * 非 bot.yml 保存不能准备或提交平台 connector，避免普通配置热重载造成平台断连重连。
     */
    @Test
    fun `applier should not reload platform connector when bot config was not changed`() {
        val calls = mutableListOf<String>()
        val applier = RuntimeConfigApplier(
            installBiliConfigRuntimeSnapshot = { calls += "install-bili" },
            installBiliDataRuntimeSnapshot = { calls += "install-data" },
            installBotRuntimeSnapshot = { calls += "install-bot" },
            reloadImageRuntime = { calls += "reload-image" },
            closeBiliClients = { calls += "close-client" },
            refreshTaskers = { calls += "refresh-taskers" },
            preparePlatformConnector = {
                calls += "prepare-platform"
                PlatformConnectorPrepareResult(success = true, prepared = null)
            },
            commitPlatformConnector = {
                calls += "commit-platform"
                PlatformConnectorReloadResult(success = true)
            },
        )
        val old = RuntimeConfigSnapshot(BiliConfig(admin = 1L), BiliDataWrapper(dataVersion = 4), BotConfig(firstRunFlag = 1))
        val candidate = old.copy(biliConfig = BiliConfig(admin = 2L))

        applier.applyBaseConfig(
            RuntimeConfigGeneration(
                oldSnapshot = old,
                candidateSnapshot = candidate,
                changedFiles = setOf(WebUiConfigFileKind.BILI_CONFIG),
            ),
        )

        assertEquals(listOf("install-bili", "reload-image", "close-client", "refresh-taskers"), calls)
    }

    /**
     * bot.yml 保存仍必须执行平台 prepare/commit，保证平台连接参数热切换继续生效。
     */
    @Test
    fun `applier should reload platform connector when bot config changed`() {
        val calls = mutableListOf<String>()
        val applier = RuntimeConfigApplier(
            installBiliConfigRuntimeSnapshot = { calls += "install-bili" },
            installBiliDataRuntimeSnapshot = { calls += "install-data" },
            installBotRuntimeSnapshot = { calls += "install-bot" },
            reloadImageRuntime = { calls += "reload-image" },
            closeBiliClients = { calls += "close-client" },
            refreshTaskers = { calls += "refresh-taskers" },
            preparePlatformConnector = {
                calls += "prepare-platform"
                PlatformConnectorPrepareResult(success = true, prepared = null)
            },
            commitPlatformConnector = {
                calls += "commit-platform"
                PlatformConnectorReloadResult(success = true)
            },
        )
        val old = RuntimeConfigSnapshot(BiliConfig(admin = 1L), BiliDataWrapper(dataVersion = 4), BotConfig(firstRunFlag = 1))
        val candidate = old.copy(botConfig = BotConfig(platform = testPlatformConfig(host = "10.0.0.2")))

        applier.applyBaseConfig(
            RuntimeConfigGeneration(
                oldSnapshot = old,
                candidateSnapshot = candidate,
                changedFiles = setOf(WebUiConfigFileKind.BOT_CONFIG),
            ),
        )

        assertEquals(listOf("prepare-platform", "install-bot", "commit-platform"), calls)
    }

    /**
     * 候选提交失败时 applier 自身要恢复旧运行缓存，避免外层只恢复全局配置而遗漏 tasker 等派生状态。
     */
    @Test
    fun `applier should restore runtime caches when platform commit fails after candidate refresh`() {
        val calls = mutableListOf<String>()
        val applier = RuntimeConfigApplier(
            installBiliConfigRuntimeSnapshot = { config -> calls += "install-bili:${config.admin}" },
            installBiliDataRuntimeSnapshot = { data -> calls += "install-data:${data.dataVersion}" },
            installBotRuntimeSnapshot = { config -> calls += "install-bot:${config.firstRunFlag}" },
            reloadImageRuntime = { calls += "reload-image" },
            closeBiliClients = { calls += "close-client" },
            refreshTaskers = { calls += "refresh-taskers" },
            preparePlatformConnector = {
                calls += "prepare-platform"
                PlatformConnectorPrepareResult(success = true, prepared = null)
            },
            commitPlatformConnector = {
                calls += "commit-platform"
                PlatformConnectorReloadResult(success = false, message = "commit failed")
            },
        )
        val old = RuntimeConfigSnapshot(BiliConfig(admin = 1L), BiliDataWrapper(dataVersion = 4), BotConfig(firstRunFlag = 1))
        val candidate = RuntimeConfigSnapshot(
            BiliConfig(admin = 2L),
            BiliDataWrapper(dataVersion = 4),
            BotConfig(firstRunFlag = 2, platform = testPlatformConfig(host = "10.0.0.2")),
        )

        assertFailsWith<IllegalStateException> {
            applier.applyBaseConfig(
                RuntimeConfigGeneration(
                    oldSnapshot = old,
                    candidateSnapshot = candidate,
                    changedFiles = setOf(WebUiConfigFileKind.BILI_CONFIG, WebUiConfigFileKind.BOT_CONFIG),
                ),
            )
        }

        assertEquals(
            listOf(
                "prepare-platform",
                "install-bili:2",
                "install-bot:2",
                "reload-image",
                "close-client",
                "refresh-taskers",
                "commit-platform",
                "install-bili:1",
                "install-bot:1",
                "reload-image",
                "close-client",
                "refresh-taskers",
            ),
            calls,
        )
    }

    @Test
    fun `applier should restore runtime caches when webui reload fails after candidate install`() {
        val calls = mutableListOf<String>()
        val old = RuntimeConfigSnapshot(
            BiliConfig(admin = 1L),
            BiliDataWrapper(dataVersion = 4),
            BotConfig(webui = WebUiConfig(enabled = true, port = 18080), firstRunFlag = 1),
        )
        val candidate = RuntimeConfigSnapshot(
            BiliConfig(admin = 2L),
            BiliDataWrapper(dataVersion = 4),
            BotConfig(webui = WebUiConfig(enabled = true, port = 18081), firstRunFlag = 2),
        )
        val applier = RuntimeConfigApplier(
            installBiliConfigRuntimeSnapshot = { config -> calls += "install-bili:${config.admin}" },
            installBiliDataRuntimeSnapshot = { data -> calls += "install-data:${data.dataVersion}" },
            installBotRuntimeSnapshot = { config -> calls += "install-bot:${config.firstRunFlag}" },
            reloadImageRuntime = { calls += "reload-image" },
            closeBiliClients = { calls += "close-client" },
            refreshTaskers = { calls += "refresh-taskers" },
            preparePlatformConnector = {
                calls += "prepare-platform"
                PlatformConnectorPrepareResult(success = true, prepared = null)
            },
            commitPlatformConnector = {
                calls += "commit-platform"
                PlatformConnectorReloadResult(success = true)
            },
            planWebUiReload = { _, _ ->
                calls += "plan-webui"
                WebUiReloadPlan(restartRequired = true, webUiRedirectUrl = "http://127.0.0.1:18081/")
            },
            scheduleWebUiReload = {
                calls += "reload-webui"
                if (calls.count { call -> call == "reload-webui" } == 1) {
                    error("webui restart failed")
                }
            },
        )

        // WebUI 重启失败必须反馈到保存 job，并恢复已经切换的 bot.yml 运行态。
        assertFailsWith<IllegalStateException> {
            applier.applyBaseConfig(
                RuntimeConfigGeneration(
                    oldSnapshot = old,
                    candidateSnapshot = candidate,
                    changedFiles = setOf(WebUiConfigFileKind.BOT_CONFIG),
                ),
            )
        }

        assertEquals(
            listOf(
                "plan-webui",
                "install-bot:2",
                "reload-webui",
                "install-bot:1",
                "plan-webui",
                "reload-webui",
            ),
            calls,
        )
    }

    @Test
    fun `applier should close prepared connector when later runtime refresh fails`() = runBlocking {
        val oldAdapter = PreparedRecordingAdapter(sendResult = true)
        val candidateAdapter = PreparedRecordingAdapter(sendResult = true)
        val manager = PlatformConnectorManager(
            config = BotConfig(),
            adapterFactory = { oldAdapter },
        )
        manager.initialize()
        manager.start()
        val applier = RuntimeConfigApplier(
            installBiliConfigRuntimeSnapshot = {},
            installBiliDataRuntimeSnapshot = {},
            installBotRuntimeSnapshot = {},
            reloadImageRuntime = { error("image runtime failed") },
            closeBiliClients = {},
            refreshTaskers = {},
            preparePlatformConnector = { config ->
                manager.prepareReload(config, adapterFactory = { candidateAdapter })
            },
            commitPlatformConnector = { prepared ->
                manager.commitReload(requireNotNull(prepared))
            },
        )
        val old = RuntimeConfigSnapshot(BiliConfig(admin = 1L), BiliDataWrapper(dataVersion = 4), BotConfig(firstRunFlag = 1))
        val candidate = RuntimeConfigSnapshot(
            BiliConfig(admin = 2L),
            BiliDataWrapper(dataVersion = 4),
            BotConfig(firstRunFlag = 2, platform = testPlatformConfig(host = "10.0.0.2")),
        )

        // 运行态刷新在 commit 前失败时，已启动候选必须由 applier 关闭，不能遗留给后台重连循环。
        assertFailsWith<IllegalStateException> {
            applier.applyBaseConfig(
                RuntimeConfigGeneration(
                    oldSnapshot = old,
                    candidateSnapshot = candidate,
                    changedFiles = setOf(WebUiConfigFileKind.BILI_CONFIG, WebUiConfigFileKind.BOT_CONFIG),
                ),
            )
        }

        assertEquals(1, candidateAdapter.startCount)
        assertEquals(1, candidateAdapter.stopCount)
        assertEquals(0, oldAdapter.stopCount)
    }

    /**
     * 测试平台配置差异只改变连接参数，避免用 firstRunFlag 这类非平台字段误触发 connector reload。
     */
    private fun testPlatformConfig(host: String): PlatformConfig {
        return PlatformConfig(
            type = PlatformType.ONEBOT11,
            adapter = "onebot11",
            onebot11 = NapCatConfig(host = host, port = 3001),
        )
    }

    /**
     * 只记录 applier 触发的 connector 生命周期，避免 core 测试依赖 vendor transport。
     */
    private class PreparedRecordingAdapter(
        private val sendResult: Boolean,
    ) : PlatformAdapter {
        var startCount: Int = 0
            private set
        var stopCount: Int = 0
            private set
        private var connected: Boolean = false

        override val eventFlow = emptyFlow<PlatformInboundMessage>()

        override fun start() {
            startCount++
            connected = true
        }

        override suspend fun stop() {
            stopCount++
            connected = false
        }

        override fun declaredCapabilities(): Set<PlatformCapability> = emptySet()

        override suspend fun sendMessage(contact: PlatformContact, message: List<OutgoingPart>): Boolean = sendResult

        override fun runtimeStatus(): PlatformRuntimeStatus {
            return PlatformRuntimeStatus(connected = connected, reconnectAttempts = 0)
        }

        override suspend fun isContactReachable(contact: PlatformContact): Boolean = false

        override suspend fun canAtAll(contact: PlatformContact): Boolean = false
    }
}
