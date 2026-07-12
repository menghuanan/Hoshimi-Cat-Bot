package top.bilibili.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class QrLoginCoordinatorTest {
    /** 扫码会话必须先返回二维码，再按 B 站轮询结果进入确认和成功状态。 */
    @Test
    fun `qr login should publish scan confirmation and successful terminal state`() = runBlocking {
        val scanned = CompletableDeferred<Unit>()
        val continuePolling = CompletableDeferred<Unit>()
        var pollCount = 0
        var committedCallbackUrl: String? = null
        val coordinator = QrLoginCoordinator(
            clockMillis = { 1_000L },
            requestQr = { QrLoginPayload("https://qr.example/login", "qr-key") },
            renderQr = { byteArrayOf(1, 2, 3) },
            queryStatus = {
                pollCount++
                if (pollCount == 1) {
                    scanned.complete(Unit)
                    QrLoginPollPayload(86090, null, "scanned")
                } else {
                    QrLoginPollPayload(0, "https://callback.example/?SESSDATA=value&bili_jct=csrf", "ok")
                }
            },
            commitCallback = { callbackUrl -> committedCallbackUrl = callbackUrl; true },
            postCommitRefresh = {},
            pollDelay = {
                // 第一次轮询后暂停，确保测试能观察到待确认状态，而不是只看到终态。
                if (pollCount == 1) continuePolling.await()
            },
            workerLauncher = { block -> launch(start = CoroutineStart.DEFAULT) { block() } },
        )

        val started = assertIs<QrLoginStartResult.Started>(coordinator.start("webui"))
        assertEquals(QrLoginPhase.WAITING_FOR_SCAN, started.snapshot.phase)
        assertContentEquals(byteArrayOf(1, 2, 3), started.qrImageBytes)

        scanned.await()
        assertEquals(QrLoginPhase.WAITING_FOR_CONFIRMATION, coordinator.snapshot(started.snapshot.sessionId)?.phase)
        continuePolling.complete(Unit)

        val terminal = coordinator.awaitTerminal(started.snapshot.sessionId)
        assertEquals(QrLoginPhase.SUCCEEDED, terminal?.phase)
        assertEquals("https://callback.example/?SESSDATA=value&bili_jct=csrf", committedCallbackUrl)
    }

    /** 命令端或 WebUI 已占用全局会话时，后续创建只能得到剩余时间冲突。 */
    @Test
    fun `only one global qr login session should be active`() = runBlocking {
        val keepPolling = CompletableDeferred<Unit>()
        val coordinator = QrLoginCoordinator(
            clockMillis = { 10_000L },
            requestQr = { QrLoginPayload("https://qr.example/login", "qr-key") },
            renderQr = { byteArrayOf(9) },
            queryStatus = { keepPolling.await(); QrLoginPollPayload(86101, null, "waiting") },
            commitCallback = { true },
            pollDelay = {},
            workerLauncher = { block -> launch { block() } },
        )

        val first = assertIs<QrLoginStartResult.Started>(coordinator.start("command:group:1"))
        val conflict = assertIs<QrLoginStartResult.Conflict>(coordinator.start("webui"))

        assertEquals(180L, conflict.remainingSeconds)
        assertEquals(QrLoginCancelResult.CANCELLED, coordinator.cancel(first.snapshot.sessionId))
        keepPolling.complete(Unit)
    }

    /** 普通等待态允许取消，但凭据提交开始后必须拒绝取消并完成当前提交。 */
    @Test
    fun `cancel should stop waiting sessions but not interrupt credential commit`() = runBlocking {
        val waitingGate = CompletableDeferred<Unit>()
        val waitingCoordinator = QrLoginCoordinator(
            requestQr = { QrLoginPayload("https://qr.example/waiting", "waiting-key") },
            renderQr = { byteArrayOf(4) },
            queryStatus = { waitingGate.await(); QrLoginPollPayload(86101, null, "waiting") },
            commitCallback = { true },
            pollDelay = {},
            workerLauncher = { block -> launch { block() } },
        )
        val waiting = assertIs<QrLoginStartResult.Started>(waitingCoordinator.start("webui"))

        assertEquals(QrLoginCancelResult.CANCELLED, waitingCoordinator.cancel(waiting.snapshot.sessionId))
        assertEquals(QrLoginPhase.CANCELLED, waitingCoordinator.awaitTerminal(waiting.snapshot.sessionId)?.phase)
        waitingGate.complete(Unit)

        val commitStarted = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val committingCoordinator = QrLoginCoordinator(
            requestQr = { QrLoginPayload("https://qr.example/commit", "commit-key") },
            renderQr = { byteArrayOf(5) },
            queryStatus = { QrLoginPollPayload(0, "https://callback.example/success", "ok") },
            commitCallback = {
                commitStarted.countDown()
                check(releaseCommit.await(5, TimeUnit.SECONDS))
                true
            },
            postCommitRefresh = {},
            pollDelay = {},
            workerLauncher = { block -> launch(Dispatchers.Default) { block() } },
        )
        val committing = assertIs<QrLoginStartResult.Started>(committingCoordinator.start("webui"))
        assertTrue(commitStarted.await(5, TimeUnit.SECONDS))

        assertEquals(QrLoginPhase.COMMITTING, committingCoordinator.snapshot(committing.snapshot.sessionId)?.phase)
        assertEquals(QrLoginCancelResult.COMMITTING, committingCoordinator.cancel(committing.snapshot.sessionId))
        releaseCommit.countDown()
        assertTrue(committingCoordinator.awaitTerminal(committing.snapshot.sessionId)?.phase == QrLoginPhase.SUCCEEDED)
    }

    /** 提交阶段即使跨过二维码 TTL 也必须继续独占全局会话，直到凭据提交完整收口。 */
    @Test
    fun `committing session should remain exclusive after qr ttl`() = runBlocking {
        var now = 1_000L
        val commitStarted = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val coordinator = QrLoginCoordinator(
            clockMillis = { now },
            requestQr = { QrLoginPayload("https://qr.example/commit", "commit-key") },
            renderQr = { byteArrayOf(5) },
            queryStatus = { QrLoginPollPayload(0, "https://callback.example/success", "ok") },
            commitCallback = {
                commitStarted.countDown()
                check(releaseCommit.await(5, TimeUnit.SECONDS))
                true
            },
            postCommitRefresh = {},
            pollDelay = {},
            workerLauncher = { block -> launch(Dispatchers.Default) { block() } },
        )
        val committing = assertIs<QrLoginStartResult.Started>(coordinator.start("command"))
        assertTrue(commitStarted.await(5, TimeUnit.SECONDS))
        now = committing.snapshot.expiresAtEpochMillis + 1

        val conflict = assertIs<QrLoginStartResult.Conflict>(coordinator.start("webui"))

        // 提交态冲突不得伪造二维码租约剩余 1 秒，调用方需要得到明确 phase 且无 retryAfter。
        assertTrue(conflict.toString().contains("phase=COMMITTING"))
        assertTrue(conflict.toString().contains("remainingSeconds=null"))
        assertEquals(QrLoginPhase.COMMITTING, coordinator.snapshot(committing.snapshot.sessionId)?.phase)
        releaseCommit.countDown()
        assertEquals(QrLoginPhase.SUCCEEDED, coordinator.awaitTerminal(committing.snapshot.sessionId)?.phase)
    }

    /** 核心凭据提交成功后必须先释放登录入口，附加刷新阻塞不能继续占用全局互斥。 */
    @Test
    fun `successful core commit should release session before post commit refresh`() = runBlocking {
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        var requestCount = 0
        val coordinator = QrLoginCoordinator(
            requestQr = {
                requestCount++
                QrLoginPayload("https://qr.example/$requestCount", "key-$requestCount")
            },
            renderQr = { byteArrayOf(5) },
            queryStatus = { key ->
                if (key == "key-1") {
                    QrLoginPollPayload(0, "https://callback.example/success", "ok")
                } else {
                    CompletableDeferred<Unit>().await()
                    null
                }
            },
            commitCallback = { true },
            postCommitRefresh = {
                refreshStarted.complete(Unit)
                releaseRefresh.await()
            },
            pollDelay = {},
            workerLauncher = { block -> launch { block() } },
        )
        val first = assertIs<QrLoginStartResult.Started>(coordinator.start("command"))

        assertEquals(QrLoginPhase.SUCCEEDED, coordinator.awaitTerminal(first.snapshot.sessionId)?.phase)
        refreshStarted.await()
        val second = assertIs<QrLoginStartResult.Started>(coordinator.start("webui"))

        assertEquals(QrLoginCancelResult.CANCELLED, coordinator.cancel(second.snapshot.sessionId))
        releaseRefresh.complete(Unit)
    }

    /** 停机 drain 必须拒绝新会话、取消等待 worker 并发布 STOPPED 运行快照。 */
    @Test
    fun `shutdown should cancel waiting workers and reject new sessions`() = runBlocking {
        val queryStarted = CompletableDeferred<Unit>()
        val coordinator = QrLoginCoordinator(
            requestQr = { QrLoginPayload("https://qr.example/waiting", "waiting-key") },
            renderQr = { byteArrayOf(4) },
            queryStatus = {
                queryStarted.complete(Unit)
                CompletableDeferred<Unit>().await()
                null
            },
            commitCallback = { true },
            postCommitRefresh = {},
            pollDelay = {},
            workerLauncher = { block -> launch { block() } },
        )
        val started = assertIs<QrLoginStartResult.Started>(coordinator.start("webui"))
        queryStarted.await()

        coordinator.shutdownAndDrain(timeoutMs = 1_000L)

        assertEquals(QrLoginPhase.CANCELLED, coordinator.awaitTerminal(started.snapshot.sessionId)?.phase)
        assertEquals(QrLoginDrainState.STOPPED, coordinator.runtimeSnapshot().drainState)
        assertFalse(coordinator.runtimeSnapshot().acceptingNewSessions)
        assertIs<QrLoginStartResult.Failed>(coordinator.start("command"))
    }

    /** 核心提交 watchdog 超时后保持旧栅栏、进入降级并触发一次恢复动作。 */
    @Test
    fun `commit watchdog should fail closed and request recovery`() = runBlocking {
        val commitStarted = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val watchdogGate = CompletableDeferred<Unit>()
        val recovery = CompletableDeferred<QrLoginCoordinatorRuntimeSnapshot>()
        val coordinator = QrLoginCoordinator(
            requestQr = { QrLoginPayload("https://qr.example/commit", "commit-key") },
            renderQr = { byteArrayOf(5) },
            queryStatus = { QrLoginPollPayload(0, "https://callback.example/success", "ok") },
            commitCallback = {
                commitStarted.countDown()
                check(releaseCommit.await(5, TimeUnit.SECONDS))
                true
            },
            postCommitRefresh = {},
            pollDelay = {},
            commitWatchdogDelay = { watchdogGate.await() },
            onDrainTimeout = { snapshot -> recovery.complete(snapshot) },
            workerLauncher = { block -> launch(Dispatchers.Default) { block() } },
        )
        val started = assertIs<QrLoginStartResult.Started>(coordinator.start("command"))
        assertTrue(commitStarted.await(5, TimeUnit.SECONDS))

        watchdogGate.complete(Unit)
        val timedOut = recovery.await()

        assertEquals(QrLoginPhase.COMMITTING, timedOut.activePhase)
        assertEquals(QrLoginDrainState.DRAIN_TIMED_OUT, timedOut.drainState)
        assertEquals(1L, timedOut.commitDrainTimeoutCount)
        assertFalse(timedOut.acceptingNewSessions)
        assertIs<QrLoginStartResult.Failed>(coordinator.start("webui"))

        releaseCommit.countDown()
        assertEquals(QrLoginPhase.SUCCEEDED, coordinator.awaitTerminal(started.snapshot.sessionId)?.phase)
    }

    /** 提交态停机必须在 drain 窗口内等待核心提交完成，不能通过取消 worker 截断凭据切换。 */
    @Test
    fun `shutdown should drain committing worker before stopping`() = runBlocking {
        val commitStarted = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val coordinator = QrLoginCoordinator(
            requestQr = { QrLoginPayload("https://qr.example/commit", "commit-key") },
            renderQr = { byteArrayOf(5) },
            queryStatus = { QrLoginPollPayload(0, "https://callback.example/success", "ok") },
            commitCallback = {
                commitStarted.countDown()
                check(releaseCommit.await(5, TimeUnit.SECONDS))
                true
            },
            postCommitRefresh = {},
            pollDelay = {},
            workerLauncher = { block -> launch(Dispatchers.Default) { block() } },
        )
        val started = assertIs<QrLoginStartResult.Started>(coordinator.start("command"))
        assertTrue(commitStarted.await(5, TimeUnit.SECONDS))

        val drained = CompletableDeferred<Unit>()
        val drainJob = launch {
            coordinator.shutdownAndDrain(timeoutMs = 2_000L)
            drained.complete(Unit)
        }
        delay(50L)
        assertFalse(drained.isCompleted)

        releaseCommit.countDown()
        drained.await()
        drainJob.join()

        assertEquals(QrLoginPhase.SUCCEEDED, coordinator.awaitTerminal(started.snapshot.sessionId)?.phase)
        assertEquals(QrLoginDrainState.STOPPED, coordinator.runtimeSnapshot().drainState)
    }

    /** 超过 TTL 的卡住 worker 不得继续占用全局入口，迟到结果也不得提交到新会话。 */
    @Test
    fun `expired active session should be replaced without allowing stale commit`() = runBlocking {
        var now = 1_000L
        val staleQueryStarted = CompletableDeferred<Unit>()
        val releaseStaleQuery = CompletableDeferred<Unit>()
        var requestCount = 0
        var commitCount = 0
        val coordinator = QrLoginCoordinator(
            clockMillis = { now },
            requestQr = {
                requestCount++
                QrLoginPayload("https://qr.example/$requestCount", "key-$requestCount")
            },
            renderQr = { byteArrayOf(7) },
            queryStatus = { key ->
                if (key == "key-1") {
                    staleQueryStarted.complete(Unit)
                    withContext(NonCancellable) { releaseStaleQuery.await() }
                    QrLoginPollPayload(0, "https://callback.example/stale", "ok")
                } else {
                    CompletableDeferred<Unit>().await()
                    null
                }
            },
            commitCallback = { commitCount++; true },
            pollDelay = {},
            workerLauncher = { block -> launch { block() } },
        )
        val first = assertIs<QrLoginStartResult.Started>(coordinator.start("command"))
        staleQueryStarted.await()
        now = first.snapshot.expiresAtEpochMillis + 1

        val secondResult = coordinator.start("webui")
        releaseStaleQuery.complete(Unit)
        val second = assertIs<QrLoginStartResult.Started>(secondResult)

        assertEquals(QrLoginPhase.EXPIRED, coordinator.awaitTerminal(first.snapshot.sessionId)?.phase)
        assertEquals(0, commitCount)
        assertEquals(QrLoginCancelResult.CANCELLED, coordinator.cancel(second.snapshot.sessionId))
    }

    /** 取码失败和凭据落盘失败都要释放全局占用，并返回稳定失败终态。 */
    @Test
    fun `upstream and persistence failures should release the global session`() = runBlocking {
        var requestCount = 0
        val coordinator = QrLoginCoordinator(
            requestQr = {
                requestCount++
                if (requestCount == 1) null else QrLoginPayload("https://qr.example/retry", "retry-key")
            },
            renderQr = { byteArrayOf(8) },
            queryStatus = { QrLoginPollPayload(0, "https://callback.example/retry", "ok") },
            commitCallback = { false },
            pollDelay = {},
            workerLauncher = { block -> launch { block() } },
        )

        assertIs<QrLoginStartResult.Failed>(coordinator.start("webui"))
        val retry = assertIs<QrLoginStartResult.Started>(coordinator.start("webui"))
        assertEquals(QrLoginPhase.FAILED, coordinator.awaitTerminal(retry.snapshot.sessionId)?.phase)
        assertIs<QrLoginStartResult.Started>(coordinator.start("command"))
    }

    /** 成功码缺少回调 URL 属于协议失败，不能让 worker 静默退出并遗留活动会话。 */
    @Test
    fun `successful poll code without callback url should fail the session`() = runBlocking {
        val queried = CompletableDeferred<Unit>()
        val coordinator = QrLoginCoordinator(
            requestQr = { QrLoginPayload("https://qr.example/missing-callback", "missing-key") },
            renderQr = { byteArrayOf(6) },
            queryStatus = { queried.complete(Unit); QrLoginPollPayload(0, null, "ok") },
            commitCallback = { true },
            pollDelay = {},
            workerLauncher = { block -> launch { block() } },
        )
        val started = assertIs<QrLoginStartResult.Started>(coordinator.start("webui"))
        queried.await()

        assertEquals(QrLoginPhase.FAILED, coordinator.snapshot(started.snapshot.sessionId)?.phase)
    }
}
