package top.bilibili.core

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import top.bilibili.BiliConfig
import top.bilibili.BiliConfigManager
import top.bilibili.connector.OutgoingPart
import top.bilibili.connector.PlatformConnectorManager
import top.bilibili.connector.PlatformConnectorPrepareResult
import top.bilibili.connector.PlatformConnectorReloadResult
import top.bilibili.connector.PlatformContact
import top.bilibili.connector.PreparedPlatformConnector
import top.bilibili.config.ConfigManager
import top.bilibili.core.resource.LambdaResourcePartition
import top.bilibili.core.resource.ResourceStopReport
import top.bilibili.core.resource.ResourceStrictness
import top.bilibili.core.resource.ResourceSupervisor
import top.bilibili.core.resource.ShutdownPhase
import top.bilibili.data.BiliCookie
import top.bilibili.data.BiliMessage
import top.bilibili.data.DynamicDetail
import top.bilibili.data.LiveDetail
import top.bilibili.service.CacheMaintenanceService
import top.bilibili.service.DefaultMessageGateway
import top.bilibili.service.FirstRunService
import top.bilibili.service.MessageEventDispatchService
import top.bilibili.service.MessageGatewayProvider
import top.bilibili.service.RuntimeWarmupService
import top.bilibili.service.StartupDataInitService
import top.bilibili.service.TaskBootstrapService
import top.bilibili.service.closeServiceClient
import top.bilibili.tasker.BiliCheckTasker
import top.bilibili.tasker.BiliTasker
import top.bilibili.utils.parsePlatformContact
import top.bilibili.utils.ImageCache
import top.bilibili.utils.closeUtilsClient
import top.bilibili.webui.config.WebUiConfig
import top.bilibili.webui.config.WebUiSettings
import top.bilibili.webui.server.WebUiManager
import top.bilibili.webui.server.WebUiReloadPlan
import top.bilibili.webui.service.WebUiConfigHotReloadApplyService
import top.bilibili.webui.service.WebUiConfigHotReloadCoordinator
import java.io.File
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.io.path.Path

/**
 * Bot 当前所处的生命周期状态。
 */
enum class BotLifecycleState {
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
}

/**
 * 启动入口的同步结果，用于阻止主线程在初始化失败后继续保持进程存活。
 */
enum class BotStartResult {
    STARTED,
    FAILED,
}

/**
 * Bot 运行期入口，负责启动、停机与公共资源管理。
 */
object BiliBiliBot : CoroutineScope {
    val logger = LoggerFactory.getLogger(BiliBiliBot::class.java)

    private var job: Job? = SupervisorJob()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Default + (job ?: SupervisorJob().also { job = it })

    private val isRunning = AtomicBoolean(false)
    private val lifecycleState = AtomicReference(BotLifecycleState.STOPPED)
    private var startTime: Long = 0L

    val dataFolder = File("data")
    val dataFolderPath: Path = Path("data")

    val tempPath: Path = Path("temp").also { path ->
        if (!path.toFile().exists()) {
            path.toFile().mkdirs()
        }
    }

    var cookie = BiliCookie()

    private var connectorManager: PlatformConnectorManager? = null
    // Bot 启动层只保留 WebUI 生命周期接线权，不在 core 内承载任何路由或页面逻辑。
    private var webUiManager: WebUiManager? = null
    private var webUiManagerPendingStop: WebUiManager? = null
    private var webUiReloadJob: Job? = null
    private var webUiConfigHotReloadCoordinator: WebUiConfigHotReloadCoordinator? = null

    lateinit var config: top.bilibili.config.BotConfig
        private set

    private var eventCollectorJob: Job? = null
    private var startupDataInitJob: Job? = null
    private var startupTaskBootstrapJob: Job? = null
    private val resourceSupervisor = ResourceSupervisor()

    /**
     * 判断平台适配器是否已经完成初始化。
     */
    fun isPlatformAdapterInitialized(): Boolean = connectorManager?.isInitialized() == true

    /**
     * 判断运行期配置是否已经加载完成。
     */
    fun isConfigInitialized(): Boolean = ::config.isInitialized

    /**
     * 判断 Bot 是否处于停机流程中。
     */
    fun isStopping(): Boolean = lifecycleState.get() == BotLifecycleState.STOPPING

