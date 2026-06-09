package top.bilibili.webui.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import top.bilibili.BiliConfig
import top.bilibili.BiliDataWrapper
import top.bilibili.config.BotConfig
import top.bilibili.core.RuntimeConfigGeneration
import top.bilibili.core.RuntimeConfigSnapshot
import top.bilibili.webui.server.WebUiReloadPlan
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import top.bilibili.webui.model.WebUiBiliConfigWriteRequestDto
import top.bilibili.webui.model.WebUiBotConfigWriteRequestDto
import top.bilibili.webui.model.WebUiConfigBatchSaveRequestDto
import top.bilibili.webui.model.WebUiConfigFileKind
import top.bilibili.webui.model.WebUiConfigHotReloadJobDto
import top.bilibili.webui.model.WebUiConfigHotReloadPhase
import top.bilibili.webui.model.WebUiConfigSaveResultDto

class WebUiConfigHotReloadCoordinatorTest {
    /**
     * 热重载任务 DTO 的枚举名称需要保持稳定，前端轮询时只消费这些公开阶段和文件边界。
     */
    @Test
    fun `hot reload job dto should serialize stable phase and file names`() {
        val dto = WebUiConfigHotReloadJobDto(
            jobId = "job-1",
            phase = WebUiConfigHotReloadPhase.APPLIED,
            files = listOf(WebUiConfigFileKind.BILI_CONFIG, WebUiConfigFileKind.BOT_CONFIG),
            coalescedSignals = 2,
            message = "applied",
        )

        val encoded = Json.encodeToString(WebUiConfigHotReloadJobDto.serializer(), dto)

        assertTrue(encoded.contains("APPLIED"))
        assertTrue(encoded.contains("BILI_CONFIG"))
        assertTrue(encoded.contains("BOT_CONFIG"))
        assertEquals(dto, Json.decodeFromString(WebUiConfigHotReloadJobDto.serializer(), encoded))
    }

    /**
     * 空闲协调器收到首个保存信号时应立即启动 worker，避免用户第一次点击还被 debounce 延迟。
     */
    @Test
    fun `first save should run immediately when coordinator is idle`() {
        val startedJobs = mutableListOf<String>()
        val coordinator = WebUiConfigHotReloadCoordinator(
            nowMillis = { 100L },
            delayMillis = {},
            applyAction = { jobId, _ ->
                startedJobs += jobId
                WebUiConfigHotReloadJobDto(jobId = jobId, phase = WebUiConfigHotReloadPhase.APPLIED)
            },
        )

        val job = coordinator.submit(WebUiConfigBatchSaveRequestDto())
        coordinator.drainForTest()

        assertEquals(WebUiConfigHotReloadPhase.APPLIED, coordinator.readJob(job.jobId)?.phase)
        assertEquals(listOf(job.jobId), startedJobs)
    }

    /**
     * 运行中的保存不能被后续信号污染；后续信号只归并到下一批，并按同文件最后 payload 获胜。
     */
    @Test
    fun `save signals while running should coalesce into one next job`() {
        var now = 0L
        val appliedFiles = mutableListOf<List<WebUiConfigFileKind>>()
        val coordinator = WebUiConfigHotReloadCoordinator(
            nowMillis = { now },
            delayMillis = { millis -> now += millis },
            applyAction = { jobId, request ->
                val files = request.fileKinds()
                appliedFiles += files
                WebUiConfigHotReloadJobDto(jobId = jobId, phase = WebUiConfigHotReloadPhase.APPLIED, files = files)
            },
            autoStartWorker = false,
        )

        val first = coordinator.submit(batchWithBiliConfigToken("a"))
        coordinator.markRunningForTest(first.jobId)
        coordinator.submit(batchWithBiliConfigToken("b"))
        coordinator.submit(batchWithBotToken("c"))
        coordinator.completeRunningForTest()

        assertEquals(2, appliedFiles.size)
        assertEquals(listOf(WebUiConfigFileKind.BILI_CONFIG), appliedFiles.first())
        assertEquals(setOf(WebUiConfigFileKind.BILI_CONFIG, WebUiConfigFileKind.BOT_CONFIG), appliedFiles.last().toSet())
    }

