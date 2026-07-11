package top.bilibili.service

import top.bilibili.core.BiliBiliBot
import top.bilibili.core.resource.TaskResourcePolicyRegistry
import top.bilibili.tasker.CacheClearTasker
import top.bilibili.tasker.DynamicCheckTasker
import top.bilibili.tasker.DynamicMessageTasker
import top.bilibili.tasker.DeliveryRetryTasker
import top.bilibili.tasker.ListenerTasker
import top.bilibili.tasker.LiveCheckTasker
import top.bilibili.tasker.LiveCloseCheckTasker
import top.bilibili.tasker.LiveMessageTasker
import top.bilibili.tasker.LogClearTasker
import top.bilibili.tasker.ProcessGuardian
import top.bilibili.tasker.SendTasker
import top.bilibili.tasker.SkiaCleanupTasker
import top.bilibili.tasker.TaskRecoveryRegistration
import top.bilibili.tasker.TaskRecoveryRegistry

/**
 * 统一启动后台任务集合，避免主启动流程手工维护任务顺序和覆盖校验。
 */
object TaskBootstrapService {
    private const val TASK_INITIALIZATION_TIMEOUT_MS = 10_000L
    private val taskRegistrations = listOf(
        TaskRecoveryRegistration("ListenerTasker", { ListenerTasker }, autoRecover = true, critical = true),
        TaskRecoveryRegistration("DynamicCheckTasker", { DynamicCheckTasker }, autoRecover = true, critical = true),
        TaskRecoveryRegistration("LiveCheckTasker", { LiveCheckTasker }, autoRecover = true, critical = true),
        TaskRecoveryRegistration("LiveCloseCheckTasker", { LiveCloseCheckTasker }, autoRecover = true, critical = true),
        TaskRecoveryRegistration("DynamicMessageTasker", { DynamicMessageTasker }, autoRecover = true, critical = true),
        TaskRecoveryRegistration("DeliveryRetryTasker", { DeliveryRetryTasker }, autoRecover = true, critical = true),
        TaskRecoveryRegistration("LiveMessageTasker", { LiveMessageTasker }, autoRecover = true, critical = true),
        TaskRecoveryRegistration("SendTasker", { SendTasker }, autoRecover = true, critical = true),
        TaskRecoveryRegistration("CacheClearTasker", { CacheClearTasker }, autoRecover = true, critical = false),
        TaskRecoveryRegistration("LogClearTasker", { LogClearTasker }, autoRecover = true, critical = false),
        TaskRecoveryRegistration("SkiaCleanupTasker", { SkiaCleanupTasker }, autoRecover = true, critical = false),
        // Guardian 不自我监督；其异常由根生命周期和健康日志暴露，避免形成递归恢复环。
        TaskRecoveryRegistration("ProcessGuardian", { ProcessGuardian }, autoRecover = false, critical = true),
    )

    /**
     * 校验任务资源策略后按既定顺序启动所有后台任务。
     * @return 所有任务均接受启动请求时返回 true；任一任务拒绝启动时返回 false。
     * @throws Exception 资源策略或任务启动发生异常时向启动入口传播。
     */
    suspend fun startTasks(): Boolean {
        BiliBiliBot.logger.info("正在启动任务...")
        TaskRecoveryRegistry.install(taskRegistrations)

        // 每个任务的 Boolean 启动结果都属于启动契约，不能只记录日志后继续报告整体成功。
        TaskRecoveryRegistry.registrations().forEach { registration ->
            BiliBiliBot.logger.info("启动任务: {}", registration.taskName)
            if (!registration.tasker().startAndAwaitInitialization(TASK_INITIALIZATION_TIMEOUT_MS)) {
                BiliBiliBot.logger.error("任务拒绝启动: {}", registration.taskName)
                return false
            }
        }

        BiliBiliBot.logger.info("所有任务已启动")
        return true
    }
}