    /**
     * 返回当前生命周期状态快照，供只读运行态观测面安全消费。
     */
    fun currentLifecycleState(): BotLifecycleState = lifecycleState.get()

    /**
     * 统一重载 manager-owned 配置快照，并同步刷新 core 缓存过的 bot 配置与图片主题配置。
     */
    fun reloadManagedConfiguration() {
        BiliConfigManager.reloadAll()
        ConfigManager.reload()
        config = ConfigManager.botConfig
        top.bilibili.data.BiliImageTheme.reload()
        top.bilibili.data.BiliImageQuality.reload()
        top.bilibili.draw.FontManager.reloadRuntimeConfig()
    }

    /**
     * 热重载安装已验证的 bot.yml 快照，保持 core 缓存与 ConfigManager 同步。
     */
    fun installRuntimeConfig(configSnapshot: top.bilibili.config.BotConfig) {
        config = configSnapshot.normalizedBotConfig()
    }

    /**
     * 返回已初始化的运行期配置，不存在时抛出异常。
     */
    fun requireConfig(): top.bilibili.config.BotConfig {
        require(::config.isInitialized) {
            "配置尚未加载。"
        }
        return config
    }

    var uid: Long = 0L
    var tagid: Int = 0
    private var commandLineDebugMode: Boolean? = null

    /**
     * 同步 debugMode 到系统属性和已初始化的 Logback logger，保证热重载与冷启动日志级别一致。
     */
    fun applyDebugLoggingRuntime(configSnapshot: BiliConfig): Boolean {
        val debugMode = commandLineDebugMode ?: configSnapshot.enableConfig.debugMode
        val level = if (debugMode) Level.DEBUG else Level.INFO
        System.setProperty("APP_LOG_LEVEL", level.levelStr)
        LoggerContext::class.java.cast(
            LoggerFactory.getILoggerFactory(),
        ).let { context ->
            context.getLogger("top.bilibili").level = level
            context.getLogger("ROOT").level = level
        }
        return debugMode
    }

    val dynamicChannel = ObservableChannel<DynamicDetail>(20)
    val liveChannel = ObservableChannel<LiveDetail>(20)
    val messageChannel = ObservableChannel<BiliMessage>(20)

    /** 汇总三条 core 业务队列的填充度，监控侧只读取计数而不接触消息内容。 */
    fun localQueueSnapshots(): List<LocalQueueSnapshot> = listOf(
        dynamicChannel.snapshot("dynamicChannel"),
        liveChannel.snapshot("liveChannel"),
        messageChannel.snapshot("messageChannel"),
    )
    val liveUsers = mutableMapOf<Long, Long>()

    /**
     * 运行期统一通过 connector manager 访问平台能力，避免业务层再感知 raw adapter。
     */
    fun requireConnectorManager(): PlatformConnectorManager {
        return connectorManager ?: error("平台连接管理器尚未初始化，请先完成启动。")
    }

    /**
     * 准备已验证 bot.yml 候选平台连接；失败时 connector manager 保持旧代际。
     */
    fun preparePlatformConnector(candidateConfig: top.bilibili.config.BotConfig): PlatformConnectorPrepareResult {
        return requireConnectorManager().prepareReload(candidateConfig)
    }

    /**
     * 提交已准备的平台连接代际；失败时调用方必须关闭未提交候选并恢复旧运行态。
     */
    fun commitPlatformConnector(prepared: PreparedPlatformConnector): PlatformConnectorReloadResult {
        return requireConnectorManager().commitReload(prepared)
    }

    /**
     * WebUI 配置热重载协调器绑定 Bot 根生命周期，WebUI server 重启时不能丢失正在处理的保存任务。
     */
    fun requireWebUiConfigHotReloadCoordinator(): WebUiConfigHotReloadCoordinator {
        return webUiConfigHotReloadCoordinator ?: WebUiConfigHotReloadApplyService().let { applyService ->
            WebUiConfigHotReloadCoordinator(
                scope = this,
                applyAction = applyService::apply,
                applyPersistedBiliDataAction = applyService::applyAlreadyPersistedBiliData,
            )
        }.also { coordinator ->
            webUiConfigHotReloadCoordinator = coordinator
        }
    }

