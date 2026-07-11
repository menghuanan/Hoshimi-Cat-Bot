package top.bilibili.tasker

import top.bilibili.core.BiliBiliBot
import top.bilibili.delivery.DeliveryCoordinator
import top.bilibili.delivery.DeliveryReceipt

/**
 * 周期恢复交付账本中的到期消息快照；重新入队不代表成功，仍等待 SendTasker 平台回执。
 */
object DeliveryRetryTasker : BiliTasker("DeliveryRetryTasker") {
    override var interval: Int = 30

    override fun init() {
        DeliveryCoordinator.initialize()
    }

    override suspend fun main() {
        DeliveryCoordinator.dueRetries().forEach { record ->
            val message = record.message ?: return@forEach
            runCatching {
                BiliBiliBot.messageChannel.send(message)
                DeliveryCoordinator.markRetryQueued(record.id)
            }
                .onFailure { error ->
                    DeliveryCoordinator.recordReceipt(DeliveryReceipt(record.id, false, error.message))
                }
        }
    }
}
