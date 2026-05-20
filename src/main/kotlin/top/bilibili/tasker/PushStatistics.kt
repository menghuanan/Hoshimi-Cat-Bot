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
    val recentRecords: List<PushDeliveryRecordSnapshot>,
)

/**
 * 最近推送记录快照只保留首页需要的时间、类型、状态和摘要，避免把任务内部对象直接暴露出去。
 */
data class PushDeliveryRecordSnapshot(
    val timestampEpochMillis: Long,
    val type: String,
    val success: Boolean,
    val summary: String,
    val target: String?,
)

/**
 * 按系统自然日滚动的轻量计数器，避免为了首页指标引入新的持久化结构。
 */
internal class DailyPushStatsCounter(
    private val todayProvider: () -> LocalDate = { LocalDate.now() },
    private val currentTimeMillisProvider: () -> Long = { System.currentTimeMillis() },
) {
    private companion object {
        // 首页只展示最近几条推送，避免任务进程长期运行时把内存历史无限放大。
        const val MAX_RECENT_RECORDS = 5
    }

    private var currentDate: LocalDate = todayProvider()
    private var dynamicSuccessCount = 0
    private var liveSuccessCount = 0
    private var liveCloseSuccessCount = 0
    private var failureCount = 0
    private var lastSuccessAtEpochMillis: Long? = null
    private val recentRecords = ArrayDeque<PushDeliveryRecordSnapshot>()

    /**
     * 记录一次成功投递，计数口径按“实际发送到单个联系人”累计。
     */
    fun recordSuccess(type: PushStatisticType) = synchronized(this) {
        recordDelivery(type = type, success = true, summary = type.name)
    }

    /**
     * 记录一次投递失败，失败数同样按单个联系人粒度累计。
     */
    fun recordFailure(type: PushStatisticType) = synchronized(this) {
        recordDelivery(type = type, success = false, summary = type.name)
    }

    /**
     * 记录一次完整的推送结果，同时更新首页统计和最近记录列表。
     */
    fun recordDelivery(
        type: PushStatisticType,
        success: Boolean,
        summary: String,
        target: String? = null,
    ) = synchronized(this) {
        ensureCurrentDate()
        when (type) {
            PushStatisticType.DYNAMIC -> if (success) dynamicSuccessCount += 1 else failureCount += 1
            PushStatisticType.LIVE -> if (success) liveSuccessCount += 1 else failureCount += 1
            PushStatisticType.LIVE_CLOSE -> if (success) liveCloseSuccessCount += 1 else failureCount += 1
        }
        if (success) {
            lastSuccessAtEpochMillis = currentTimeMillisProvider()
        }
        pushRecentRecord(
            PushDeliveryRecordSnapshot(
                timestampEpochMillis = currentTimeMillisProvider(),
                type = type.name,
                success = success,
                summary = summary,
                target = target,
            ),
        )
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
            recentRecords = recentRecords.toList(),
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
        recentRecords.clear()
    }

    /**
     * 最近记录采用头插加限长策略，保证首页取出的总是最新几条而不会无限增长。
     */
    private fun pushRecentRecord(record: PushDeliveryRecordSnapshot) {
        recentRecords.addFirst(record)
        while (recentRecords.size > MAX_RECENT_RECORDS) {
            recentRecords.removeLast()
        }
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
     * 记录带摘要的完整推送结果，供首页最近推送记录直接展示。
     */
    fun recordDelivery(
        type: PushStatisticType,
        success: Boolean,
        summary: String,
        target: String? = null,
    ) {
        counter.recordDelivery(type = type, success = success, summary = summary, target = target)
    }

    /**
     * 导出当前自然日统计快照，供 WebUI 只读展示。
     */
    fun snapshot(): DailyPushStatsSnapshot {
        return counter.snapshot()
    }
}