    /**
     * WebUI 运行面变更只生成响应后调度计划，不在配置 apply 阶段直接关闭当前 HTTP 请求。
     */
    fun planWebUiReload(current: WebUiConfig, next: WebUiConfig): WebUiReloadPlan {
        val currentConfig = current.normalized()
        val nextConfig = next.normalized()
        if (!currentConfig.enabled && !nextConfig.enabled) {
            return WebUiReloadPlan(restartRequired = false, message = "webui disabled")
        }

        val nextSettings = nextConfig.toSettings()
        val currentSettings = currentConfig.toSettings()
        val restartRequired = when {
            currentConfig.enabled != nextConfig.enabled -> true
            !nextConfig.enabled -> false
            webUiManager == null -> true
            else -> currentSettings != nextSettings
        }
        if (!restartRequired) {
            return WebUiReloadPlan(restartRequired = false, message = "webui unchanged")
        }

        val redirect = if (nextConfig.enabled && (!currentConfig.enabled || currentSettings.host != nextSettings.host || currentSettings.port != nextSettings.port)) {
            "http://${nextSettings.host}:${nextSettings.port}/"
        } else {
            null
        }
        return WebUiReloadPlan(
            restartRequired = true,
            webUiRedirectUrl = redirect,
            message = "webui restart scheduled",
            nextSettings = nextSettings,
        )
    }

    /**
     * WebUI 重启先验证新监听器可启动，旧入口的停止再延迟执行以保护当前保存响应。
     */
    fun scheduleWebUiReload(plan: WebUiReloadPlan) {
        if (!plan.restartRequired) {
            return
        }
        val nextSettings = plan.nextSettings ?: return
        webUiReloadJob?.cancel()
        webUiReloadJob = null
        applyScheduledWebUiReload(nextSettings)
    }

    /**
     * 执行已提交配置对应的 WebUI start/stop；新入口成功前不能关闭旧入口。
     */
    private fun applyScheduledWebUiReload(nextSettings: WebUiSettings) {
        val previousManager = webUiManager
        if (previousManager?.settings == nextSettings && webUiManagerPendingStop == null) {
            // 回滚时旧入口可能从未离线，当前设置已经匹配时不做无意义重启。
            logger.info("WebUI 已处于目标热重载配置，跳过重复切换")
            return
        }
        if (!nextSettings.enabled) {
            webUiReloadJob = launch {
                // 禁用 WebUI 仍需等当前保存响应返回后再关闭本地管理入口。
                delay(300)
                if (isStopping()) {
                    logger.info("Bot 正在停机，取消 WebUI 禁用计划")
                    return@launch
                }
                previousManager?.stop()
                if (webUiManager === previousManager) {
                    webUiManager = null
                }
                webUiManagerPendingStop?.stop()
                webUiManagerPendingStop = null
            }
            logger.info("WebUI 已按热重载配置禁用")
            return
        }

        val pendingStopManager = webUiManagerPendingStop
        if (pendingStopManager?.settings?.sameEndpointAs(nextSettings) == true) {
            // 回滚到仍在延迟停止窗口内的旧入口时，直接恢复引用并关闭候选入口。
            previousManager?.stop()
            webUiManager = pendingStopManager
            webUiManagerPendingStop = null
            logger.info("WebUI 已恢复到热重载前入口: http://${nextSettings.host}:${nextSettings.port}/")
            return
        }

        val nextManager = WebUiManager(
            nextSettings,
            logWindowStartEpochMillis = startTime,
        )
        if (previousManager?.settings?.sameEndpointAs(nextSettings) == true) {
            // 同一监听端点无法双开；先释放旧端口，失败时立即尝试恢复旧管理入口。
            previousManager.stop()
            try {
                nextManager.start()
            } catch (throwable: Throwable) {
                runCatching { previousManager.start() }
                    .onFailure { restartError ->
                        logger.error("WebUI 热重载失败后恢复旧入口也失败: ${restartError.message}", restartError)
                    }
                throw throwable
            }
            webUiManager = nextManager
            logger.info("WebUI 已按热重载配置重新启动: http://${nextSettings.host}:${nextSettings.port}/")
            return
        }

        // 先绑定新监听器，若端口冲突或配置错误抛出，旧 WebUI 仍保持服务。
        nextManager.start()
        webUiManager = nextManager
        webUiManagerPendingStop = previousManager
        webUiReloadJob = launch {
            // 轻微延迟把旧监听器关闭放到当前 HTTP 响应之后，避免请求被自己的 stop() 截断。
            delay(300)
            if (isStopping()) {
                logger.info("Bot 正在停机，跳过旧 WebUI 关闭计划")
                return@launch
            }
            if (webUiManagerPendingStop === previousManager) {
                previousManager?.stop()
                webUiManagerPendingStop = null
            }
        }
        logger.info("WebUI 已按热重载配置重新启动: http://${nextSettings.host}:${nextSettings.port}/")
    }

