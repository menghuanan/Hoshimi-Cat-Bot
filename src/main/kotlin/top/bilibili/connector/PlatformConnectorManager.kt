package top.bilibili.connector

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import top.bilibili.config.BotConfig
import top.bilibili.connector.onebot11.core.KtorOneBot11Transport
import top.bilibili.connector.onebot11.generic.GenericOneBot11Adapter
import top.bilibili.connector.onebot11.vendors.llbot.LlBotAdapter
import top.bilibili.connector.onebot11.vendors.llbot.LlBotClient
import top.bilibili.connector.onebot11.vendors.napcat.NapCatAdapter
import top.bilibili.connector.onebot11.vendors.napcat.NapCatClient
import top.bilibili.connector.qqofficial.QQOfficialAdapter

/**
 * 平台生命周期入口；额外入站谓词由装配层注入，仅接触平台中立消息。
 * 群目录观察回调同样由装配层注入，仅接触中立群模型。
 */
class PlatformConnectorManager(
    config: BotConfig,
    private val adapterFactory: (() -> PlatformAdapter)? = null,
    private val bridgeScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val additionalInboundFilter: (PlatformInboundMessage) -> Boolean = { false },
    private val groupObserver: (String, PlatformGroupObservation) -> Unit = { _, _ -> },
) {
    private val lifecycleLock = Any()
    private var activeConfig: BotConfig = config
    private var platformAdapter: PlatformAdapter? = null
    private var groupGate: PlatformGroupObservationGate? = null
    private val stableEventFlow = MutableSharedFlow<PlatformInboundMessage>(extraBufferCapacity = 64)
    private var eventBridgeJob: Job? = null
    private var lifecycleState: ConnectorLifecycleState = ConnectorLifecycleState.IDLE

    /**
     * 启动层统一通过 manager 获取事件流，避免再感知具体 vendor 适配器的创建细节。
     */
    val eventFlow: Flow<PlatformInboundMessage> = stableEventFlow

    /**
     * 返回 manager 是否已持有运行期适配器实例，供启动链区分“已初始化”与“已启动”。
     */
    fun isInitialized(): Boolean = synchronized(lifecycleLock) { platformAdapter != null }

    /**
     * 先创建并缓存运行期适配器，便于调用方在 start 前完成事件订阅和网关注册。
     */
    fun initialize() {
        adapter()
    }

    /**
     * 统一启动当前平台适配器，并在异常时回滚生命周期状态避免残留半启动实例。
     */
    fun start() {
        val adapterToStart = synchronized(lifecycleLock) {
            if (
                lifecycleState == ConnectorLifecycleState.STARTING ||
                lifecycleState == ConnectorLifecycleState.STARTED
            ) {
                return
            }
            adapter().also {
                lifecycleState = ConnectorLifecycleState.STARTING
            }
        }

        try {
            adapterToStart.start()
            synchronized(lifecycleLock) {
                if (platformAdapter === adapterToStart) {
                    // 激活已完成握手的初始代际，并补发启动期间缓冲的群目录事件。
                    groupGate?.activate()
                    startEventBridgeLocked(adapterToStart)
                    lifecycleState = ConnectorLifecycleState.STARTED
                }
            }
        } catch (throwable: Throwable) {
            synchronized(lifecycleLock) {
                if (platformAdapter === adapterToStart) {
                    groupGate?.close()
                    groupGate = null
                    platformAdapter = null
                }
                lifecycleState = ConnectorLifecycleState.IDLE
            }
            throw throwable
        }
    }

    /**
     * connector manager 统一暴露 suspend 停机入口，确保底层 transport 可沿生命周期安全关闭。
     */
    suspend fun stop() {
        val adapterToStop = synchronized(lifecycleLock) {
            val currentAdapter = platformAdapter ?: return
            if (lifecycleState == ConnectorLifecycleState.STOPPING) {
                return
            }
            lifecycleState = ConnectorLifecycleState.STOPPING
            groupGate?.close()
            currentAdapter
        }

        try {
            adapterToStop.stop()
        } finally {
            synchronized(lifecycleLock) {
                if (platformAdapter === adapterToStop) {
                    eventBridgeJob?.cancel()
                    eventBridgeJob = null
                    platformAdapter = null
                    groupGate = null
                }
                lifecycleState = ConnectorLifecycleState.IDLE
            }
        }
    }

    /**
     * 准备候选平台代际；候选启动失败时只关闭候选，不触碰当前活动 adapter。
     */
    fun prepareReload(
        newConfig: BotConfig,
        adapterFactory: (() -> PlatformAdapter)? = null,
    ): PlatformConnectorPrepareResult {
        // 候选事件只进入本代际内存缓冲，prepare 失败不会污染持久群目录。
        val candidateGate = newGroupGate(newConfig)
        val candidateAdapter = createPlatformAdapter(newConfig, adapterFactory, candidateGate)
        return try {
            candidateAdapter.start()
            val ready = runBlocking {
                // start() 可能只启动后台重连循环；提交前必须等到候选真的进入 connected。
                candidateAdapter.awaitReadyForReload(candidateReadinessTimeoutMillis(newConfig))
            }
            if (!ready) {
                error("platform connector candidate did not become connected before timeout")
            }
            PlatformConnectorPrepareResult(
                success = true,
                prepared = PreparedPlatformConnector(
                    candidateConfig = newConfig,
                    candidateAdapter = candidateAdapter,
                    groupGate = candidateGate,
                ),
            )
        } catch (throwable: Throwable) {
            candidateGate.close()
            runBlockingStop(candidateAdapter)
            PlatformConnectorPrepareResult(
                success = false,
                message = throwable.message ?: "platform connector candidate start failed",
            )
        }
    }

    /**
     * 提交已启动的候选平台代际；交换完成后再关闭旧 adapter，避免候选失败中断旧入口。
     */
    fun commitReload(prepared: PreparedPlatformConnector): PlatformConnectorReloadResult {
        val oldAdapter = synchronized(lifecycleLock) {
            if (lifecycleState == ConnectorLifecycleState.STOPPING) {
                prepared.closeUncommitted()
                return PlatformConnectorReloadResult(success = false, message = "platform connector is stopping")
            }
            if (prepared.committed || prepared.closed) {
                return PlatformConnectorReloadResult(success = false, message = "platform connector candidate is not active")
            }
            val previous = platformAdapter
            groupGate?.close()
            eventBridgeJob?.cancel()
            platformAdapter = prepared.candidateAdapter
            activeConfig = prepared.candidateConfig
            groupGate = prepared.groupGate
            groupGate?.activate()
            startEventBridgeLocked(prepared.candidateAdapter)
            lifecycleState = ConnectorLifecycleState.STARTED
            prepared.committed = true
            previous
        }
        runBlockingStop(oldAdapter)
        return PlatformConnectorReloadResult(success = true)
    }

    /**
     * 回滚未提交候选只关闭候选 adapter，不修改 manager 当前活动代际。
     */
    fun rollbackReload(prepared: PreparedPlatformConnector) {
        prepared.closeUncommitted()
    }

    /**
     * 统一暴露平台发送入口，避免业务层继续拿 raw adapter 发送消息。
     */
    suspend fun sendMessage(contact: PlatformContact, message: List<OutgoingPart>): Boolean {
        return currentAdapter()?.sendMessage(contact, message) ?: false
    }

    /**
     * 统一暴露 capability guard，避免业务层继续直接依赖 adapter 实现。
     */
    suspend fun guardCapability(request: CapabilityRequest): CapabilityGuardResult {
        return currentAdapter()?.guardCapability(request)
            ?: CapabilityGuard.unsupported("platform adapter is not initialized")
    }

    /**
     * 返回当前平台运行状态；未初始化时也提供默认值，避免外层监控再判空。
     */
    fun runtimeStatus(): PlatformRuntimeStatus {
        return currentAdapter()?.runtimeStatus()
            ?: PlatformRuntimeStatus(
                connected = false,
                reconnectAttempts = 0,
            )
    }

    /**
     * 汇总当前平台 transport 的运行时观测快照；未初始化时返回带说明的空快照，避免守护链路直接抛异常。
     */
    fun runtimeObservability(): PlatformObservabilitySnapshot {
        return currentAdapter()?.runtimeObservability()
            ?: PlatformObservabilitySnapshot.empty("platform adapter is not initialized")
    }

    /** 只提供当前已提交官方连接身份；未启动、停机和非官方平台均不提供可选群上下文。 */
    fun groupContext(): PlatformGroupContext? = synchronized(lifecycleLock) {
        val gate = groupGate ?: return@synchronized null
        if (lifecycleState != ConnectorLifecycleState.STARTED || gate.accountId.isBlank()) return@synchronized null
        PlatformGroupContext(gate.accountId, gate.generation, platformAdapter?.runtimeStatus()?.connected == true)
    }

    /** 查询绑定指定运行代际，网络请求期间不持有生命周期锁，切换后丢弃旧结果。 */
    suspend fun lookupGroup(context: PlatformGroupContext, id: String, queryName: Boolean, queryState: Boolean): PlatformGroupProfile {
        val adapter = synchronized(lifecycleLock) {
            if (groupGate?.generation == context.generation && lifecycleState == ConnectorLifecycleState.STARTED) platformAdapter else null
        } ?: return PlatformGroupProfile()
        // 目录查询也走能力门禁，不允许其他适配器被强行调用官方补全接口。
        if (adapter.guardCapability(CapabilityRequest(PlatformCapability.GROUP_DIRECTORY)).stopCurrentOperation) return PlatformGroupProfile()
        val result = adapter.lookupGroup(id, queryName, queryState)
        return if (groupContext()?.generation == context.generation) result else PlatformGroupProfile()
    }

    /** 只有官方应用拥有群目录命名空间，其他适配器的默认回调保持空操作。 */
    private fun newGroupGate(config: BotConfig) = PlatformGroupObservationGate(
        if (config.selectedAdapterKind() == PlatformAdapterKind.QQ_OFFICIAL) config.platform.qqOfficial.appId else "",
        groupObserver,
    )

    /**
     * 将平台选择与 vendor 组装收口到 connector 层，避免业务启动入口继续直接 new adapter。
     * groupGate 接收本代际事件，避免候选状态泄漏给业务目录。
     */
    private fun createPlatformAdapter(
        config: BotConfig = activeConfig,
        overrideFactory: (() -> PlatformAdapter)? = adapterFactory,
        groupGate: PlatformGroupObservationGate,
    ): PlatformAdapter {
        overrideFactory?.let { factory ->
            return factory.invoke()
        }
        return when (config.selectedAdapterKind()) {
            PlatformAdapterKind.NAPCAT -> {
                val oneBotConfig = config.selectedOneBot11Config()
                NapCatAdapter(NapCatClient(oneBotConfig))
            }
            PlatformAdapterKind.LLBOT -> {
                val oneBotConfig = config.selectedOneBot11Config()
                LlBotAdapter(LlBotClient(oneBotConfig))
            }
            PlatformAdapterKind.ONEBOT11 -> {
                val oneBotConfig = config.selectedOneBot11Config()
                GenericOneBot11Adapter(KtorOneBot11Transport(oneBotConfig))
            }
            PlatformAdapterKind.QQ_OFFICIAL -> QQOfficialAdapter(
                config.platform.qqOfficial,
                additionalInboundFilter = additionalInboundFilter,
                groupObserver = groupGate::observe,
            )
        }
    }

    /**
     * manager 内部统一取出运行期适配器，禁止把 raw adapter 暴露给外层业务代码。
     */
    private fun adapter(): PlatformAdapter {
        return synchronized(lifecycleLock) {
            platformAdapter ?: createPlatformAdapter(groupGate = newGroupGate(activeConfig).also { groupGate = it }).also { adapter ->
                platformAdapter = adapter
            }
        }
    }

    /**
     * 仅在 manager 当前仍持有活动代际时返回 adapter，避免停机后被发送链路隐式拉起新实例。
     */
    private fun currentAdapter(): PlatformAdapter? {
        return synchronized(lifecycleLock) {
            platformAdapter
        }
    }

    /**
     * manager 拥有稳定事件桥；切换 adapter 时只换桥接 job，不更换暴露给 core 的 Flow 实例。
     */
    private fun startEventBridgeLocked(adapter: PlatformAdapter) {
        eventBridgeJob = bridgeScope.launch {
            adapter.eventFlow.collect { event ->
                stableEventFlow.emit(event)
            }
        }
    }

    /**
     * 显式区分 connector manager 当前所处的代际状态，避免 stop 后误复用已关闭实例。
     */
    private enum class ConnectorLifecycleState {
        IDLE,
        STARTING,
        STARTED,
        STOPPING,
    }

    /**
     * stop 是 suspend API；reload 准备/提交同步入口只在短生命周期清理候选或旧 adapter 时阻塞等待。
     */
    private fun runBlockingStop(adapter: PlatformAdapter?) {
        adapter ?: return
        runBlocking {
            runCatching { adapter.stop() }
        }
    }

    /**
     * 候选连通等待沿用当前平台连接超时，并设置合理下限避免配置过小导致误杀瞬时握手。
     */
    private fun candidateReadinessTimeoutMillis(config: BotConfig): Long {
        return when (config.selectedAdapterKind()) {
            PlatformAdapterKind.ONEBOT11,
            PlatformAdapterKind.NAPCAT,
            PlatformAdapterKind.LLBOT -> config.selectedOneBot11Config().connectTimeout.coerceAtLeast(1_000L)
            PlatformAdapterKind.QQ_OFFICIAL -> 15_000L
        }
    }
}

/**
 * 候选平台代际只由 PlatformConnectorManager 创建和提交，不向业务层暴露 vendor 类型。
 */
data class PreparedPlatformConnector internal constructor(
    internal val candidateConfig: BotConfig,
    internal val candidateAdapter: PlatformAdapter,
    internal val groupGate: PlatformGroupObservationGate,
) {
    internal var committed: Boolean = false
    internal var closed: Boolean = false

    /**
     * 未提交候选关闭时只回收候选 adapter，不能影响 manager 当前活动 adapter。
     */
    fun closeUncommitted() {
        if (committed || closed) {
            return
        }
        closed = true
        groupGate.close()
        runBlocking {
            runCatching { candidateAdapter.stop() }
        }
    }
}

/**
 * 平台热切换准备结果只报告候选是否启动成功，不暴露具体 vendor adapter。
 */
data class PlatformConnectorPrepareResult(
    val success: Boolean,
    val prepared: PreparedPlatformConnector? = null,
    val message: String = "",
)

/**
 * 平台热切换提交结果只报告成功与失败原因，不暴露具体 vendor adapter。
 */
data class PlatformConnectorReloadResult(
    val success: Boolean,
    val message: String = "",
)
