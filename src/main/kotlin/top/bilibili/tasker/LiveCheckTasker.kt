package top.bilibili.tasker

import kotlinx.coroutines.withTimeout
import top.bilibili.BiliConfigManager
import top.bilibili.BiliData
import top.bilibili.api.getLive
import top.bilibili.core.BiliBiliBot
import top.bilibili.core.BiliBiliBot.liveUsers
import top.bilibili.service.PushFanoutService
import top.bilibili.utils.logger
import top.bilibili.utils.sendAll
import java.time.Instant
import top.bilibili.delivery.DeliveryCoordinator
import top.bilibili.delivery.DeliveryKind

/**
 * 轮询关注列表中的新开播直播并投递到消息流水线。
 */
object LiveCheckTasker : BiliCheckTasker("LiveCheckTasker") {
    // 真实轮询间隔由 BiliCheckTasker 按 normalRange/lowSpeedRange 动态重算；这里仅保留初始化回退值。
    override var interval = 60
    private var liveCloseEnable = BiliConfigManager.config.enableConfig.liveCloseNotifyEnable

    private val liveChannel by BiliBiliBot::liveChannel
    private val dynamic by BiliData::dynamic

    private var lastLive: Long = Instant.now().epochSecond

    override fun init() {
        super.init()
        DeliveryCoordinator.initialize()
    }

    override suspend fun main() = withTimeout(180003) {
        logger.debug("开始直播检查...")

        val followingUsers = dynamic.filter { it.value.contacts.isNotEmpty() }.map { it.key }
        if (followingUsers.isEmpty()) {
            logger.debug("没有任何订阅，跳过直播检查")
            return@withTimeout
        }

        logger.debug("订阅的UP主UID: ${followingUsers.joinToString()}")
        logger.debug("当前 lastLive = $lastLive")

        val liveList = client.getLive(source = "LiveCheckTasker.followed-live-list")
        if (liveList == null) {
            logger.warn("获取关注列表直播失败")
            return@withTimeout
        }

        val lives = liveList.rooms
            .filter {
                val isNew = it.liveTime > lastLive
                logger.debug("检查直播时间: ${it.uname} (${it.uid}), liveTime=${it.liveTime}, lastLive=$lastLive, isNew=$isNew")
                isNew
            }
            .filter {
                val isFollowing = followingUsers.contains(it.uid)
                logger.debug("检查是否已订阅: ${it.uname} (${it.uid}), isFollowing=$isFollowing")
                isFollowing
            }
            .sortedBy { it.liveTime }

        logger.debug("过滤后新开播的直播数: ${lives.size}")

        if (lives.isNotEmpty()) {
            logger.info("检测到 ${lives.size} 个新开播直播")
            lives.forEach {
                logger.info("新直播 ${it.uname} (${it.uid}) - ${it.title}")
            }

            lastLive = lives.last().liveTime
            logger.debug("更新 lastLive 为 $lastLive")

            logger.debug("发送 ${lives.size} 个直播到 liveChannel...")
            val details = lives.flatMap { live ->
                val businessId = "${live.uid}:${live.roomId}:${live.liveTime}"
                PushFanoutService.liveDetailsForContacts(live, PushFanoutService.resolveLiveContacts(live.uid, dynamic)).mapNotNull { detail ->
                    val contact = detail.contact ?: return@mapNotNull detail
                    val record = DeliveryCoordinator.discover(DeliveryKind.LIVE_OPEN, businessId, contact)
                    if (DeliveryCoordinator.isTerminal(record.id)) null else detail.copy(deliveryId = record.id)
                }
            }
            liveChannel.sendAll(details)
            logger.debug("直播已发送到 liveChannel")

            // 下播配对由 SendTasker 的开播成功回执建立，入队不再代表已通知。
        }
    }

    /**
     * 直播下播通知开关热重载后需要重新读取，避免保存成功但仍按旧开关记录 liveUsers。
     */
    override fun refreshRuntimeConfig() {
        liveCloseEnable = BiliConfigManager.config.enableConfig.liveCloseNotifyEnable
        super.refreshRuntimeConfig()
    }
}