    /**
     * WebUI 同端点重启不能先启动新实例，否则旧 server 仍占用端口。
     */
    private fun WebUiSettings.sameEndpointAs(other: WebUiSettings): Boolean {
        return host == other.host && port == other.port
    }

    /**
     * 读取类路径资源流。
     */
    @Deprecated("使用 useResourceAsStream 或 getResourceBytes 替代", ReplaceWith("useResourceAsStream(path) { it.readBytes() }"))
    fun getResourceAsStream(path: String): InputStream? {
        val resourcePath = if (path.startsWith("/")) path.substring(1) else path
        return this::class.java.classLoader.getResourceAsStream(resourcePath)
    }

    /**
     * 以 `use` 语义读取类路径资源流，确保流被及时关闭。
     */
    inline fun <T> useResourceAsStream(path: String, block: (InputStream) -> T): T? {
        val resourcePath = if (path.startsWith("/")) path.substring(1) else path
        return this::class.java.classLoader.getResourceAsStream(resourcePath)?.use(block)
    }

    /**
     * 读取类路径资源的完整字节数组。
     */
    fun getResourceBytes(path: String): ByteArray? {
        val resourcePath = if (path.startsWith("/")) path.substring(1) else path
        return this::class.java.classLoader.getResourceAsStream(resourcePath)?.use {
            it.readBytes()
        }
    }

