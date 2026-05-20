package top.bilibili.tasker

import top.bilibili.data.BiliMessage
import top.bilibili.data.DynamicMessage
import top.bilibili.data.LiveCloseMessage
import top.bilibili.data.LiveMessage
import java.time.LocalDate

/**
 * 首页推送统计使用的消息类型，保持和业务消息模型一一对应。
 */
enum class PushStatisticType {
    DYNAMIC,
    LIVE,
    LIVE_CLOSE;

    companion object {
        /**
         * 将业务消息映射为统计类型，避免发送链路直接依赖前端展示字段。
         */
        fun from(message: BiliMessage): PushStatisticType {
            return when (message) {
                is DynamicMessage -> DYNAMIC
                is LiveMessage -> LIVE
                is LiveCloseMessage -> LIVE_CLOSE
            }
        }
    }
}

/**
 * 单日推送统计快照，供 WebUI facade 转换成序列化 DTO。
 */
data class DailyPushStatsSnapshot(
    val date: String,
    val total: Int,
    val dynamic: Int,
    val live: Int,
    val liveClose: Int,
    val failed: Int,
    val lastSuccessAtEpochMillis: Long?,
)

/**
 * 按系统自然日滚动的轻量计数器，避免为了首页指标引入新的持久化结构。
 */
internal class DailyPushStatsCounter(
    private val todayProvider: () -> LocalDate = { LocalDate.now() },
    private val currentTimeMillisProvider: () -> Long = { System.currentTimeMillis() },
) {
    private var currentDate: LocalDate = todayProvider()
    private var dynamicSuccessCount = 0
    private var liveSuccessCount = 0
    private var liveCloseSuccessCount = 0
    private var failureCount = 0
    private var lastSuccessAtEpochMillis: Long? = null

    /**
     * 记录一次成功投递，计数口径按“实际发送到单个联系人”累计。
     */
    fun recordSuccess(type: PushStatisticType) = synchronized(this) {
        ensureCurrentDate()
        when (type) {
            PushStatisticType.DYNAMIC -> dynamicSuccessCount += 1
            PushStatisticType.LIVE -> liveSuccessCount += 1
            PushStatisticType.LIVE_CLOSE -> liveCloseSuccessCount += 1
        }
        lastSuccessAtEpochMillis = currentTimeMillisProvider()
    }

    /**
     * 记录一次投递失败，失败数同样按单个联系人粒度累计。
     */
    fun recordFailure(type: PushStatisticType) = synchronized(this) {
        ensureCurrentDate()
        failureCount += 1
    }

    /**
     * 返回当前自然日的不可变快照，调用方无法反向修改计数器内部状态。
     */
    fun snapshot(): DailyPushStatsSnapshot = synchronized(this) {
        ensureCurrentDate()
        val total = dynamicSuccessCount + liveSuccessCount + liveCloseSuccessCount
        DailyPushStatsSnapshot(
            date = currentDate.toString(),
            total = total,
            dynamic = dynamicSuccessCount,
            live = liveSuccessCount,
            liveClose = liveCloseSuccessCount,
            failed = failureCount,
            lastSuccessAtEpochMillis = lastSuccessAtEpochMillis,
        )
    }

    /**
     * 自然日变化时清空上一天的内存计数，保证首页始终展示“今日”统计。
     */
    private fun ensureCurrentDate() {
        val today = todayProvider()
        if (today == currentDate) {
            return
        }
        currentDate = today
        dynamicSuccessCount = 0
        liveSuccessCount = 0
        liveCloseSuccessCount = 0
        failureCount = 0
        lastSuccessAtEpochMillis = null
    }
}

/**
 * SendTasker 使用的进程内推送统计入口，生命周期随当前 Bot 进程重置。
 */
object PushStatistics {
    private val counter = DailyPushStatsCounter()

    /**
     * 记录指定业务消息类型的一次成功推送。
     */
    fun recordSuccess(type: PushStatisticType) {
        counter.recordSuccess(type)
    }

    /**
     * 记录指定业务消息类型的一次失败推送。
     */
    fun recordFailure(type: PushStatisticType) {
        counter.recordFailure(type)
    }

    /**
     * 导出当前自然日统计快照，供 WebUI 只读展示。
     */
    fun snapshot(): DailyPushStatsSnapshot {
        return counter.snapshot()
    }
}
