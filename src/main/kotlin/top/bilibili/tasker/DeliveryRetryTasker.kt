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
        // 构建阶段重试按持久化输入类型回投对应 channel，不依赖尚未生成的消息快照。
        DeliveryCoordinator.dueBuildRetries().forEach { record ->
            runCatching {
                DeliveryCoordinator.markBuildQueued(record.id)
                when {
                    record.dynamicDetail != null -> BiliBiliBot.dynamicChannel.send(record.dynamicDetail)
                    record.liveDetail != null -> BiliBiliBot.liveChannel.send(record.liveDetail)
                    else -> error("交付记录缺少构建输入")
                }
            }.onFailure { error ->
                DeliveryCoordinator.recordBuildFailure(record.id, error.message)
            }
        }
        // 发送阶段只处理已经持久化完整 BiliMessage 的记录。
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