    /**
     * 启动 Bot 并初始化配置、平台适配器、任务与资源分区。
     * @return `STARTED` 表示已进入运行态，`FAILED` 表示初始化失败且不得进入永久等待。
     */
    fun start(enableDebug: Boolean? = null): BotStartResult {
        if (!isRunning.compareAndSet(false, true)) {
            logger.warn("Bot 已在运行中，忽略重复启动请求")
            return if (lifecycleState.get() == BotLifecycleState.RUNNING) BotStartResult.STARTED else BotStartResult.FAILED
        }

        lifecycleState.set(BotLifecycleState.STARTING)
        startTime = System.currentTimeMillis()

        if (enableDebug != null) {
            commandLineDebugMode = enableDebug
        }

        try {
            BiliConfigManager.init()
            val debugMode = applyDebugLoggingRuntime(BiliConfigManager.config)
            if (debugMode) {
                val source = if (commandLineDebugMode == true) "命令行" else "配置文件"
                logger.debug("已从${source}启用调试模式")
            }
        } catch (e: Exception) {
            logger.error("初始化配置失败: ${e.message}", e)
            isRunning.set(false)
            lifecycleState.set(BotLifecycleState.STOPPED)
            return BotStartResult.FAILED
        }

        logger.info("========================================")
        logger.info("  欢迎使用 Hoshimi-Cat-Bot")
        logger.info("========================================")

        try {
            resourceSupervisor.reset()

            logger.info("正在初始化必要目录...")
            listOf("config", "data", "temp", "logs").forEach { dir ->
                File(dir).apply {
                    if (!exists()) {
                        mkdirs()
                        logger.debug("已创建目录: $dir")
                    }
                }
            }

            logger.info("正在加载 Bot 配置...")
            ConfigManager.init()
            config = ConfigManager.botConfig
            top.bilibili.data.BiliImageTheme.reload()
            top.bilibili.data.BiliImageQuality.reload()

            if (BiliConfigManager.config.enableConfig.cacheClearEnable) {
                logger.info("正在清理启动缓存...")
                CacheMaintenanceService.clearAllCache()
            }

            if (!config.validateSelectedPlatform()) {
                logger.error("平台配置无效，请检查 config/bot.yml 中的 {}", config.selectedPlatformType())
                isRunning.set(false)
                lifecycleState.set(BotLifecycleState.STOPPED)
                return BotStartResult.FAILED
            }

            if (config.webui.enabled) {
                logger.info("正在启动 WebUI...")
                // WebUI 仅作为可选管理面启动，具体服务器与路由细节保持在 webui 包内部。
                webUiManager = WebUiManager(
                    config.webui.toSettings(),
                    logWindowStartEpochMillis = startTime,
                ).also { manager ->
                    manager.start()
                }
            } else {
                logger.info("WebUI 未启用，跳过启动")
            }

            logger.info("正在初始化平台适配器...")
            // 启动入口只依赖 connector manager，由其统一封装平台选择、适配器组装与生命周期。
            connectorManager = PlatformConnectorManager(config).also { manager ->
                manager.initialize()
            }
            MessageGatewayProvider.register(
                DefaultMessageGateway(
                    sendMessageEntryPoint = { contact, message -> requireConnectorManager().sendMessage(contact, message) },
                    adminContactProvider = { BiliConfigManager.config.normalizedAdminSubject() },
                    logger = logger,
                ),
            )

            eventCollectorJob = launch {
                requireConnectorManager().eventFlow.collect { event ->
                    try {
                        withTimeout(5_000) {
                            MessageEventDispatchService.handleMessageEvent(event)
                        }
                    } catch (_: TimeoutCancellationException) {
                        logger.warn("消息事件处理超时: ${event.chatType}")
                    } catch (e: Exception) {
                        if (isStopping()) {
                            logger.debug("停机期间忽略事件分发异常: ${e.message}")
                        } else {
                            logger.error("分发消息事件失败", e)
                        }
                    }
                }
            }

            requireConnectorManager().start()

            registerResourcePartitions()

            startupDataInitJob = launch {
                try {
                    withTimeout(60_000) {
                        // 适当延迟能给平台连接和基础资源留出稳定时间，避免冷启动时初始化链路相互争抢。
                        delay(3_000)
                        StartupDataInitService.initBiliData()
                    }
                } catch (_: TimeoutCancellationException) {
                    logger.error("启动数据初始化超时")
                    stop("startup-data-timeout")
                } finally {
                    startupDataInitJob = null
                }
            }

            startupTaskBootstrapJob = launch {
                try {
                    withTimeout(120_000) {
                        // 任务启动放到连接与基础数据之后，可以减少首屏启动时的并发峰值。
                        delay(5_000)
                        TaskBootstrapService.startTasks()
                        // 在正式流量前预热运行热点，减少首小时内 CodeCache/Metaspace 的持续补增长。
                        RuntimeWarmupService.warmupOnceAfterStartup()
                        delay(1_000)
                        FirstRunService.checkFirstRun(config)
                    }
                } catch (_: TimeoutCancellationException) {
                    logger.error("任务启动超时")
                } finally {
                    startupTaskBootstrapJob = null
                }
            }

            lifecycleState.set(BotLifecycleState.RUNNING)
            logger.info("Bot 启动成功")
            return BotStartResult.STARTED
        } catch (e: Exception) {
            logger.error("Bot 启动失败: ${e.message}", e)
            stop("startup-failure")
            return BotStartResult.FAILED
        }
    }

    /**
     * 触发一次完整的停机流程，并在必要时执行兜底资源回收。
     */
    fun stop(reason: String = "shutdown-request") {
        val currentState = lifecycleState.get()
        if (currentState == BotLifecycleState.STOPPING || currentState == BotLifecycleState.STOPPED) {
            logger.info("当前生命周期状态为 $currentState，忽略停止请求")
            return
        }

        if (!isRunning.compareAndSet(true, false) && currentState != BotLifecycleState.STARTING) {
            logger.warn("Bot 未运行，忽略停止请求")
            lifecycleState.compareAndSet(BotLifecycleState.STARTING, BotLifecycleState.STOPPED)
            return
        }

        lifecycleState.set(BotLifecycleState.STOPPING)
        logger.info("正在停止 Bot，原因=$reason")

        var report: ResourceStopReport? = null
        var usedFallback = false

        try {
            report = runCatching {
                runBlocking { resourceSupervisor.stopAll() }
            }.onFailure {
                logger.error("资源总管停止失败: ${it.message}", it)
            }.getOrNull()

            // 资源总管缺报告、未覆盖资源或出现失败时，仍要继续兜底回收，避免残留后台任务和连接。
            if (report == null || report.totalPartitions == 0 || !report.success) {
                usedFallback = true
                runBlocking { fallbackStopResources() }
            }

            if (::config.isInitialized) {
                runCatching { BiliConfigManager.saveData() }
                    .onFailure { logger.warn("停机时保存运行数据失败: ${it.message}", it) }
            }
        } catch (e: Exception) {
            logger.error("停机过程中发生未预期异常: ${e.message}", e)
        } finally {
            lifecycleState.set(BotLifecycleState.STOPPED)
            logShutdownSummary(reason, report, usedFallback)
            logger.info("Bot 已停止")
        }
    }