    /**
     * 订阅页已落盘刷新信号和设置页 batch 进入同一 pending 窗口时，两段运行态 apply 都必须保留。
     */
    @Test
    fun `pending batch save should preserve persisted BiliData reload signal`() {
        val calls = mutableListOf<String>()
        val coordinator = WebUiConfigHotReloadCoordinator(
            nowMillis = { 100L },
            delayMillis = {},
            applyAction = { jobId, request ->
                calls += "batch:${request.fileKinds().joinToString(",")}"
                WebUiConfigHotReloadJobDto(
                    jobId = jobId,
                    phase = WebUiConfigHotReloadPhase.APPLIED,
                    files = request.fileKinds(),
                )
            },
            applyPersistedBiliDataAction = { jobId ->
                calls += "persisted:${WebUiConfigFileKind.BILI_DATA}"
                WebUiConfigHotReloadJobDto(
                    jobId = jobId,
                    phase = WebUiConfigHotReloadPhase.APPLIED,
                    files = listOf(WebUiConfigFileKind.BILI_DATA),
                )
            },
            autoStartWorker = false,
        )

        val dataJob = coordinator.submitPersistedBiliDataReload()
        val configJob = coordinator.submit(batchWithBiliConfigToken("bili"))
        coordinator.drainForTest()

        assertEquals(listOf("batch:BILI_CONFIG", "persisted:BILI_DATA"), calls)
        assertEquals(
            setOf(WebUiConfigFileKind.BILI_CONFIG, WebUiConfigFileKind.BILI_DATA),
            coordinator.readJob(dataJob.jobId)?.files?.toSet(),
        )
        assertEquals(
            setOf(WebUiConfigFileKind.BILI_CONFIG, WebUiConfigFileKind.BILI_DATA),
            coordinator.readJob(configJob.jobId)?.files?.toSet(),
        )
    }

    /**
     * 设置页本身保存 BiliData 时已经覆盖数据运行态 apply，不应再追加同窗口的订阅刷新子任务。
     */
    @Test
    fun `pending batch with explicit BiliData should not duplicate persisted reload`() {
        val calls = mutableListOf<String>()
        val coordinator = WebUiConfigHotReloadCoordinator(
            nowMillis = { 100L },
            delayMillis = {},
            applyAction = { jobId, request ->
                calls += "batch:${request.fileKinds().joinToString(",")}"
                WebUiConfigHotReloadJobDto(
                    jobId = jobId,
                    phase = WebUiConfigHotReloadPhase.APPLIED,
                    files = request.fileKinds(),
                )
            },
            applyPersistedBiliDataAction = { jobId ->
                calls += "persisted:${WebUiConfigFileKind.BILI_DATA}"
                WebUiConfigHotReloadJobDto(
                    jobId = jobId,
                    phase = WebUiConfigHotReloadPhase.APPLIED,
                    files = listOf(WebUiConfigFileKind.BILI_DATA),
                )
            },
            autoStartWorker = false,
        )

        coordinator.submitPersistedBiliDataReload()
        coordinator.submit(batchWithBiliDataToken("data"))
        coordinator.drainForTest()

        assertEquals(listOf("batch:BILI_DATA"), calls)
    }

