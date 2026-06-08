package top.bilibili.webui.service

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import top.bilibili.webui.model.WebUiConfigBatchSaveRequestDto
import top.bilibili.webui.model.WebUiConfigFileKind
import top.bilibili.webui.model.WebUiConfigFileSaveOutcomeDto
import top.bilibili.webui.model.WebUiConfigHotReloadJobDto
import top.bilibili.webui.model.WebUiConfigHotReloadPhase

/**
 * WebUI 配置保存协调器保证前端保存信号串行处理，并把运行中的重复信号归并为下一次任务。
 */
class WebUiConfigHotReloadCoordinator(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
    private val debounceMillis: Long = 3_000L,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val delayMillis: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val applyAction: suspend (String, WebUiConfigBatchSaveRequestDto) -> WebUiConfigHotReloadJobDto,
    private val applyPersistedBiliDataAction: suspend (String) -> WebUiConfigHotReloadJobDto = WebUiConfigHotReloadApplyService()::applyAlreadyPersistedBiliData,
    private val autoStartWorker: Boolean = true,
    private val onJobUpdatedForTest: (WebUiConfigHotReloadJobDto) -> Unit = {},
) {
    companion object {
        /**
         * 兼容旧保存 facade 的协调器工厂，Task 9 会替换为完整 dry-run/persist/apply 链路。
         */
        fun fromConfigWriteFacade(configWriteFacade: WebUiConfigWriteFacade): WebUiConfigHotReloadCoordinator {
            return WebUiConfigHotReloadCoordinator(
                applyAction = { jobId, request ->
                    val outcomes = mutableListOf<WebUiConfigFileSaveOutcomeDto>()
                    request.biliConfig?.let { input ->
                        outcomes += WebUiConfigFileSaveOutcomeDto(
                            file = WebUiConfigFileKind.BILI_CONFIG,
                            result = configWriteFacade.saveBiliConfig(input),
                        )
                    }
                    request.biliData?.let { input ->
                        outcomes += WebUiConfigFileSaveOutcomeDto(
                            file = WebUiConfigFileKind.BILI_DATA,
                            result = configWriteFacade.saveBiliData(input),
                        )
                    }
                    request.botConfig?.let { input ->
                        outcomes += WebUiConfigFileSaveOutcomeDto(
                            file = WebUiConfigFileKind.BOT_CONFIG,
                            result = configWriteFacade.saveBotConfig(input),
                        )
                    }
                    val success = outcomes.all { outcome -> outcome.result.success }
                    WebUiConfigHotReloadJobDto(
                        jobId = jobId,
                        phase = if (success) WebUiConfigHotReloadPhase.APPLIED else WebUiConfigHotReloadPhase.FAILED,
                        files = request.fileKinds(),
                        outcomes = outcomes,
                        message = outcomes.firstOrNull { outcome -> !outcome.result.success }?.result?.message
                            ?: "configuration save job applied",
                    )
                },
            )
        }
    }

    private val lock = Any()
    private val jobs = linkedMapOf<String, WebUiConfigHotReloadJobDto>()
    private var workerJob: Job? = null
    private var pendingSubmission: WebUiConfigHotReloadSubmission? = null
    private val pendingJobIds = mutableListOf<String>()
    private val completionCallbacksByJobId = mutableMapOf<String, (WebUiConfigHotReloadJobDto) -> Unit>()
    private var pendingSignalCount = 0
    private var pausedBatchForTest: RunningBatch? = null
    private var acceptingJobs = true

    /**
     * 接收一次前端保存信号；每个信号都有独立 jobId，完成回调用于记录该 job 的最终保存结果。
     */
    fun submit(
        request: WebUiConfigBatchSaveRequestDto,
        onCompleted: (WebUiConfigHotReloadJobDto) -> Unit = {},
    ): WebUiConfigHotReloadJobDto {
        return enqueueSubmission(BatchSaveSubmission(request), onCompleted)
    }

    /**
     * 订阅页已经完成 BiliData.yml 写盘时只提交运行态刷新信号，完成回调仅观察刷新结果不重复写盘。
     */
    fun submitPersistedBiliDataReload(
        onCompleted: (WebUiConfigHotReloadJobDto) -> Unit = {},
    ): WebUiConfigHotReloadJobDto {
        return enqueueSubmission(PersistedBiliDataReloadSubmission, onCompleted)
    }

    /**
     * 所有保存来源共享同一 pending 槽，保证设置页和订阅页不会并发应用运行代际。
     */
    private fun enqueueSubmission(
        submission: WebUiConfigHotReloadSubmission,
        onCompleted: (WebUiConfigHotReloadJobDto) -> Unit,
    ): WebUiConfigHotReloadJobDto {
        val jobId = UUID.randomUUID().toString()
        val snapshot = WebUiConfigHotReloadJobDto(
            jobId = jobId,
            phase = WebUiConfigHotReloadPhase.QUEUED,
            files = submission.fileKinds(),
            acceptedAtEpochMillis = nowMillis(),
        )
        synchronized(lock) {
            if (!acceptingJobs) {
                val rejected = snapshot.copy(
                    phase = WebUiConfigHotReloadPhase.FAILED,
                    message = "hot reload coordinator is shutting down",
                )
                jobs[jobId] = rejected
                return rejected
            }
            jobs[jobId] = snapshot
            completionCallbacksByJobId[jobId] = onCompleted
            pendingSubmission = pendingSubmission.mergeWith(submission)
            pendingJobIds += jobId
            pendingSignalCount += 1
            if (autoStartWorker && workerJob?.isActive != true) {
                workerJob = scope.launch { drainLoop() }
            }
        }
        return snapshot
    }

    /**
     * 读取任务快照；路由层只返回 DTO，不暴露内部队列引用。
     */
    fun readJob(jobId: String): WebUiConfigHotReloadJobDto? = synchronized(lock) { jobs[jobId] }

    /**
     * 测试入口同步清空队列，生产路径只由协程 worker 调用 drainLoop。
     */
    internal fun drainForTest() {
        runBlocking {
            // 自动 worker 可能已取走 pending 批次但尚未写回结果；测试同步入口先等待它收敛。
            val runningWorker = synchronized(lock) { workerJob?.takeIf { it.isActive } }
            runningWorker?.join()
            drainLoop()
        }
    }

    /**
     * Bot 停机时停止接收新任务，等待当前 worker 收敛并把 pending job 标记为失败。
     */
    suspend fun closeForShutdown(timeoutMs: Long = 10_000L) {
        val running = synchronized(lock) {
            acceptingJobs = false
            workerJob
        }
        if (running != null) {
            withTimeoutOrNull(timeoutMs) {
                running.cancelAndJoin()
            }
        }
        synchronized(lock) {
            pendingJobIds.forEach { jobId ->
                jobs[jobId]?.let { job ->
                    jobs[jobId] = job.copy(
                        phase = WebUiConfigHotReloadPhase.FAILED,
                        message = "hot reload job cancelled during bot shutdown",
                    )
                }
                completionCallbacksByJobId.remove(jobId)
            }
            pendingSubmission = null
            pendingJobIds.clear()
            pendingSignalCount = 0
        }
    }

    /**
     * 测试入口捕获首个批次但暂不执行，用来模拟保存任务正在运行时的新信号。
     */
    internal fun markRunningForTest(expectedJobId: String) {
        synchronized(lock) {
            if (pausedBatchForTest == null) {
                pausedBatchForTest = takePendingBatchLocked(delayBeforeRun = false)
            }
            require(pausedBatchForTest?.jobIds?.contains(expectedJobId) == true) {
                "expected job is not in the paused running batch"
            }
        }
    }

    /**
     * 测试入口执行被暂停的批次并继续清空 pending 队列，验证下一批归并行为。
     */
    internal fun completeRunningForTest() {
        runBlocking {
            completeCurrentBatchForTest()
            drainLoop()
        }
    }

    /**
     * 测试入口只完成已捕获的当前批次，不触碰运行期间新增的 pending job。
     */
    internal fun completeCurrentBatchForTest() {
        val batch = synchronized(lock) {
            pausedBatchForTest ?: takePendingBatchLocked(delayBeforeRun = false)
        } ?: return
        synchronized(lock) {
            pausedBatchForTest = null
        }
        runBlocking { runOneTask(batch) }
    }

    /**
     * 单 worker 循环每次原子取出当前 pending 批次；运行中新增的 job 留给下一批处理。
     */
    private suspend fun drainLoop() {
        var shouldDelay = false
        while (true) {
            val batch = synchronized(lock) {
                takePendingBatchLocked(delayBeforeRun = shouldDelay)
            } ?: return
            if (batch.delayBeforeRun) {
                delayMillis(debounceMillis)
            }
            runOneTask(batch)
            shouldDelay = true
        }
    }

    /**
     * 取批次时一次性清空 pending 槽，确保当前批次完成时不会误更新后续 job。
     */
    private fun takePendingBatchLocked(delayBeforeRun: Boolean): RunningBatch? {
        val submission = pendingSubmission ?: return null
        val jobIds = pendingJobIds.toList()
        pendingSubmission = null
        pendingJobIds.clear()
        val signalCount = pendingSignalCount.coerceAtLeast(1)
        pendingSignalCount = 0
        return RunningBatch(
            jobIds = jobIds,
            submission = submission,
            signalCount = signalCount,
            delayBeforeRun = delayBeforeRun,
        )
    }

    /**
     * 运行批次时只更新 RunningBatch 捕获的 jobId，避免 pending job 被上一个批次提前标记完成。
     */
    private suspend fun runOneTask(batch: RunningBatch) {
        val startedAt = nowMillis()
        batch.jobIds.forEach { jobId ->
            updateJob(
                jobId,
                requireNotNull(readJob(jobId)).copy(
                    phase = WebUiConfigHotReloadPhase.SAVING,
                    startedAtEpochMillis = startedAt,
                    coalescedSignals = batch.signalCount,
                    files = batch.submission.fileKinds(),
                ),
            )
        }

        val representativeJobId = batch.jobIds.firstOrNull() ?: return
        val result = runCatching {
            runSubmission(representativeJobId, batch.submission)
        }.getOrElse { error ->
            WebUiConfigHotReloadJobDto(
                jobId = representativeJobId,
                phase = WebUiConfigHotReloadPhase.FAILED,
                files = batch.submission.fileKinds(),
                coalescedSignals = batch.signalCount,
                message = error.message ?: "config hot reload failed",
            )
        }
        val completedAt = nowMillis()
        batch.jobIds.forEach { jobId ->
            val completedJob = result.copy(
                jobId = jobId,
                files = result.files.ifEmpty { batch.submission.fileKinds() },
                coalescedSignals = batch.signalCount,
                acceptedAtEpochMillis = readJob(jobId)?.acceptedAtEpochMillis ?: 0L,
                startedAtEpochMillis = startedAt,
                completedAtEpochMillis = completedAt,
            )
            notifyCompletion(jobId, completedJob)
            updateJob(jobId, completedJob)
        }
    }

    /**
     * 运行批次根据内部提交类型选择保存或已持久化刷新路径，避免订阅 mutation 重复写盘。
     */
    private suspend fun runSubmission(
        jobId: String,
        submission: WebUiConfigHotReloadSubmission,
    ): WebUiConfigHotReloadJobDto {
        return when (submission) {
            is BatchSaveSubmission -> applyAction(jobId, submission.request)
            PersistedBiliDataReloadSubmission -> applyPersistedBiliDataAction(jobId)
        }
    }

    /**
     * job 状态写回集中经过一个测试 hook，便于断言批次归属是否正确。
     */
    private fun updateJob(jobId: String, job: WebUiConfigHotReloadJobDto) {
        synchronized(lock) {
            jobs[jobId] = job
        }
        onJobUpdatedForTest(job)
    }

    /**
     * 完成回调用于路由层恢复配置保存审计；异常不能反向影响已经完成的热重载任务。
     */
    private fun notifyCompletion(jobId: String, job: WebUiConfigHotReloadJobDto) {
        val callback = synchronized(lock) {
            completionCallbacksByJobId.remove(jobId)
        }
        runCatching { callback?.invoke(job) }
    }

    /**
     * 运行批次明确记录捕获的 jobId 集合；后续 pending 不得被当前批次更新。
     */
    private data class RunningBatch(
        val jobIds: List<String>,
        val submission: WebUiConfigHotReloadSubmission,
        val signalCount: Int,
        val delayBeforeRun: Boolean,
    )
}

