package top.bilibili.tasker

import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.bilibili.AtAllType
import top.bilibili.BiliData
import top.bilibili.connector.OutgoingPart
import top.bilibili.connector.PlatformChatType
import top.bilibili.connector.PlatformContact
import top.bilibili.connector.PlatformType
import top.bilibili.data.LiveMessage
import top.bilibili.service.AtAllService
import top.bilibili.service.MessageGateway
import top.bilibili.service.MessageGatewayProvider

class SendTaskerAtAllCooldownTest {
    private val runtimeSubject = "group:10001"
    private val persistedSubject = "onebot11:group:10001"
    private val contact = PlatformContact(PlatformType.ONEBOT11, PlatformChatType.GROUP, "10001")

    @AfterTest
    fun cleanup() {
        MessageGatewayProvider.unregister()
        BiliData.atAll.clear()
        BiliData.atAllCooldownUntil.clear()
    }

    @Test
    fun `deliverQueuedMessage 应在带全体消息发送成功后记录冷却`() = runBlocking {
        val uid = 123456L
        val message = liveMessage(uid)
        BiliData.atAll[persistedSubject] = mutableMapOf(uid to mutableSetOf(AtAllType.LIVE))
        MessageGatewayProvider.register(FakeGateway(sendResults = listOf(true)))

        val delivered = SendTasker.deliverQueuedMessage(
            contact = contact,
            segments = listOf(OutgoingPart.atAll(), OutgoingPart.text("测试直播")),
            cooldownSubject = runtimeSubject,
            sourceMessage = message,
        )

        assertTrue(delivered)
        assertFalse(AtAllService.shouldAtAll(runtimeSubject, uid, message), "发送成功后应立即进入同类型冷却窗口")
    }

    @Test
    fun `deliverQueuedMessage 应在全体发送失败但普通降级成功时不记录冷却`() = runBlocking {
        val uid = 123456L
        val message = liveMessage(uid)
        BiliData.atAll[persistedSubject] = mutableMapOf(uid to mutableSetOf(AtAllType.LIVE))
        MessageGatewayProvider.register(FakeGateway(sendResults = listOf(false, true)))

        val delivered = SendTasker.deliverQueuedMessage(
            contact = contact,
            segments = listOf(OutgoingPart.atAll(), OutgoingPart.text("测试直播")),
            cooldownSubject = runtimeSubject,
            sourceMessage = message,
        )

        assertTrue(delivered, "降级重发成功后整体发送结果仍应视为成功")
        assertTrue(AtAllService.shouldAtAll(runtimeSubject, uid, message), "降级路径不应占用同类型 @全体 冷却窗口")
    }

    /**
     * 用固定返回序列模拟消息网关，确保发送侧测试只聚焦冷却记账分支。
     */
    private class FakeGateway(
        private val sendResults: List<Boolean>,
    ) : MessageGateway {
        private var sendCount = 0

        override suspend fun sendMessage(contact: PlatformContact, message: List<OutgoingPart>): Boolean {
            error("test should use guarded send path only")
        }

        override suspend fun sendMessageGuarded(contact: PlatformContact, message: List<OutgoingPart>): Boolean {
            val index = sendCount.coerceAtMost(sendResults.lastIndex)
            sendCount += 1
            return sendResults[index]
        }

        override suspend fun sendAdminMessage(message: String): Boolean = true
    }

    /**
     * 构造一个最小直播消息，供发送成功与降级路径共享。
     */
    private fun liveMessage(uid: Long): LiveMessage {
        return LiveMessage(
            rid = 1000L,
            mid = uid,
            name = "测试主播",
            time = "2026-05-20 18:00:00",
            timestamp = 1778992800,
            title = "测试直播",
            cover = "https://example.com/cover.jpg",
            area = "测试分区",
            link = "https://live.bilibili.com/1000",
            drawPath = null,
            contact = null,
        )
    }
}