    /**
     * batch 子任务失败也不能阻止已落盘的订阅数据刷新，否则订阅页保存仍会停留在旧运行态。
     */
    @Test
    fun `persisted BiliData reload should still run when coalesced batch fails`() {
        val calls = mutableListOf<String>()
        val coordinator = WebUiConfigHotReloadCoordinator(
            nowMillis = { 100L },
            delayMillis = {},
            applyAction = { jobId, request ->
                calls += "batch:${request.fileKinds().joinToString(",")}"
                WebUiConfigHotReloadJobDto(
                    jobId = jobId,
                    phase = WebUiConfigHotReloadPhase.FAILED,
                    files = request.fileKinds(),
                    message = "batch failed",
                )
            },
            applyPersistedBiliDataAction = { jobId ->
                calls += "persisted:${WebUiConfigFileKind.BILI_DATA}"
                WebUiConfigHotReloadJobDto(
                    jobId = jobId,
                    phase = WebUiConfigHotReloadPhase.APPLIED,
                    files = listOf(WebUiConfigFileKind.BILI_DATA),
                )
            },
            autoStartWorker = false,
        )

        val dataJob = coordinator.submitPersistedBiliDataReload()
        coordinator.submit(batchWithBiliConfigToken("bili"))
        coordinator.drainForTest()

        assertEquals(listOf("batch:BILI_CONFIG", "persisted:BILI_DATA"), calls)
        assertEquals(WebUiConfigHotReloadPhase.FAILED, coordinator.readJob(dataJob.jobId)?.phase)
    }

    /**
     * 当前批次完成时只能更新它捕获的 jobId，运行期间新增的 queued job 必须留给下一批。
     */
    @Test
    fun `pending jobs should not be completed by the currently running batch`() {
        val completed = mutableMapOf<String, WebUiConfigHotReloadPhase>()
        val coordinator = WebUiConfigHotReloadCoordinator(
            nowMillis = { 100L },
            delayMillis = {},
            applyAction = { jobId, request ->
                WebUiConfigHotReloadJobDto(
                    jobId = jobId,
                    phase = WebUiConfigHotReloadPhase.APPLIED,
                    files = request.fileKinds(),
                )
            },
            autoStartWorker = false,
            onJobUpdatedForTest = { job -> completed[job.jobId] = job.phase },
        )

        val first = coordinator.submit(batchWithBiliConfigToken("first"))
        coordinator.markRunningForTest(first.jobId)
        val second = coordinator.submit(batchWithBotToken("second"))
        coordinator.completeCurrentBatchForTest()

        assertEquals(WebUiConfigHotReloadPhase.APPLIED, completed[first.jobId])
        assertEquals(WebUiConfigHotReloadPhase.QUEUED, coordinator.readJob(second.jobId)?.phase)
    }

    /**
     * 停机可能发生在下一批已离开 pending 但尚未进入 SAVING 的 debounce 窗口，此时也必须给前端留下终态。
     */
    @Test
    fun `shutdown should fail queued batch already taken for debounce`() = runBlocking {
        val firstApplyEntered = CompletableDeferred<Unit>()
        val releaseFirstApply = CompletableDeferred<Unit>()
        val debounceEntered = CompletableDeferred<Unit>()
        val coordinator = WebUiConfigHotReloadCoordinator(
            nowMillis = { 100L },
            delayMillis = {
                debounceEntered.complete(Unit)
                CompletableDeferred<Unit>().await()
            },
            applyAction = { jobId, request ->
                firstApplyEntered.complete(Unit)
                releaseFirstApply.await()
                WebUiConfigHotReloadJobDto(
                    jobId = jobId,
                    phase = WebUiConfigHotReloadPhase.APPLIED,
                    files = request.fileKinds(),
                )
            },
        )

        coordinator.submit(batchWithBiliConfigToken("first"))
        firstApplyEntered.await()
        val second = coordinator.submit(batchWithBotToken("second"))
        releaseFirstApply.complete(Unit)
        debounceEntered.await()
        val closeJob = launch { coordinator.closeForShutdown(timeoutMs = 100L) }
        closeJob.join()

        assertEquals(WebUiConfigHotReloadPhase.FAILED, coordinator.readJob(second.jobId)?.phase)
    }

