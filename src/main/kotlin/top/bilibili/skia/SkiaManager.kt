package top.bilibili.skia

import kotlinx.coroutines.*
import org.jetbrains.skia.Graphics
import org.slf4j.LoggerFactory
import top.bilibili.draw.FontManager
import top.bilibili.utils.ImageCache
import top.bilibili.utils.FontUtils
import java.util.concurrent.atomic.AtomicLong

/**
 * Skia 资源管理器 - 统一入口
 */
object SkiaManager {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val logger = LoggerFactory.getLogger(SkiaManager::class.java)

    init {
        // Validate configuration on initialization
        try {
            SkiaConfig.validate()
            logger.info("SkiaManager initialized with valid configuration")
        } catch (e: IllegalArgumentException) {
            logger.error("Invalid SkiaConfig detected", e)
            throw e
        }
    }

    // 统计信息
    private val totalDrawingCount = AtomicLong(0)
    private var cleanupState = SkiaCleanupState()
    private val emergencyCleanupInProgress = java.util.concurrent.atomic.AtomicBoolean(false)
    private val startTime = System.currentTimeMillis()

    /**
     * 执行绘图任务（统一入口）
     */
    suspend fun <T> executeDrawing(block: suspend DrawingSession.() -> T): T {
        totalDrawingCount.incrementAndGet()

        return executeInProcess(block)
    }

    /**
     * 进程内执行
     */
    private suspend fun <T> executeInProcess(block: suspend DrawingSession.() -> T): T {
        return DrawingQueueManager.submit {
            // 每次绘制隔离会话，可确保异常路径也能收口本次创建的全部原生资源。
            DrawingSession().use { session ->
                session.block()
            }
        }
    }

    /**
     * 执行清理
     */
    suspend fun performCleanup(): SkiaCleanupResult {
        logger.debug("开始执行 Skia 清理...")

        // 清理 block 必须完整处于闸门内；超时则跳过 purge，避免与新绘图并发访问全局缓存。
        val cleaned = runCatching {
            withTimeoutOrNull(30_000L) {
                DrawingQueueManager.runExclusiveCleanup {
                    clearGlobalCaches(forcePurgeAllSkiaCaches = false)
                }
            } ?: false
        }.onFailure { e ->
            logger.warn("等待活动任务并清理全局缓存时发生异常，本轮跳过 purge", e)
        }.getOrDefault(false)
        if (!cleaned) logger.warn("等待活动任务完成超时，本轮跳过 Skia 全局缓存清理")

        // 3. 强制 GC
        repeat(3) {
            // 连续执行 GC 与 finalization，可尽量促使已释放的原生包装对象尽快完成回收。
            System.gc()
            System.runFinalization()
            delay(100)
        }

        val result = if (cleaned) SkiaCleanupResult.COMPLETED else SkiaCleanupResult.SKIPPED
        recordCleanupAttempt(result)
        logger.debug(if (result.completed) "Skia 清理完成" else "Skia 清理未执行")
        return result
    }

    /**
     * 在内存临界时执行紧急清理，优先回收可清理的 Skia 全局缓存并提高 GC 强度。
     */
    suspend fun performEmergencyCleanup(): SkiaCleanupResult {
        val now = System.currentTimeMillis()
        val last = cleanupStateSnapshot().lastEmergencyCleanupAt
        if (now - last < SkiaConfig.emergencyCleanupCooldownMs) {
            logger.debug(
                "距离上次紧急清理仅 {}ms，未达到冷却时间 {}ms，跳过本次紧急清理",
                now - last,
                SkiaConfig.emergencyCleanupCooldownMs,
            )
            return SkiaCleanupResult.SKIPPED
        }
        if (!emergencyCleanupInProgress.compareAndSet(false, true)) {
            // 独立的执行中标记只排除并发清理；失败尝试不会提前占用下一轮冷却时间。
            return SkiaCleanupResult.SKIPPED
        }

        try {
            logger.warn("触发 Skia 紧急清理：开始尝试回收全局缓存并加速归还 native 内存")

        // 紧急 purge 同样保持在完整闸门内；互斥无法建立时宁可跳过，不能冒险并发清理。
            val cleaned = runCatching {
                withTimeoutOrNull(15_000L) {
                    DrawingQueueManager.runExclusiveCleanup {
                        clearGlobalCaches(forcePurgeAllSkiaCaches = true)
                    }
                } ?: false
            }.onFailure { e ->
                logger.warn("紧急清理等待活动任务时发生异常，本轮跳过 purge", e)
            }.getOrDefault(false)
            if (!cleaned) logger.warn("紧急清理等待活动任务完成超时，本轮跳过 Skia 全局缓存清理")

            repeat(5) {
                // 连续执行更多轮 GC/finalization，尽量促使已标记对象及时释放 native 包装。
                System.gc()
                System.runFinalization()
                delay(120)
            }

            val result = if (cleaned) SkiaCleanupResult.COMPLETED else SkiaCleanupResult.SKIPPED
            recordCleanupAttempt(result, emergencyAttemptAt = now)
            logger.warn(if (result.completed) "Skia 紧急清理完成" else "Skia 紧急清理未执行")
            return result
        } finally {
            emergencyCleanupInProgress.set(false)
        }
    }

