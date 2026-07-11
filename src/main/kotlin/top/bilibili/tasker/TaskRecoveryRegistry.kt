package top.bilibili.tasker

import java.time.Clock
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import top.bilibili.core.BiliBiliBot
import top.bilibili.core.resource.TaskResourcePolicy
import top.bilibili.core.resource.TaskResourcePolicyRegistry

/**
 * 描述 Tasker 的启动与主作业恢复契约，集中约束恢复资格、核心程度和资源策略。
 *
 * @param taskName 稳定任务名称
 * @param tasker 提供当前单例任务实例
 * @param autoRecover 主作业异常结束后是否允许自动恢复
 * @param critical 是否属于核心业务任务
 */
data class TaskRecoveryRegistration(
    val taskName: String,
    val tasker: () -> BiliTasker,
    val autoRecover: Boolean,
    val critical: Boolean,
) {
    val resourcePolicy: TaskResourcePolicy
        get() = requireNotNull(TaskResourcePolicyRegistry.policyOf(taskName)) {
            "任务未声明资源策略: $taskName"
        }
}

/**
 * Tasker 主作业恢复结果，供守护进程区分恢复、退避、熔断和无需处理。
 */
sealed interface TaskRecoveryResult {
    data object NotEligible : TaskRecoveryResult
    data class Waiting(val delayMillis: Long) : TaskRecoveryResult
    data class Restarted(val taskName: String) : TaskRecoveryResult
    data class CircuitOpen(val taskName: String) : TaskRecoveryResult
}

/**
 * 统一维护 Tasker 主作业的启动定义、滑动窗口预算和熔断状态。
 *
 * 注册表只重启已有单例，不创建并存实例；进程重启会自然重置熔断状态。
 */
object TaskRecoveryRegistry {
    private const val RECOVERY_WINDOW_MS = 30L * 60L * 1_000L
    private const val MAX_RECOVERIES = 5
    private val backoffMillis = longArrayOf(5_000L, 15_000L, 45_000L, 135_000L, 300_000L)
    private val registrations = LinkedHashMap<String, TaskRecoveryRegistration>()
    private val recoveryStates = ConcurrentHashMap<String, RecoveryState>()
    internal var clock: Clock = Clock.systemUTC()
    internal var delayAction: suspend (Long) -> Unit = { delay(it) }

    /**
     * 用完整启动清单替换当前注册内容，避免热测试或重复装配保留过期定义。
     */
    @Synchronized
    fun install(entries: List<TaskRecoveryRegistration>) {
        val duplicateNames = entries.groupingBy { it.taskName }.eachCount().filterValues { it > 1 }.keys
        require(duplicateNames.isEmpty()) { "任务恢复注册重复: ${duplicateNames.joinToString()}" }
        TaskResourcePolicyRegistry.validateCoverage(entries.map { it.taskName })
        registrations.clear()
        entries.forEach { entry -> registrations[entry.taskName] = entry }
        recoveryStates.keys.retainAll(registrations.keys)
    }

    /**
     * 返回稳定启动顺序的注册快照，调用方不得修改注册表本体。
     */
    @Synchronized
    fun registrations(): List<TaskRecoveryRegistration> = registrations.values.toList()

    /**
     * 尝试恢复指定异常终止的主作业，并严格执行窗口预算与固定退避序列。
     */
    suspend fun recover(taskName: String): TaskRecoveryResult {
        val registration = synchronized(this) { registrations[taskName] } ?: return TaskRecoveryResult.NotEligible
        val tasker = registration.tasker()
        if (!registration.autoRecover || BiliBiliBot.isStopping() || !tasker.healthSnapshot().recoverableMainFailure) {
            return TaskRecoveryResult.NotEligible
        }

        // 预算判断与恢复次数登记必须原子化，避免并发 guardian tick 重复消耗并拉起同一任务。
        val attempt = synchronized(recoveryStates) {
            val now = clock.millis()
            val state = recoveryStates.computeIfAbsent(taskName) { RecoveryState() }
            while (state.attempts.isNotEmpty() && now - state.attempts.first() >= RECOVERY_WINDOW_MS) {
                state.attempts.removeFirst()
            }
            if (state.circuitOpen || state.attempts.size >= MAX_RECOVERIES) {
                state.circuitOpen = true
                null
            } else {
                state.attempts.addLast(now)
                state.attempts.size
            }
        } ?: run {
            tasker.markMainCircuitOpen()
            return TaskRecoveryResult.CircuitOpen(taskName)
        }

        val backoff = backoffMillis[attempt - 1]
        delayAction(backoff)
        if (BiliBiliBot.isStopping() || !tasker.healthSnapshot().recoverableMainFailure) {
            return TaskRecoveryResult.NotEligible
        }

        // 同一单例在 start 内再次校验活跃状态，防止退避期间由其他路径完成恢复。
        return if (tasker.restartAfterFailure()) {
            TaskRecoveryResult.Restarted(taskName)
        } else {
            TaskRecoveryResult.Waiting(backoff)
        }
    }

    /**
     * 清空运行期预算，仅供进程级装配和测试隔离使用。
     */
    internal fun resetRuntimeState() {
        recoveryStates.clear()
        clock = Clock.systemUTC()
        delayAction = { delay(it) }
    }

    /** 保存单个任务在当前进程内的恢复时间窗与熔断状态。 */
    private data class RecoveryState(
        val attempts: ArrayDeque<Long> = ArrayDeque(),
        var circuitOpen: Boolean = false,
    )
}