    /**
     * worker 取消超时后即使稍后返回成功，也不能覆盖停机已经写入的 FAILED 终态。
     */
    @Test
    fun `shutdown failed terminal state should survive late worker completion`() = runBlocking {
        val applyEntered = CountDownLatch(1)
        val releaseApply = CountDownLatch(1)
        val coordinator = WebUiConfigHotReloadCoordinator(
            nowMillis = { 100L },
            delayMillis = {},
            applyAction = { jobId, request ->
                applyEntered.countDown()
                releaseApply.await()
                WebUiConfigHotReloadJobDto(
                    jobId = jobId,
                    phase = WebUiConfigHotReloadPhase.APPLIED,
                    files = request.fileKinds(),
                )
            },
        )

        val job = coordinator.submit(batchWithBiliConfigToken("late"))
        assertTrue(applyEntered.await(1, TimeUnit.SECONDS))
        coordinator.closeForShutdown(timeoutMs = 10L)
        releaseApply.countDown()
        coordinator.drainForTest()

        assertEquals(WebUiConfigHotReloadPhase.FAILED, coordinator.readJob(job.jobId)?.phase)
    }

    /**
     * worker 执行保存动作前必须先发布 SAVING 阶段，前端才能区分排队、写盘和应用过程。
     */
    @Test
    fun `running batch should publish saving phase before final result`() {
        val phases = mutableListOf<WebUiConfigHotReloadPhase>()
        val coordinator = WebUiConfigHotReloadCoordinator(
            nowMillis = { 100L },
            delayMillis = {},
            applyAction = { jobId, request ->
                WebUiConfigHotReloadJobDto(
                    jobId = jobId,
                    phase = WebUiConfigHotReloadPhase.APPLIED,
                    files = request.fileKinds(),
                )
            },
            autoStartWorker = false,
            onJobUpdatedForTest = { job -> phases += job.phase },
        )

        coordinator.submit(batchWithBiliConfigToken("phase"))
        coordinator.drainForTest()

        assertTrue(WebUiConfigHotReloadPhase.SAVING in phases)
        assertEquals(WebUiConfigHotReloadPhase.APPLIED, phases.last())
    }

    /**
     * 批量 prepare 必须先收集所有文件的校验结果；任一文件失败时不得暴露可持久化候选批次。
     */
    @Test
    fun `batch prepare should validate all files before persisting`() {
        val service = WebUiConfigBatchSaveService(
            prepareBiliConfig = { _, _ -> successPreparedBiliConfig("bili-token") },
            prepareBotConfig = { _, _ -> botValidationFailure("bad bot") },
        )

        val result = service.prepare(
            WebUiConfigBatchSaveRequestDto(
                biliConfig = WebUiBiliConfigWriteRequestDto(snapshotToken = "old-bili"),
                botConfig = WebUiBotConfigWriteRequestDto(
                    snapshotToken = "old-bot",
                    platformType = "ONEBOT11",
                    adapter = "onebot11",
                    oneBot11Host = "127.0.0.1",
                    oneBot11Port = 3001,
                ),
            ),
            current = testCurrentWebUiConfigSnapshot(),
        )

        assertFalse(result.success)
        assertNull(result.prepared)
        assertEquals(WebUiConfigFileKind.BOT_CONFIG, result.outcomes.last().file)
    }

    /**
     * prepare 成功时只返回候选快照，不调用任何 owner save，后续持久化由协调器统一调度。
     */
    @Test
    fun `prepared batch should expose candidate snapshots without writing files`() {
        val service = WebUiConfigBatchSaveService(
            prepareBiliConfig = { _, _ -> successPreparedBiliConfig("bili-token") },
            prepareBotConfig = { _, _ -> successPreparedBotConfig("bot-token") },
        )

        val result = service.prepare(
            WebUiConfigBatchSaveRequestDto(
                biliConfig = WebUiBiliConfigWriteRequestDto(snapshotToken = "old-bili"),
                botConfig = WebUiBotConfigWriteRequestDto(
                    snapshotToken = "old-bot",
                    platformType = "ONEBOT11",
                    adapter = "onebot11",
                    oneBot11Host = "127.0.0.1",
                    oneBot11Port = 3001,
                ),
            ),
            current = testCurrentWebUiConfigSnapshot(),
        )

        assertTrue(result.success)
        assertNotNull(result.prepared?.candidateSnapshot)
        assertEquals(listOf(WebUiConfigFileKind.BILI_CONFIG, WebUiConfigFileKind.BOT_CONFIG), result.outcomes.map { it.file })
    }

