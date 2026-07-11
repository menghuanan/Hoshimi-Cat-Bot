package top.bilibili.tasker

import top.bilibili.core.BiliBiliBot
import top.bilibili.api.getLiveStatus
import top.bilibili.data.LIVE_LINK
import top.bilibili.data.LiveCloseMessage
import top.bilibili.utils.formatDuration
import top.bilibili.utils.formatRelativeTime
import top.bilibili.utils.formatTime
import java.time.Instant
import top.bilibili.delivery.DeliveryCoordinator


/**
 * 检查已推送直播是否下播，并生成下播通知。
 */
object LiveCloseCheckTasker : BiliCheckTasker("LiveCloseCheckTasker")  {

    // 下播检测同样复用区间调度，统一遵守 normalRange/lowSpeedRange 配置。
    override var interval: Int = 60
    override var checkReportEnable = false

    private var nowTime = Instant.now().epochSecond

    override suspend fun main() {
        val openRecords = DeliveryCoordinator.deliveredLiveOpenRecords()
        if (openRecords.isNotEmpty()) {
            nowTime = Instant.now().epochSecond
            val openMessages = openRecords.mapNotNull { it.message as? top.bilibili.data.LiveMessage }
            val liveStatusMap = client.getLiveStatus(openMessages.map { it.mid }.distinct())
            val liveStatusList = liveStatusMap?.map { it.value }?.filter { it.liveStatus != 1 }

            liveStatusList?.forEach { info ->
                openRecords.filter { record -> (record.message as? top.bilibili.data.LiveMessage)?.mid == info.uid }
                    .forEach { openRecord ->
                        val openMessage = openRecord.message as? top.bilibili.data.LiveMessage ?: return@forEach
                        val liveTime = openMessage.timestamp.toLong()
                        val businessId = "${openRecord.businessId}:close"
                        // 链接解析可能挂起，先在业务协程完成；账本临界区只负责注入稳定 ID 与原子保存。
                        val closeLink = LIVE_LINK(info.roomId.toString())
                        val discovery = DeliveryCoordinator.discoverReadyLiveClose(openRecord, businessId) { deliveryId ->
                            LiveCloseMessage(
                                info.roomId,
                                info.uid,
                                info.uname,
                                liveTime.formatRelativeTime,
                                0,
                                nowTime.formatTime,
                                (nowTime - liveTime).formatDuration(),
                                info.title,
                                info.area,
                                closeLink,
                                contact = openRecord.contact,
                                deliveryId = deliveryId,
                            )
                        }
                        // 已存在记录由账本租约或终态接管，轮询只发送本轮原子创建的 READY 消息。
                        if (!discovery.created) return@forEach
                        val message = discovery.record.message as? LiveCloseMessage ?: return@forEach
                        BiliBiliBot.messageChannel.send(message)
                    }
            }
        }
    }

}
