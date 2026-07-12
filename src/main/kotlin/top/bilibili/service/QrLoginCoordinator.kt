package top.bilibili.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import top.bilibili.api.getLoginQrcode
import top.bilibili.api.loginInfo
import top.bilibili.api.markBiliLoginSucceeded
import top.bilibili.core.BiliBiliBot
import top.bilibili.core.resource.BusinessLifecycleManager
import top.bilibili.core.resource.ResourceStrictness
import top.bilibili.draw.loginQrCodeBytes
import top.bilibili.initTagid
import java.util.UUID
import kotlin.math.ceil

/**
 * 二维码登录阶段只暴露平台中立状态，调用方不得读取 B 站二维码 key 或回调凭据。
 */
enum class QrLoginPhase {
    WAITING_FOR_SCAN,
    WAITING_FOR_CONFIRMATION,
    COMMITTING,
    SUCCEEDED,
    EXPIRED,
    FAILED,
    CANCELLED,
}

/**
 * 登录会话快照只包含展示和协调所需字段，可安全映射到 WebUI DTO。
 */
data class QrLoginSessionSnapshot(
    val sessionId: String,
    val phase: QrLoginPhase,
    val expiresAtEpochMillis: Long,
    val message: String,
)

/**
 * B 站取码结果只在协调器内部流转，禁止直接映射到浏览器响应。
 */
data class QrLoginPayload(
    val url: String,
    val qrcodeKey: String,
)

/**
 * B 站轮询结果压缩为协调器需要的字段，避免状态机依赖完整 vendor 响应。
 */
data class QrLoginPollPayload(
    val code: Int?,
    val callbackUrl: String?,
    val message: String?,
)

/**
 * 创建结果显式区分成功、全局占用和取码失败，便于命令端与 HTTP 层分别映射。
 */
sealed interface QrLoginStartResult {
    data class Started(
        val snapshot: QrLoginSessionSnapshot,
        val qrImageBytes: ByteArray,
        val fallbackUrl: String,
    ) : QrLoginStartResult

    /** 提交态冲突不携带伪造 retryAfter，等待态才返回二维码租约剩余秒数。 */
    data class Conflict(
        val phase: QrLoginPhase,
        val remainingSeconds: Long?,
    ) : QrLoginStartResult

    data class Failed(val message: String) : QrLoginStartResult
}

/**
 * 取消结果区分可取消、提交中和查无会话，防止 HTTP 层猜测当前状态。
 */
enum class QrLoginCancelResult {
    CANCELLED,
    COMMITTING,
    ALREADY_TERMINAL,
    NOT_FOUND,
}

/** 协调器 drain 状态独立于单个登录 phase，供资源分区和守护进程判断停机安全性。 */
enum class QrLoginDrainState {
    RUNNING,
    DRAINING,
    DRAIN_TIMED_OUT,
    STOPPED,
}

/** 运行快照只暴露生命周期计数与阶段年龄，不包含二维码或登录凭据。 */
data class QrLoginCoordinatorRuntimeSnapshot(
    val acceptingNewSessions: Boolean,
    val activePhase: QrLoginPhase?,
    val phaseAgeMillis: Long?,
    val drainState: QrLoginDrainState,
    val activeWorkerCount: Int,
    val commitDrainTimeoutCount: Long,
    val workerDrainTimeoutCount: Long,
    val postCommitRefreshTimeoutCount: Long,
    val degradedReason: String?,
)

/**
 * 共享二维码登录协调器是命令端与 WebUI 的唯一会话所有者，负责全局互斥和提交边界。
 */
