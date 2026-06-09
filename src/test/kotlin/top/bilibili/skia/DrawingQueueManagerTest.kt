package top.bilibili.skia

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class DrawingQueueManagerTest {
    /**
     * 已经排队等待 semaphore 的绘图在 cleanup 开始后也必须停住，避免继续使用即将 close 的全局样式。
     */
    @Test
    fun `queued drawing should wait when cleanup starts before semaphore is acquired`() = runBlocking {
        val activeTarget = SkiaConfig.maxConcurrent.coerceAtLeast(1)
        val activeStarted = AtomicInteger(0)
        val allActiveStarted = CompletableDeferred<Unit>()
        val releaseActive = CompletableDeferred<Unit>()
        val queuedStarted = CompletableDeferred<Unit>()
        val cleanupEnteredBlock = CompletableDeferred<Unit>()
        val releaseCleanupBlock = CompletableDeferred<Unit>()
        val cleanupCompleted = AtomicBoolean(false)

        val activeJobs = (1..activeTarget).map {
            async(Dispatchers.Default) {
                DrawingQueueManager.submit {
                    if (activeStarted.incrementAndGet() == activeTarget) {
                        allActiveStarted.complete(Unit)
                    }
                    releaseActive.await()
                }
            }
        }
        withTimeout(1_000L) { allActiveStarted.await() }

        val queuedJob = async(Dispatchers.Default) {
            DrawingQueueManager.submit {
                queuedStarted.complete(Unit)
                "queued"
            }
        }
        waitUntilPending()
        // 让排队任务越过 submit 开头的 cleaning 检查并停在 semaphore acquire 上，稳定复现旧竞态。
        delay(100L)

        val cleanupJob = launch(Dispatchers.Default) {
            DrawingQueueManager.runExclusiveCleanup {
                cleanupEnteredBlock.complete(Unit)
                runBlocking {
                    releaseCleanupBlock.await()
                }
                cleanupCompleted.set(true)
            }
        }
        waitUntilCleaningRequested()
        releaseActive.complete(Unit)

        val queuedStartedEarly = withTimeoutOrNull(300L) {
            queuedStarted.await()
            true
        } ?: false
        assertFalse(queuedStartedEarly, "queued drawing started while cleanup was requested")

        withTimeout(1_000L) { cleanupEnteredBlock.await() }
        releaseCleanupBlock.complete(Unit)
        cleanupJob.join()
        assertTrue(cleanupCompleted.get())
        assertEquals("queued", withTimeout(1_000L) { queuedJob.await() })
        activeJobs.awaitAll()
        Unit
    }

    /**
     * 测试只通过公开队列状态确认任务已经进入等待区，避免直接依赖 semaphore 实现。
     */
    private suspend fun waitUntilPending() {
        withTimeout(1_000L) {
            while (DrawingQueueManager.getQueueStatus().pendingCount == 0) {
                delay(10L)
            }
        }
    }

    /**
     * cleanup 请求状态当前只在 manager 内部保存；回归测试用反射等待闸门翻转以复现竞态窗口。
     */
    private suspend fun waitUntilCleaningRequested() {
        val field = DrawingQueueManager::class.java.getDeclaredField("isCleaning").apply {
            isAccessible = true
        }
        val cleaning = field.get(DrawingQueueManager) as java.util.concurrent.atomic.AtomicBoolean
        withTimeout(1_000L) {
            while (!cleaning.get()) {
                delay(10L)
            }
        }
        if (!cleaning.get()) {
            fail("cleanup was not requested")
        }
    }
}
