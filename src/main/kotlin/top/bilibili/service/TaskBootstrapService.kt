package top.bilibili.service

import top.bilibili.core.BiliBiliBot
import top.bilibili.core.resource.TaskResourcePolicyRegistry
import top.bilibili.tasker.CacheClearTasker
import top.bilibili.tasker.DynamicCheckTasker
import top.bilibili.tasker.DynamicMessageTasker
import top.bilibili.tasker.ListenerTasker
import top.bilibili.tasker.LiveCheckTasker
import top.bilibili.tasker.LiveCloseCheckTasker
import top.bilibili.tasker.LiveMessageTasker
import top.bilibili.tasker.LogClearTasker
import top.bilibili.tasker.ProcessGuardian
import top.bilibili.tasker.SendTasker
import top.bilibili.tasker.SkiaCleanupTasker

/**
 * 统一启动后台任务集合，避免主启动流程手工维护任务顺序和覆盖校验。
 */
object TaskBootstrapService {
    private const val TASK_INITIALIZATION_TIMEOUT_MS = 10_000L
    private val startupTaskNames = listOf(
        "ListenerTasker",
        "DynamicCheckTasker",
        "LiveCheckTasker",
        "LiveCloseCheckTasker",
        "DynamicMessageTasker",
        "LiveMessageTasker",
        "SendTasker",
        "CacheClearTasker",
        "LogClearTasker",
        "SkiaCleanupTasker",
        "ProcessGuardian",
    )

    /**
     * 校验任务资源策略后按既定顺序启动所有后台任务。
     * @return 所有任务均接受启动请求时返回 true；任一任务拒绝启动时返回 false。
     * @throws Exception 资源策略或任务启动发生异常时向启动入口传播。
     */
    suspend fun startTasks(): Boolean {
        BiliBiliBot.logger.info("正在启动任务...")
        TaskResourcePolicyRegistry.validateCoverage(startupTaskNames)

        val taskStarts = listOf(
            "ListenerTasker" to suspend { ListenerTasker.startAndAwaitInitialization(TASK_INITIALIZATION_TIMEOUT_MS) },
            "DynamicCheckTasker" to suspend { DynamicCheckTasker.startAndAwaitInitialization(TASK_INITIALIZATION_TIMEOUT_MS) },
            "LiveCheckTasker" to suspend { LiveCheckTasker.startAndAwaitInitialization(TASK_INITIALIZATION_TIMEOUT_MS) },
            "LiveCloseCheckTasker" to suspend { LiveCloseCheckTasker.startAndAwaitInitialization(TASK_INITIALIZATION_TIMEOUT_MS) },
            "DynamicMessageTasker" to suspend { DynamicMessageTasker.startAndAwaitInitialization(TASK_INITIALIZATION_TIMEOUT_MS) },
            "LiveMessageTasker" to suspend { LiveMessageTasker.startAndAwaitInitialization(TASK_INITIALIZATION_TIMEOUT_MS) },
            "SendTasker" to suspend { SendTasker.startAndAwaitInitialization(TASK_INITIALIZATION_TIMEOUT_MS) },
            "CacheClearTasker" to suspend { CacheClearTasker.startAndAwaitInitialization(TASK_INITIALIZATION_TIMEOUT_MS) },
            "LogClearTasker" to suspend { LogClearTasker.startAndAwaitInitialization(TASK_INITIALIZATION_TIMEOUT_MS) },
            "SkiaCleanupTasker" to suspend { SkiaCleanupTasker.startAndAwaitInitialization(TASK_INITIALIZATION_TIMEOUT_MS) },
            "ProcessGuardian" to suspend { ProcessGuardian.startAndAwaitInitialization(TASK_INITIALIZATION_TIMEOUT_MS) },
        )

        // 每个任务的 Boolean 启动结果都属于启动契约，不能只记录日志后继续报告整体成功。
        taskStarts.forEach { (taskName, startTask) ->
            BiliBiliBot.logger.info("启动任务: {}", taskName)
            if (!startTask()) {
                BiliBiliBot.logger.error("任务拒绝启动: {}", taskName)
                return false
            }
        }

        BiliBiliBot.logger.info("所有任务已启动")
        return true
    }
}