/**
 * 批量请求的文件列表只描述本次涉及的 owner 边界，供 DTO、队列和审计统一使用。
 */
fun WebUiConfigBatchSaveRequestDto.fileKinds(): List<WebUiConfigFileKind> {
    return buildList {
        if (biliConfig != null) add(WebUiConfigFileKind.BILI_CONFIG)
        if (biliData != null) add(WebUiConfigFileKind.BILI_DATA)
        if (botConfig != null) add(WebUiConfigFileKind.BOT_CONFIG)
    }
}

/**
 * 同一 pending 窗口内按文件合并请求；相同文件以后提交的 payload 为准。
 */
private fun WebUiConfigBatchSaveRequestDto?.mergeWith(
    next: WebUiConfigBatchSaveRequestDto,
): WebUiConfigBatchSaveRequestDto {
    val current = this ?: WebUiConfigBatchSaveRequestDto()
    return WebUiConfigBatchSaveRequestDto(
        biliConfig = next.biliConfig ?: current.biliConfig,
        biliData = next.biliData ?: current.biliData,
        botConfig = next.botConfig ?: current.botConfig,
    )
}

/**
 * 协调器内部提交模型区分浏览器 batch payload 和后端已落盘数据刷新信号，后者不进入 DTO 契约。
 */
private sealed interface WebUiConfigHotReloadSubmission {
    fun fileKinds(): List<WebUiConfigFileKind>
}