    /**
     * 热重载 apply 服务在 prepare 失败时不得触碰任何 owner 写盘或运行态 apply。
     */
    @Test
    fun `hot reload apply should skip persistence and runtime apply when prepare fails`() = runBlocking {
        var persistCalls = 0
        var applyCalls = 0
        val service = WebUiConfigHotReloadApplyService(
            batchSaveService = WebUiConfigBatchSaveService(
                prepareBiliConfig = { _, _ -> PreparedBiliConfigWrite(config = null, result = validationResult("bad config")) },
            ),
            captureRuntimeSnapshot = { testRuntimeSnapshot() },
            persistBiliConfig = {
                persistCalls += 1
                true
            },
            applyRuntime = {
                applyCalls += 1
                WebUiReloadPlan(restartRequired = false)
            },
        )

        val job = service.apply("job-prepare-failed", batchWithBiliConfigToken("old-bili"))

        assertEquals(WebUiConfigHotReloadPhase.FAILED, job.phase)
        assertEquals(0, persistCalls)
        assertEquals(0, applyCalls)
    }

    /**
     * 热重载 apply 成功时必须先持久化候选快照，再把同一候选代际交给 runtime applier。
     */
    @Test
    fun `hot reload apply should persist candidate and apply runtime generation`() = runBlocking {
        var persistedAdmin: Long? = null
        var appliedGeneration: RuntimeConfigGeneration? = null
        val service = WebUiConfigHotReloadApplyService(
            batchSaveService = WebUiConfigBatchSaveService(
                prepareBiliConfig = { _, _ -> successPreparedBiliConfig("bili-token") },
            ),
            captureRuntimeSnapshot = { testRuntimeSnapshot() },
            persistBiliConfig = { snapshot ->
                persistedAdmin = snapshot.admin
                true
            },
            applyRuntime = { generation ->
                appliedGeneration = generation
                WebUiReloadPlan(restartRequired = false)
            },
        )

        val job = service.apply("job-applied", batchWithBiliConfigToken("old-bili"))

        assertEquals(WebUiConfigHotReloadPhase.APPLIED, job.phase)
        assertEquals(2L, persistedAdmin)
        assertEquals(2L, appliedGeneration?.candidateSnapshot?.biliConfig?.admin)
        assertEquals(setOf(WebUiConfigFileKind.BILI_CONFIG), appliedGeneration?.changedFiles)
    }

    /**
     * WebUI 监听地址变化的跳转地址由 runtime applier 判定，apply 服务必须把它透传给前端 job。
     */
    @Test
    fun `hot reload apply should expose webui redirect url from runtime plan`() = runBlocking {
        val service = WebUiConfigHotReloadApplyService(
            batchSaveService = WebUiConfigBatchSaveService(
                prepareBotConfig = { _, _ -> successPreparedBotConfig("bot-token") },
            ),
            captureRuntimeSnapshot = { testRuntimeSnapshot() },
            persistBotConfig = { true },
            applyRuntime = {
                WebUiReloadPlan(restartRequired = true, webUiRedirectUrl = "http://127.0.0.1:18081/")
            },
        )

        val job = service.apply("job-webui-redirect", batchWithBotToken("old-bot"))

        assertEquals(WebUiConfigHotReloadPhase.APPLIED, job.phase)
        assertEquals("http://127.0.0.1:18081/", job.webUiRedirectUrl)
    }