class QrLoginCoordinator(
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val requestQr: suspend () -> QrLoginPayload? = {
        client.getLoginQrcode()?.let { payload ->
            payload.qrcodeKey?.takeIf(String::isNotBlank)?.let { key ->
                QrLoginPayload(url = payload.url, qrcodeKey = key)
            }
        }
    },
    private val renderQr: suspend (String) -> ByteArray = ::loginQrCodeBytes,
    private val queryStatus: suspend (String) -> QrLoginPollPayload? = { qrcodeKey ->
        client.loginInfo(qrcodeKey)?.let { payload ->
            QrLoginPollPayload(payload.code, payload.url, payload.message)
        }
    },
    private val commitCallback: (String) -> Boolean = ::commitQrLoginCallback,
    private val postCommitRefresh: suspend () -> Unit = ::initTagid,
    private val pollDelay: suspend () -> Unit = { delay(POLL_INTERVAL_MS) },
    private val postCommitRefreshTimeoutMs: Long = POST_COMMIT_REFRESH_TIMEOUT_MS,
    private val commitDrainTimeoutMs: Long = COMMIT_DRAIN_TIMEOUT_MS,
    private val commitWatchdogDelay: suspend () -> Unit = { delay(commitDrainTimeoutMs) },
    private val onDrainTimeout: (QrLoginCoordinatorRuntimeSnapshot) -> Unit = { snapshot ->
        BiliBiliBot.requestControlledRestart(
            "qr-login-drain-timeout phase=${snapshot.activePhase} ageMs=${snapshot.phaseAgeMillis}",
        )
    },
    private val workerLauncher: (suspend () -> Unit) -> Job = { block ->
        BiliBiliBot.launch {
            BusinessLifecycleManager.run(
                owner = "QrLoginCoordinator",
                operation = "qr-login",
                strictness = ResourceStrictness.RELAXED_LONG_RUNNING,
            ) {
                block()
            }
        }
    },
) {
    private val stateLock = Any()
    /** 当前全局活动二维码登录，代际用于阻止旧流程的迟到成功提交。 */
    private var activeSession: ActiveQrLoginSession? = null
    private val terminalSnapshots = linkedMapOf<String, RetainedQrLoginSnapshot>()
    private val workerJobs = linkedSetOf<Job>()
    private var acceptingNewSessions = true
    private var drainState = QrLoginDrainState.RUNNING
    private var commitDrainTimeoutCount = 0L
    private var workerDrainTimeoutCount = 0L
    private var postCommitRefreshTimeoutCount = 0L
    private var degradedReason: String? = null

    /** 当前活动记录持有 B 站敏感字段；进入终态后整条记录必须被释放。 */
    private data class ActiveQrLoginSession(
        val sessionId: String,
        val requester: String,
        val expiresAtEpochMillis: Long,
        val qrcodeKey: String? = null,
        val loginUrl: String? = null,
        val phase: QrLoginPhase? = null,
        val phaseStartedAtEpochMillis: Long,
        val terminal: CompletableDeferred<QrLoginSessionSnapshot> = CompletableDeferred(),
        var worker: Job? = null,
        var commitWatchdog: Job? = null,
    )

    /** 终态只保留脱敏快照和完成时间，供短时 HTTP 轮询与命令端收尾读取。 */
    private data class RetainedQrLoginSnapshot(
        val snapshot: QrLoginSessionSnapshot,
        val completedAtEpochMillis: Long,
    )

    /**
     * 创建全局二维码登录；取码和渲染成功后才启动后台轮询 worker。
     */
    suspend fun start(requester: String): QrLoginStartResult {
        val now = clockMillis()
        var expiredWorker: Job? = null
        val reserved = synchronized(stateLock) {
            cleanupTerminalSnapshots(now)
            if (!acceptingNewSessions) {
                return QrLoginStartResult.Failed(degradedReason ?: "登录服务正在停止，请稍后重试")
            }
            val current = activeSession
            if (current != null) {
                // COMMITTING 已越过二维码租约边界，不返回伪造倒计时且必须继续独占到核心提交收口。
                if (current.phase == QrLoginPhase.COMMITTING) {
                    return QrLoginStartResult.Conflict(QrLoginPhase.COMMITTING, remainingSeconds = null)
                }
                if (current.expiresAtEpochMillis > now) {
                    val remainingSeconds = ceil((current.expiresAtEpochMillis - now).coerceAtLeast(1L) / 1_000.0).toLong()
                    return QrLoginStartResult.Conflict(
                        phase = current.phase ?: QrLoginPhase.WAITING_FOR_SCAN,
                        remainingSeconds = remainingSeconds,
                    )
                }
                // 新创建入口主动回收超过 TTL 的卡住会话，避免上游请求长期占用全局登录锁。
                expiredWorker = current.worker
                retainTerminal(current, QrLoginPhase.EXPIRED, "登录超时，请重新登录")
            }
            ActiveQrLoginSession(
                sessionId = UUID.randomUUID().toString(),
                requester = requester,
                expiresAtEpochMillis = now + LOGIN_TTL_MS,
                phaseStartedAtEpochMillis = now,
            ).also { activeSession = it }
        }
        expiredWorker?.cancel()

        val prepared = runCatching {
            val payload = requestQr() ?: error("empty qr login payload")
            val imageBytes = renderQr(payload.url)
            payload to imageBytes
        }.getOrElse {
            finish(reserved.sessionId, QrLoginPhase.FAILED, "获取登录二维码失败")
            return QrLoginStartResult.Failed("获取登录二维码失败")
        }
        val (payload, imageBytes) = prepared
        val ready = synchronized(stateLock) {
            val current = activeSession
            if (current?.sessionId != reserved.sessionId) {
                return QrLoginStartResult.Failed("登录流程已取消")
            }
            current.copy(
                qrcodeKey = payload.qrcodeKey,
                loginUrl = payload.url,
                phase = QrLoginPhase.WAITING_FOR_SCAN,
                phaseStartedAtEpochMillis = clockMillis(),
            ).also { next ->
                next.worker = current.worker
                next.commitWatchdog = current.commitWatchdog
                activeSession = next
            }
        }
        val worker = launchTrackedWorker {
            runPollingLoop(ready.sessionId)
        }
        synchronized(stateLock) {
            activeSession?.takeIf { it.sessionId == ready.sessionId }?.worker = worker
        }
        return QrLoginStartResult.Started(
            snapshot = ready.toSnapshot(),
            qrImageBytes = imageBytes,
            fallbackUrl = payload.url,
        )
    }

    /**
     * 查询当前或短时保留的终态快照；返回值绝不包含二维码 URL、key 或图片字节。
     */
    fun snapshot(sessionId: String): QrLoginSessionSnapshot? {
        return synchronized(stateLock) {
            cleanupTerminalSnapshots(clockMillis())
            activeSession?.takeIf { it.sessionId == sessionId }?.toSnapshot()
                ?: terminalSnapshots[sessionId]?.snapshot
        }
    }

    /**
     * 命令端等待共享会话终态，避免再实现一套 B 站轮询循环。
     */
    suspend fun awaitTerminal(sessionId: String): QrLoginSessionSnapshot? {
        val terminal = synchronized(stateLock) {
            cleanupTerminalSnapshots(clockMillis())
            terminalSnapshots[sessionId]?.snapshot?.let { return it }
            activeSession?.takeIf { it.sessionId == sessionId }?.terminal
        }
        return terminal?.await()
    }

    /** 返回协调器轻量运行快照，phase age 使用当前时钟计算且不会泄露 session ID。 */
    fun runtimeSnapshot(): QrLoginCoordinatorRuntimeSnapshot {
        return synchronized(stateLock) {
            runtimeSnapshotLocked(clockMillis())
        }
    }

    /**
     * 停机时拒绝新会话、取消等待态与附加刷新，并为不可取消核心提交保留有限 drain 窗口。
     *
     * @param timeoutMs worker 完成或取消可使用的最长等待时间
     */
    suspend fun shutdownAndDrain(timeoutMs: Long = COMMIT_DRAIN_TIMEOUT_MS) {
        val (jobs, committingWorker) = synchronized(stateLock) {
            acceptingNewSessions = false
            if (drainState != QrLoginDrainState.DRAIN_TIMED_OUT) {
                drainState = QrLoginDrainState.DRAINING
            }
            val current = activeSession
            val protectedWorker = current?.takeIf { it.phase == QrLoginPhase.COMMITTING }?.worker
            if (current != null && current.phase != QrLoginPhase.COMMITTING) {
                // 等待态在停机入口立即终结，worker 随后在锁外取消并 join。
                retainTerminal(current, QrLoginPhase.CANCELLED, "登录已取消：服务正在停止")
            }
            workerJobs.toList() to protectedWorker
        }

        // 核心提交不能被协程取消截断；其余轮询、watchdog 和附加刷新均可立即取消。
        jobs.filterNot { it === committingWorker }.forEach(Job::cancel)
        val drained = withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
            jobs.joinAll()
            true
        } == true
        if (!drained) {
            val snapshot = markDrainTimedOut("二维码登录 worker 停机 drain 超时(${timeoutMs}ms)")
            onDrainTimeout(snapshot)
            error(snapshot.degradedReason ?: "二维码登录 worker 停机 drain 超时")
        }
        synchronized(stateLock) {
            if (drainState != QrLoginDrainState.DRAIN_TIMED_OUT) {
                drainState = QrLoginDrainState.STOPPED
            }
        }
    }

    /**
     * 取消仍在等待的会话；提交阶段不可取消，防止磁盘态与运行态被拆开。
     */
    fun cancel(sessionId: String): QrLoginCancelResult {
        var workerToCancel: Job? = null
        val result = synchronized(stateLock) {
            cleanupTerminalSnapshots(clockMillis())
            if (terminalSnapshots.containsKey(sessionId)) {
                return@synchronized QrLoginCancelResult.ALREADY_TERMINAL
            }
            val current = activeSession?.takeIf { it.sessionId == sessionId }
                ?: return@synchronized QrLoginCancelResult.NOT_FOUND
            if (current.phase == QrLoginPhase.COMMITTING) {
                return@synchronized QrLoginCancelResult.COMMITTING
            }
            workerToCancel = current.worker
            retainTerminal(current, QrLoginPhase.CANCELLED, "登录已取消")
            QrLoginCancelResult.CANCELLED
        }
        workerToCancel?.cancel()
        return result
    }

    /** 后台循环只解释 B 站状态码，所有提交和终态切换继续经过协调器锁。 */
    private suspend fun runPollingLoop(sessionId: String) {
        try {
            while (clockMillis() < expiresAt(sessionId)) {
                pollDelay()
                val key = synchronized(stateLock) {
                    activeSession?.takeIf { it.sessionId == sessionId }?.qrcodeKey
                } ?: return
                when (val payload = queryStatus(key)) {
                    null -> Unit
                    else -> when (payload.code) {
                        0 -> {
                            val callbackUrl = payload.callbackUrl
                            if (callbackUrl.isNullOrBlank()) {
                                finish(sessionId, QrLoginPhase.FAILED, "登录回调数据无效，请稍后重试")
                                return
                            }
                            if (!transitionToCommitting(sessionId)) return
                            val committed = commitCallback(callbackUrl)
                            if (!committed) {
                                finish(sessionId, QrLoginPhase.FAILED, "登录凭据保存失败，请稍后重试")
                                return
                            }
                            // 核心提交成功即发布终态并释放登录互斥，网络刷新不再占用 COMMITTING。
                            finish(sessionId, QrLoginPhase.SUCCEEDED, "BiliBili 登录成功")
                            runPostCommitRefresh()
                            return
                        }
                        86038 -> {
                            finish(sessionId, QrLoginPhase.EXPIRED, "二维码已失效，请重新登录")
                            return
                        }
                        86090 -> updatePhase(sessionId, QrLoginPhase.WAITING_FOR_CONFIRMATION)
                        86101 -> updatePhase(sessionId, QrLoginPhase.WAITING_FOR_SCAN)
                    }
                }
            }
            finish(sessionId, QrLoginPhase.EXPIRED, "登录超时，请重新登录")
        } catch (_: CancellationException) {
            // 主动取消已经先写入 CANCELLED；根作用域停机取消则在此补齐终态。
            finish(sessionId, QrLoginPhase.CANCELLED, "登录已取消")
        } catch (throwable: Throwable) {
            BiliBiliBot.logger.error("二维码登录轮询失败", throwable)
            finish(sessionId, QrLoginPhase.FAILED, "登录失败，请稍后重试")
        }
    }

    /** 只有仍占用 active 状态的代际能进入不可替换的提交阶段，迟到结果在落盘前被拒绝。 */
    private fun transitionToCommitting(sessionId: String): Boolean {
        val transitioned = synchronized(stateLock) {
            val current = activeSession?.takeIf { it.sessionId == sessionId } ?: return@synchronized false
            activeSession = current.copy(
                phase = QrLoginPhase.COMMITTING,
                phaseStartedAtEpochMillis = clockMillis(),
            ).also { next ->
                next.worker = current.worker
                next.commitWatchdog = current.commitWatchdog
            }
            true
        }
        if (transitioned) {
            val watchdog = launchTrackedWorker {
                commitWatchdogDelay()
                val timedOut = synchronized(stateLock) {
                    activeSession?.sessionId == sessionId && activeSession?.phase == QrLoginPhase.COMMITTING
                }
                if (timedOut) {
                    val snapshot = markDrainTimedOut("二维码登录核心凭据提交超过 ${commitDrainTimeoutMs}ms")
                    onDrainTimeout(snapshot)
                }
            }
            synchronized(stateLock) {
                activeSession?.takeIf { it.sessionId == sessionId }?.commitWatchdog = watchdog
            }
        }
        return transitioned
    }

    /** 普通轮询状态更新只允许当前会话执行，迟到 worker 不得覆盖后来者。 */
    private fun updatePhase(sessionId: String, phase: QrLoginPhase) {
        synchronized(stateLock) {
            val current = activeSession?.takeIf { it.sessionId == sessionId } ?: return
            activeSession = current.copy(
                phase = phase,
                phaseStartedAtEpochMillis = clockMillis(),
            ).also { next ->
                next.worker = current.worker
                next.commitWatchdog = current.commitWatchdog
            }
        }
    }

    /** 成功后的分组刷新独立限时执行，失败或超时只记录告警，不回滚已经提交的登录凭据。 */
    private suspend fun runPostCommitRefresh() {
        val refreshAllowed = synchronized(stateLock) { acceptingNewSessions }
        if (!refreshAllowed) return
        val completed = try {
            withTimeoutOrNull(postCommitRefreshTimeoutMs.coerceAtLeast(1L)) {
                postCommitRefresh()
                true
            } == true
        } catch (cancellation: CancellationException) {
            // WORKERS 分区取消附加刷新时必须保留协程取消语义，确保依赖关闭前 worker 真正退出。
            throw cancellation
        } catch (throwable: Throwable) {
            BiliBiliBot.logger.warn("登录成功后的分组刷新失败: ${throwable.message}", throwable)
            true
        }
        if (!completed) {
            synchronized(stateLock) {
                postCommitRefreshTimeoutCount++
            }
            BiliBiliBot.logger.warn("登录成功后的分组刷新超时(${postCommitRefreshTimeoutMs}ms)，已保留成功凭据")
        }
    }

    /** 所有轮询、watchdog 与附加刷新 job 都登记到同一集合，供 WORKERS 分区统一 drain。 */
    private fun launchTrackedWorker(block: suspend () -> Unit): Job {
        val job = workerLauncher(block)
        synchronized(stateLock) {
            workerJobs += job
        }
        job.invokeOnCompletion {
            synchronized(stateLock) {
                workerJobs -= job
            }
        }
        return job
    }

    /** drain 超时进入失败关闭状态并累计可观测计数，旧提交栅栏保持占用直到进程重启。 */
    private fun markDrainTimedOut(reason: String): QrLoginCoordinatorRuntimeSnapshot {
        return synchronized(stateLock) {
            if (drainState != QrLoginDrainState.DRAIN_TIMED_OUT) {
                if (activeSession?.phase == QrLoginPhase.COMMITTING) {
                    commitDrainTimeoutCount++
                }
                workerDrainTimeoutCount++
                acceptingNewSessions = false
                drainState = QrLoginDrainState.DRAIN_TIMED_OUT
                degradedReason = reason
            }
            runtimeSnapshotLocked(clockMillis())
        }
    }

    /** 锁内构建运行快照，调用方必须持有 stateLock 以获得同一时点的复合状态。 */
    private fun runtimeSnapshotLocked(now: Long): QrLoginCoordinatorRuntimeSnapshot {
        val current = activeSession
        return QrLoginCoordinatorRuntimeSnapshot(
            acceptingNewSessions = acceptingNewSessions,
            activePhase = current?.phase,
            phaseAgeMillis = current?.let { (now - it.phaseStartedAtEpochMillis).coerceAtLeast(0L) },
            drainState = drainState,
            activeWorkerCount = workerJobs.count(Job::isActive),
            commitDrainTimeoutCount = commitDrainTimeoutCount,
            workerDrainTimeoutCount = workerDrainTimeoutCount,
            postCommitRefreshTimeoutCount = postCommitRefreshTimeoutCount,
            degradedReason = degradedReason,
        )
    }

    /** 终态收口同时释放活动记录中的敏感字段，并唤醒命令端等待者。 */
    private fun finish(sessionId: String, phase: QrLoginPhase, message: String) {
        synchronized(stateLock) {
            // 只允许当前代际释放占用，旧流程不得清除后来者的 active 状态。
            val current = activeSession?.takeIf { it.sessionId == sessionId } ?: return
            retainTerminal(current, phase, message)
        }
    }

    /** 终态保留集中处理 active 清空和 deferred 完成，避免不同失败路径遗留全局占用。 */
    private fun retainTerminal(current: ActiveQrLoginSession, phase: QrLoginPhase, message: String) {
        current.commitWatchdog?.cancel()
        val snapshot = current.toSnapshot(phase, message)
        terminalSnapshots[current.sessionId] = RetainedQrLoginSnapshot(snapshot, clockMillis())
        activeSession = null
        current.terminal.complete(snapshot)
    }

    /** 过期时间读取不到活动会话时返回当前时间，使迟到 worker 立即退出。 */
    private fun expiresAt(sessionId: String): Long {
        return synchronized(stateLock) {
            activeSession?.takeIf { it.sessionId == sessionId }?.expiresAtEpochMillis ?: clockMillis()
        }
    }

    /** 快照文案由状态机统一生成，命令端和 WebUI 不再分别解释内部状态。 */
    private fun ActiveQrLoginSession.toSnapshot(
        targetPhase: QrLoginPhase = phase ?: QrLoginPhase.WAITING_FOR_SCAN,
        targetMessage: String = phaseMessage(targetPhase),
    ): QrLoginSessionSnapshot {
        return QrLoginSessionSnapshot(sessionId, targetPhase, expiresAtEpochMillis, targetMessage)
    }

    /** 终态只短时保留，避免长期运行进程积累历史会话 ID。 */
    private fun cleanupTerminalSnapshots(now: Long) {
        terminalSnapshots.entries.removeIf { (_, retained) ->
            now - retained.completedAtEpochMillis >= TERMINAL_RETENTION_MS
        }
    }

    companion object {
        const val LOGIN_TTL_MS = 180_000L
        const val POLL_INTERVAL_MS = 3_000L
        const val TERMINAL_RETENTION_MS = 300_000L
        const val COMMIT_DRAIN_TIMEOUT_MS = 15_000L
        const val POST_COMMIT_REFRESH_TIMEOUT_MS = 10_000L

        /** 生产入口始终复用同一个协调器，保证命令端与 WebUI 的全局互斥。 */
        val shared: QrLoginCoordinator by lazy { QrLoginCoordinator() }
    }
}