    /**
     * 返回当前运行状态是否满足基本健康条件。
     */
    fun isHealthy(): Boolean {
        return lifecycleState.get() == BotLifecycleState.RUNNING &&
            isRunning.get() &&
            job?.isActive == true &&
            isPlatformAdapterInitialized()
    }

    /**
     * 返回本次运行已持续的秒数。
     */
    fun getUptimeSeconds(): Long {
        return if (isRunning.get()) {
            (System.currentTimeMillis() - startTime) / 1000
        } else {
            0L
        }
    }

    /**
     * 返回当前进程启动时间戳；未运行时返回 0，避免 WebUI 展示过期启动时间。
     */
    fun getStartTimeEpochMillis(): Long {
        return if (isRunning.get()) {
            startTime
        } else {
            0L
        }
    }

    /**
     * 向已配置的管理员联系人发送纯文本消息。
     */
    suspend fun sendAdminMessage(message: String): Boolean {
        return MessageGatewayProvider.require().sendAdminMessage(message)
    }

    /**
     * 通过统一平台联系人向目标发送消息。
     */
    suspend fun sendMessage(contact: PlatformContact, message: List<OutgoingPart>): Boolean {
        return MessageGatewayProvider.require().sendMessage(contact, message)
    }

    /**
     * 注册停机时需要按阶段回收的全部资源分区。
     */
    private fun registerResourcePartitions() {
        resourceSupervisor.reset()

        resourceSupervisor.register(
            LambdaResourcePartition(
                id = "startup-delayed-jobs",
                owns = listOf("startupDataInitJob", "startupTaskBootstrapJob"),
                strictness = ResourceStrictness.RELAXED_LONG_RUNNING,
                shutdownPhase = ShutdownPhase.INGRESS,
                stopAction = {
                    // 停机阶段先取消延迟启动协程，避免出现“边关边起”导致的资源回收竞态。
                    startupDataInitJob?.cancelAndJoin()
                    startupDataInitJob = null
                    startupTaskBootstrapJob?.cancelAndJoin()
                    startupTaskBootstrapJob = null
                },
            ),
        )

        resourceSupervisor.register(
            LambdaResourcePartition(
                id = "scope-job",
                owns = listOf("BiliBiliBot.job"),
                strictness = ResourceStrictness.RELAXED_LONG_RUNNING,
                shutdownPhase = ShutdownPhase.ROOT_SCOPE,
                stopAction = {
                    try {
                        withTimeout(10_000) {
                            job?.cancelAndJoin()
                        }
                    } catch (_: TimeoutCancellationException) {
                        logger.warn("等待根协程作用域停止超时")
                        job?.cancel()
                    } finally {
                        job = null
                    }
                },
            ),
        )

        resourceSupervisor.register(
            LambdaResourcePartition(
                id = "skia-manager",
                owns = listOf("SkiaManager", "FontManager"),
                strictness = ResourceStrictness.RELAXED_LONG_RUNNING,
                shutdownPhase = ShutdownPhase.DEPENDENCIES,
                stopAction = {
                    top.bilibili.skia.SkiaManager.shutdown()
                    logger.info("Skia 管理器已停止")
                },
            ),
        )

        resourceSupervisor.register(
            LambdaResourcePartition(
                id = "bili-client",
                owns = listOf("biliClient"),
                strictness = ResourceStrictness.STRICT,
                shutdownPhase = ShutdownPhase.DEPENDENCIES,
                stopAction = {
                    closeUtilsClient()
                    logger.info("共享 biliClient 已停止")
                },
            ),
        )

        resourceSupervisor.register(
            LambdaResourcePartition(
                id = "service-bili-client",
                owns = listOf("service.client"),
                strictness = ResourceStrictness.STRICT,
                shutdownPhase = ShutdownPhase.DEPENDENCIES,
                stopAction = {
                    closeServiceClient()
                    logger.info("服务共享客户端已停止")
                },
            ),
        )

        resourceSupervisor.register(
            LambdaResourcePartition(
                id = "check-tasker-bili-client",
                owns = listOf("BiliCheckTasker.client"),
                strictness = ResourceStrictness.STRICT,
                shutdownPhase = ShutdownPhase.DEPENDENCIES,
                stopAction = {
                    BiliCheckTasker.closeSharedClient()
                    logger.info("BiliCheckTasker 共享客户端已停止")
                },
            ),
        )

        resourceSupervisor.register(
            LambdaResourcePartition(
                id = "image-cache",
                owns = listOf("ImageCache"),
                strictness = ResourceStrictness.STRICT,
                shutdownPhase = ShutdownPhase.DEPENDENCIES,
                stopAction = {
                    ImageCache.close()
                    logger.info("图片缓存已停止")
                },
            ),
        )

        resourceSupervisor.register(
            LambdaResourcePartition(
                id = "taskers",
                owns = listOf("BiliTasker.*"),
                strictness = ResourceStrictness.RELAXED_LONG_RUNNING,
                shutdownPhase = ShutdownPhase.WORKERS,
                stopAction = {
                    val report = BiliTasker.cancelAll(timeoutMs = 10_000)
                    if (!report.success) {
                        error("任务器停止失败: ${report.failures}")
                    }
                },
            ),
        )

        resourceSupervisor.register(
            LambdaResourcePartition(
                id = "gateway-platform",
                owns = listOf("PlatformConnectorManager", "MessageGatewayProvider"),
                strictness = ResourceStrictness.RELAXED_LONG_RUNNING,
                shutdownPhase = ShutdownPhase.INGRESS,
                stopAction = {
                    // 先停入口再清空发送网关，避免停机过程中继续接收事件或产生新的发送请求。
                    if (isPlatformAdapterInitialized()) {
                        requireConnectorManager().stop()
                    }
                    connectorManager = null
                    MessageGatewayProvider.unregister()
                },
            ),
        )

        resourceSupervisor.register(
            LambdaResourcePartition(
                id = "webui-manager",
                owns = listOf("WebUiManager"),
                strictness = ResourceStrictness.RELAXED_LONG_RUNNING,
                shutdownPhase = ShutdownPhase.INGRESS,
                stopAction = {
                    // WebUI 属于本地管理入口，停机时需要先停止接收新请求，再继续释放底层依赖。
                    webUiReloadJob?.cancelAndJoin()
                    webUiReloadJob = null
                    webUiManagerPendingStop?.stop()
                    webUiManagerPendingStop = null
                    webUiManager?.stop()
                    webUiManager = null
                },
            ),
        )

        resourceSupervisor.register(
            LambdaResourcePartition(
                id = "webui-config-hot-reload",
                owns = listOf("WebUiConfigHotReloadCoordinator"),
                strictness = ResourceStrictness.RELAXED_LONG_RUNNING,
                shutdownPhase = ShutdownPhase.INGRESS,
                stopAction = {
                    // 热重载协调器属于 Bot 级管理入口；停机时先拒收新保存并收敛 pending job。
                    webUiConfigHotReloadCoordinator?.closeForShutdown(timeoutMs = 10_000)
                    webUiConfigHotReloadCoordinator = null
                },
            ),
        )

        resourceSupervisor.register(
            LambdaResourcePartition(
                id = "event-collector",
                owns = listOf("eventCollectorJob"),
                strictness = ResourceStrictness.STRICT,
                shutdownPhase = ShutdownPhase.INGRESS,
                stopAction = {
                    eventCollectorJob?.cancelAndJoin()
                    eventCollectorJob = null
                },
            ),
        )

        resourceSupervisor.register(
            LambdaResourcePartition(
                id = "channels",
                owns = listOf("dynamicChannel", "liveChannel", "messageChannel"),
                strictness = ResourceStrictness.STRICT,
                shutdownPhase = ShutdownPhase.CHANNELS,
                stopAction = {
                    dynamicChannel.close()
                    liveChannel.close()
                    messageChannel.close()
                    logger.debug("所有消息通道已关闭")
                },
            ),
        )
    }