    /**
     * 已持久化的订阅数据刷新只能声明 BiliData 变更，不能让 runtime applier 误触发平台或 BiliConfig 刷新。
     */
    @Test
    fun `persisted BiliData reload should pass only BiliData file boundary to runtime apply`() = runBlocking {
        var appliedGeneration: RuntimeConfigGeneration? = null
        val service = WebUiConfigHotReloadApplyService(
            captureRuntimeSnapshot = { testRuntimeSnapshot() },
            applyRuntime = { generation ->
                appliedGeneration = generation
                WebUiReloadPlan(restartRequired = false)
            },
        )

        val job = service.applyAlreadyPersistedBiliData("job-data-refresh")

        assertEquals(WebUiConfigHotReloadPhase.APPLIED, job.phase)
        assertEquals(setOf(WebUiConfigFileKind.BILI_DATA), appliedGeneration?.changedFiles)
    }

    /**
     * 多文件候选写盘发生半提交时，服务必须回写已成功保存的旧快照并阻止运行态 apply。
     */
    @Test
    fun `hot reload apply should rollback already persisted files when later persistence fails`() = runBlocking {
        val persistedAdmins = mutableListOf<Long>()
        var applyCalls = 0
        val service = WebUiConfigHotReloadApplyService(
            batchSaveService = WebUiConfigBatchSaveService(
                prepareBiliConfig = { _, _ -> successPreparedBiliConfig("bili-token") },
                prepareBotConfig = { _, _ -> successPreparedBotConfig("bot-token") },
            ),
            captureRuntimeSnapshot = { testRuntimeSnapshot() },
            persistBiliConfig = { snapshot ->
                persistedAdmins += snapshot.admin
                true
            },
            persistBotConfig = { false },
            applyRuntime = {
                applyCalls += 1
                WebUiReloadPlan(restartRequired = false)
            },
        )

        val job = service.apply(
            "job-persistence-failed",
            WebUiConfigBatchSaveRequestDto(
                biliConfig = WebUiBiliConfigWriteRequestDto(snapshotToken = "old-bili"),
                botConfig = WebUiBotConfigWriteRequestDto(
                    snapshotToken = "old-bot",
                    platformType = "ONEBOT11",
                    adapter = "onebot11",
                    oneBot11Host = "127.0.0.1",
                    oneBot11Port = 3001,
                ),
            ),
        )

        assertEquals(WebUiConfigHotReloadPhase.FAILED, job.phase)
        assertEquals(listOf(2L, 1L), persistedAdmins)
        assertEquals(0, applyCalls)
    }

    /**
     * 运行态 apply 失败后应先恢复旧内存态，再尝试把已落盘候选文件回滚到旧快照。
     */
    @Test
    fun `hot reload apply should restore memory and rollback disk when runtime apply fails`() = runBlocking {
        val persistedAdmins = mutableListOf<Long>()
        var restoredAdmin: Long? = null
        val service = WebUiConfigHotReloadApplyService(
            batchSaveService = WebUiConfigBatchSaveService(
                prepareBiliConfig = { _, _ -> successPreparedBiliConfig("bili-token") },
            ),
            captureRuntimeSnapshot = { testRuntimeSnapshot() },
            persistBiliConfig = { snapshot ->
                persistedAdmins += snapshot.admin
                true
            },
            restoreRuntime = { snapshot ->
                restoredAdmin = snapshot.biliConfig.admin
            },
            applyRuntime = {
                error("candidate apply failed")
            },
        )

        val job = service.apply("job-apply-failed", batchWithBiliConfigToken("old-bili"))

        assertEquals(WebUiConfigHotReloadPhase.FAILED, job.phase)
        assertEquals(1L, restoredAdmin)
        assertEquals(listOf(2L, 1L), persistedAdmins)
        assertTrue(job.message.contains("old runtime is still working"))
    }

    /**
     * 测试用 request builder 只填快照 token，让队列测试关注文件归并而非字段校验。
     */
    private fun batchWithBiliConfigToken(token: String): WebUiConfigBatchSaveRequestDto {
        return WebUiConfigBatchSaveRequestDto(
            biliConfig = WebUiBiliConfigWriteRequestDto(snapshotToken = token),
        )
    }

