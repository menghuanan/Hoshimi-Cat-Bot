package top.bilibili.skia

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 绘图队列管理器
 */
object DrawingQueueManager {
    private val logger = LoggerFactory.getLogger(DrawingQueueManager::class.java)

    private val semaphore = Semaphore(SkiaConfig.maxConcurrent)
    private val activeCount = AtomicInteger(0)
    private val pendingCount = AtomicInteger(0)
    private val isCleaning = AtomicBoolean(false)
    private val cleaningLock = Any()
    private val lastActivityTime = AtomicLong(System.currentTimeMillis())

    /**
     * 提交绘图任务
     */
    suspend fun <T> submit(block: suspend () -> T): T {
        // 1. Atomically check and increment pending count
        val currentPending = pendingCount.getAndIncrement()
        if (currentPending >= SkiaConfig.maxQueueSize) {
            pendingCount.decrementAndGet()
            throw DrawingQueueFullException("绘图队列已满，请稍后重试")
        }

        var acquired = false
        var activated = false
        var needsRollback = true  // Track if we need to rollback pending count
        try {
            // 2. Acquire semaphore only after cleanup has no active request, and re-check after waiting.
            while (true) {
                while (isCleaning.get()) {
                    delay(100)
                }
                semaphore.acquire()
                acquired = true
                var movedToActive = false
                synchronized(cleaningLock) {
                    if (!isCleaning.get()) {
                        pendingCount.decrementAndGet()
                        needsRollback = false  // Successfully moved from pending to active
                        activeCount.incrementAndGet()
                        activated = true
                        movedToActive = true
                    }
                }
                if (movedToActive) {
                    break
                }
                // cleanup may start while this task waits on the semaphore; release and wait for the cleanup window.
                semaphore.release()
                acquired = false
            }

            // 4. Record activity
            lastActivityTime.set(System.currentTimeMillis())

            // 5. Execute with timeout
            return withTimeout(SkiaConfig.drawingTimeoutMs) {
                block()
            }
        } finally {
            if (acquired) {
                if (activated) {
                    activeCount.decrementAndGet()
                }
                semaphore.release()
            } else if (needsRollback) {
                // 仅在真正进入等待队列失败时回滚，避免 pending 计数被重复扣减。
                // Exception occurred before acquiring semaphore
                pendingCount.decrementAndGet()
            }
            lastActivityTime.set(System.currentTimeMillis())
        }
    }

    /**
     * 等待所有活动任务完成
     */
    suspend fun awaitAllCompleted() {
        runExclusiveCleanup { }
    }

    /**
     * 清理全局 Skia/字体资源时暂停新绘图并等待活动绘图完成，调用方的 close 块在暂停窗口内执行。
     */
    suspend fun runExclusiveCleanup(block: () -> Unit) {
        synchronized(cleaningLock) {
            isCleaning.set(true)
        }
        try {
            // 等待所有活动任务完成；已排队任务会在 acquire 后二次检查 cleaning 并退回等待。
            while (activeCount.get() > 0) {
                delay(100)
            }
            block()
        } finally {
            synchronized(cleaningLock) {
                isCleaning.set(false)
            }
        }
    }

    /**
     * 获取队列状态
     */
    fun getQueueStatus(): QueueStatus {
        return QueueStatus(
            pendingCount = pendingCount.get(),
            activeCount = activeCount.get(),
            isFull = pendingCount.get() >= SkiaConfig.maxQueueSize,
            lastActivityTime = lastActivityTime.get()
        )
    }

    /**
     * 检查是否空闲超时
     */
    fun isIdleTimeout(): Boolean {
        val idleTime = System.currentTimeMillis() - lastActivityTime.get()
        return activeCount.get() == 0 && idleTime >= SkiaConfig.idleTimeoutMs
    }
}

/**
 * 队列状态
 *
 * @param pendingCount 等待中的任务数
 * @param activeCount 执行中的任务数
 * @param isFull 队列是否已满
 * @param lastActivityTime 最近一次活动时间戳
 */
data class QueueStatus(
    val pendingCount: Int,
    val activeCount: Int,
    val isFull: Boolean,
    val lastActivityTime: Long
)

/**
 * 队列已满异常
 */
class DrawingQueueFullException(message: String) : Exception(message)