/**
 * 浏览器设置页 batch 保存会执行 dry-run、persist 和 runtime apply 全链路。
 */
private data class BatchSaveSubmission(
    val request: WebUiConfigBatchSaveRequestDto,
) : WebUiConfigHotReloadSubmission {
    override fun fileKinds(): List<WebUiConfigFileKind> = request.fileKinds()
}

/**
 * 订阅页 mutation 已经完成 BiliData.yml 持久化，只需要串行刷新 BiliData 运行缓存。
 */
private data object PersistedBiliDataReloadSubmission : WebUiConfigHotReloadSubmission {
    override fun fileKinds(): List<WebUiConfigFileKind> = listOf(WebUiConfigFileKind.BILI_DATA)
}

/**
 * 同一 pending 窗口内 SettingsPage 显式 BiliData payload 优先于已持久化订阅刷新信号。
 */
private fun WebUiConfigHotReloadSubmission?.mergeWith(
    next: WebUiConfigHotReloadSubmission,
): WebUiConfigHotReloadSubmission {
    return when {
        this == null -> next
        this is BatchSaveSubmission && next is BatchSaveSubmission -> BatchSaveSubmission(request.mergeWith(next.request))
        this is BatchSaveSubmission -> this
        next is BatchSaveSubmission -> next
        else -> PersistedBiliDataReloadSubmission
    }
}