    /**
     * BiliData 测试 payload 只填快照 token，让队列测试聚焦在提交类型归并。
     */
    private fun batchWithBiliDataToken(token: String): WebUiConfigBatchSaveRequestDto {
        return WebUiConfigBatchSaveRequestDto(
            biliData = top.bilibili.webui.model.WebUiBiliDataWriteRequestDto(
                snapshotToken = token,
                linkParseBlacklistContacts = emptyList(),
            ),
        )
    }

    /**
     * bot.yml 测试 payload 使用合法默认值，避免队列测试被平台字段默认值干扰。
     */
    private fun batchWithBotToken(token: String): WebUiConfigBatchSaveRequestDto {
        return WebUiConfigBatchSaveRequestDto(
            botConfig = WebUiBotConfigWriteRequestDto(
                snapshotToken = token,
                platformType = "ONEBOT11",
                adapter = "onebot11",
                oneBot11Host = "127.0.0.1",
                oneBot11Port = 3001,
            ),
        )
    }

    /**
     * 测试快照使用全新对象，避免批量 prepare 测试依赖真实全局配置单例。
     */
    private fun testCurrentWebUiConfigSnapshot(): WebUiConfigCandidateSnapshot {
        return WebUiConfigCandidateSnapshot(
            biliConfig = BiliConfig(admin = 1L),
            biliData = BiliDataWrapper(),
            botConfig = BotConfig(),
        )
    }

    /**
     * 运行态测试快照和 WebUI candidate 测试快照保持相同基线，便于断言候选变化。
     */
    private fun testRuntimeSnapshot(): RuntimeConfigSnapshot {
        return RuntimeConfigSnapshot(
            biliConfig = BiliConfig(admin = 1L),
            biliData = BiliDataWrapper(),
            botConfig = BotConfig(),
        )
    }

    /**
     * 成功的 BiliConfig prepare 返回候选对象和成功结果，模拟 facade dry-run 但不触发写盘。
     */
    private fun successPreparedBiliConfig(token: String): PreparedBiliConfigWrite {
        return PreparedBiliConfigWrite(
            config = BiliConfig(admin = 2L, adminContact = "onebot11:private:1"),
            result = saveResult(token),
        )
    }

    /**
     * 成功的 bot.yml prepare 返回候选对象，用于验证批量服务组装跨文件候选快照。
     */
    private fun successPreparedBotConfig(token: String): PreparedBotConfigWrite {
        return PreparedBotConfigWrite(
            config = BotConfig(),
            result = saveResult(token),
        )
    }

    /**
     * 校验失败结果模拟单文件 facade dry-run 拒绝，使批量服务停在未持久化状态。
     */
    private fun botValidationFailure(message: String): PreparedBotConfigWrite {
        return PreparedBotConfigWrite(
            config = null,
            result = validationResult(message),
        )
    }

    /**
     * 校验失败结果模拟单文件 facade dry-run 拒绝，使批量服务停在未持久化状态。
     */
    private fun validationResult(message: String): WebUiConfigSaveResultDto {
        return WebUiConfigSaveResultDto(
            success = false,
            persisted = false,
            conflictDetected = false,
            validationErrors = listOf(message),
            effectiveLevel = top.bilibili.webui.model.WebUiSaveEffectLevel.REJECTED_VALIDATION,
            recommendedAction = top.bilibili.webui.model.WebUiRecommendedAction.FIX_VALIDATION_ERRORS,
            snapshotToken = "",
            message = message,
        )
    }

    /**
     * 测试成功结果固定为未持久化的 dry-run 语义，便于区分 prepare 和 persist 阶段。
     */
    private fun saveResult(token: String): WebUiConfigSaveResultDto {
        return WebUiConfigSaveResultDto(
            success = true,
            persisted = false,
            conflictDetected = false,
            validationErrors = emptyList(),
            effectiveLevel = top.bilibili.webui.model.WebUiSaveEffectLevel.RELOAD_REQUIRED,
            recommendedAction = top.bilibili.webui.model.WebUiRecommendedAction.RELOAD_CONFIG,
            snapshotToken = token,
            message = "prepared",
        )
    }
}