    /**
     * 在资源总管异常或覆盖不足时，按旧流程兜底回收关键资源。
     */
    private suspend fun fallbackStopResources() {
        runCatching {
            // 兜底路径同样要先取消启动延迟协程，避免停机后又触发初始化/拉起任务器。
            startupDataInitJob?.cancelAndJoin()
            startupDataInitJob = null
            startupTaskBootstrapJob?.cancelAndJoin()
            startupTaskBootstrapJob = null
        }.onFailure {
            logger.warn("兜底停止启动延迟协程失败: ${it.message}", it)
        }

        runCatching {
            // 兜底停机同样先关闭本地管理入口，避免停机期间继续接入新的 HTTP 请求。
            webUiReloadJob?.cancelAndJoin()
            webUiReloadJob = null
            webUiManagerPendingStop?.stop()
            webUiManagerPendingStop = null
            webUiManager?.stop()
            webUiManager = null
        }.onFailure {
            logger.warn("兜底停止 WebUI 失败: ${it.message}", it)
        }

        runCatching {
            // 协调器即使不随 WebUI manager 生命周期重建，也必须在兜底路径中显式关闭。
            webUiConfigHotReloadCoordinator?.closeForShutdown(timeoutMs = 10_000)
            webUiConfigHotReloadCoordinator = null
        }.onFailure {
            logger.warn("兜底停止 WebUI 配置热重载协调器失败: ${it.message}", it)
        }

        runCatching {
            if (isPlatformAdapterInitialized()) {
                requireConnectorManager().stop()
            }
            connectorManager = null
            MessageGatewayProvider.unregister()
        }.onFailure {
            logger.warn("兜底停止入口资源失败: ${it.message}", it)
        }

        runCatching {
            eventCollectorJob?.cancelAndJoin()
            eventCollectorJob = null
        }.onFailure {
            logger.warn("兜底停止事件收集器失败: ${it.message}", it)
        }

        runCatching {
            val report = BiliTasker.cancelAll(timeoutMs = 10_000)
            if (!report.success) {
                logger.warn("兜底停止任务器存在失败项: ${report.failures}")
            }
        }.onFailure {
            logger.warn("兜底停止任务器失败: ${it.message}", it)
        }

        runCatching {
            dynamicChannel.close()
            liveChannel.close()
            messageChannel.close()
        }.onFailure {
            logger.warn("兜底关闭通道失败: ${it.message}", it)
        }

        runCatching { ImageCache.close() }
            .onFailure { logger.warn("兜底关闭图片缓存失败: ${it.message}", it) }

        runCatching { closeUtilsClient() }
            .onFailure { logger.warn("兜底关闭 biliClient 失败: ${it.message}", it) }

        runCatching { closeServiceClient() }
            .onFailure { logger.warn("兜底关闭服务共享客户端失败: ${it.message}", it) }

        runCatching { BiliCheckTasker.closeSharedClient() }
            .onFailure { logger.warn("兜底关闭 BiliCheckTasker 客户端失败: ${it.message}", it) }

        runCatching { top.bilibili.skia.SkiaManager.shutdown() }
            .onFailure { logger.warn("兜底关闭 Skia 失败: ${it.message}", it) }

        runCatching {
            withTimeout(10_000) {
                job?.cancelAndJoin()
            }
        }.onFailure {
            logger.warn("兜底停止根协程作用域失败: ${it.message}", it)
            // join 超时时直接取消即可，避免停机线程被永久阻塞在根作用域回收上。
            job?.cancel()
        }
        job = null
    }

    /**
     * 输出一次包含阶段统计与失败详情的停机摘要。
     */
    private fun logShutdownSummary(
        reason: String,
        report: ResourceStopReport?,
        usedFallback: Boolean,
    ) {
        if (report == null) {
            logger.warn("停机摘要: 原因=$reason，资源总管报告缺失，是否使用兜底=$usedFallback")
            return
        }

        val phaseSummary = report.phaseReports.joinToString(separator = "; ") { phase ->
            "${phase.phase}:${phase.stoppedPartitions}/${phase.totalPartitions} in ${phase.durationMs}ms"
        }
        val message =
            "停机摘要: 原因=$reason，成功=${report.success}，总分区=${report.totalPartitions}，失败分区=${report.failedPartitions}，是否使用兜底=$usedFallback，阶段=[$phaseSummary]，失败详情=${report.failures}"
        if (report.success && !usedFallback) {
            logger.info(message)
        } else {
            logger.warn(message)
        }
    }
}