/** 状态文案保持稳定中文语义，前端无需重新翻译内部枚举。 */
private fun phaseMessage(phase: QrLoginPhase): String {
    return when (phase) {
        QrLoginPhase.WAITING_FOR_SCAN -> "等待扫码"
        QrLoginPhase.WAITING_FOR_CONFIRMATION -> "已扫码，等待确认"
        QrLoginPhase.COMMITTING -> "正在保存登录凭据"
        QrLoginPhase.SUCCEEDED -> "BiliBili 登录成功"
        QrLoginPhase.EXPIRED -> "二维码已失效，请重新登录"
        QrLoginPhase.FAILED -> "登录失败，请稍后重试"
        QrLoginPhase.CANCELLED -> "登录已取消"
    }
}

/**
 * 生产提交入口复用现有回调解析和候选配置原子写入，并在成功后刷新账号运行态。
 */
private fun commitQrLoginCallback(callbackUrl: String): Boolean {
    val callbackPayload = LoginService.parseLoginCallback(callbackUrl)
    if (callbackPayload.cookie.isEmpty() || !LoginService.commitLoginConfig(callbackPayload.cookie)) {
        return false
    }
    BiliBiliBot.cookie.replaceWith(BiliBiliBot.cookie.fromHeader(callbackPayload.cookie))
    // 若当前登录回调携带 DedeUserID，则直接刷新运行时 UID，避免额外调用 userInfo。
    callbackPayload.dedeUserId?.toLongOrNull()?.let { dedeUserId ->
        BiliBiliBot.uid = dedeUserId
    }
    // 登录成功后才恢复失效提醒门闩，避免启动期普通成功请求造成重复提醒。
    markBiliLoginSucceeded()
    return true
}