    /**
     * 清理全局缓存
     */
    private fun clearGlobalCaches(forcePurgeAllSkiaCaches: Boolean): Boolean {
        // 段落缓存会随不同文本内容持续增长，空闲清理时需要显式重置。
        runCatching {
            FontUtils.resetParagraphCache()
            logger.debug("FontUtils paragraph cache cleared successfully")
        }.onFailure { e ->
            logger.warn("Failed to clear FontUtils paragraph cache", e)
        }

        // 通过 Graphics API 主动清理 Skia 资源缓存，避免仅依赖被动淘汰导致长期高水位。
        val skiaCachePurged = runCatching {
            val beforeResourceCacheUsed = Graphics.resourceCacheTotalUsed
            if (forcePurgeAllSkiaCaches) {
                Graphics.purgeAllCaches()
            } else {
                Graphics.purgeResourceCache()
            }
            val afterResourceCacheUsed = Graphics.resourceCacheTotalUsed
            logger.debug(
                "Skia resource cache purge completed: mode={}, before={}B, after={}B",
                if (forcePurgeAllSkiaCaches) "all" else "resource-only",
                beforeResourceCacheUsed,
                afterResourceCacheUsed,
            )
            true
        }.onFailure { e ->
            logger.warn("Failed to purge Skia resource cache via Graphics API", e)
        }.getOrDefault(false)

        // 清理图片缓存
        runCatching {
            ImageCache.cleanCache()
            logger.debug("ImageCache cleared successfully")
        }.onFailure { e ->
            logger.warn("Failed to clear ImageCache", e)
        }

        return skiaCachePurged
    }

    /**
     * 获取内存使用率
     */
    private fun getMemoryUsage(): Double {
        val runtime = Runtime.getRuntime()
        val used = runtime.totalMemory() - runtime.freeMemory()
        val max = runtime.maxMemory()
        return used.toDouble() / max.toDouble()
    }

    /**
     * 获取状态信息
     */
    fun getStatus(): SkiaManagerStatus {
        return SkiaManagerStatus(
            mode = "IN_PROCESS",
            memoryUsage = getMemoryUsage(),
            resourceCacheBytes = runCatching { Graphics.resourceCacheTotalUsed.toLong() }.getOrDefault(-1L),
            totalDrawingCount = totalDrawingCount.get(),
            totalCleanupCount = cleanupStateSnapshot().successfulCleanupCount,
            queueStatus = DrawingQueueManager.getQueueStatus(),
            uptimeMs = System.currentTimeMillis() - startTime
        )
    }

    /**
     * 关闭管理器
     */
    suspend fun shutdown() {
        logger.info("关闭 SkiaManager...")
        performCleanup()

        // 先取消协程作用域，确保没有新任务运行
        scope.cancel()

        // 然后关闭 FontManager
        try {
            FontManager.close()
            logger.info("FontManager 已关闭")
        } catch (e: Exception) {
            logger.error("关闭 FontManager 时出错: ${e.message}", e)
        }
    }

    /**
     * 原子更新清理成功次数与紧急冷却时间，跳过的尝试不会改变成功状态。
     */
    @Synchronized
    private fun recordCleanupAttempt(result: SkiaCleanupResult, emergencyAttemptAt: Long? = null) {
        cleanupState = cleanupState.afterAttempt(result, emergencyAttemptAt)
    }

    /**
     * 返回一致的清理状态快照，避免监控读取到计数与冷却时间的中间状态。
     */
    @Synchronized
    private fun cleanupStateSnapshot(): SkiaCleanupState = cleanupState
}

/**
 * 单次 Skia 清理的真实执行结果；只有 COMPLETED 表示 purge 已在互斥闸门内执行。
 */
enum class SkiaCleanupResult(val completed: Boolean) {
    COMPLETED(true),
    SKIPPED(false),
}

/**
 * Skia 清理成功状态；冷却时间只记录最近一次真正完成的紧急清理。
 */
data class SkiaCleanupState(
    val successfulCleanupCount: Long = 0L,
    val lastEmergencyCleanupAt: Long = 0L,
) {
    /**
     * 根据真实执行结果生成下一状态，跳过的尝试保持原状态不变。
     */
    fun afterAttempt(result: SkiaCleanupResult, emergencyAttemptAt: Long? = null): SkiaCleanupState {
        if (!result.completed) return this
        return copy(
            successfulCleanupCount = successfulCleanupCount + 1L,
            lastEmergencyCleanupAt = emergencyAttemptAt ?: lastEmergencyCleanupAt,
        )
    }
}

/**
 * SkiaManager 状态
 *
 * @param mode 当前运行模式；当前仅支持进程内绘图。
 * @param memoryUsage 当前内存使用率
 * @param resourceCacheBytes Skia Graphics 可直接观测的 native resource cache 字节数，采集失败时为 -1。
 * @param totalDrawingCount 累计绘图次数
 * @param totalCleanupCount 累计清理次数
 * @param queueStatus 当前队列状态
 * @param uptimeMs 管理器运行时长
 */
data class SkiaManagerStatus(
    val mode: String,
    val memoryUsage: Double,
    val resourceCacheBytes: Long,
    val totalDrawingCount: Long,
    val totalCleanupCount: Long,
    val queueStatus: QueueStatus,
    val uptimeMs: Long
)
