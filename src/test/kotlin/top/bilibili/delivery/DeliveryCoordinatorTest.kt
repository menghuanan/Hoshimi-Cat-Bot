package top.bilibili.delivery

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.bilibili.data.DynamicMessage
import top.bilibili.data.DynamicType
import top.bilibili.data.LiveMessage

class DeliveryCoordinatorTest {
    private lateinit var store: DeliveryLedgerStore

    /** 每个用例使用独立账本文件，避免测试污染真实 data 目录。 */
    @BeforeTest
    fun setup() {
        val file = Files.createTempDirectory("delivery-ledger").resolve("delivery-ledger.json").toFile()
        store = DeliveryLedgerStore(file)
        DeliveryCoordinator.resetForTest(store)
        DeliveryCoordinator.initialize()
    }

    /** 测试结束恢复另一个临时空 store，避免对象状态泄漏到后续测试。 */
    @AfterTest
    fun cleanup() {
        val file = Files.createTempDirectory("delivery-ledger-cleanup").resolve("delivery-ledger.json").toFile()
        DeliveryCoordinator.resetForTest(DeliveryLedgerStore(file))
    }

    /** 联系人级 ID 稳定且平台成功回执才进入 DELIVERED。 */
    @Test
    fun `delivery should only complete after successful platform receipt`() {
        val first = DeliveryCoordinator.discover(DeliveryKind.DYNAMIC, "did-1", "onebot11:group:100", now = 1_000L)
        val duplicate = DeliveryCoordinator.discover(DeliveryKind.DYNAMIC, "did-1", "onebot11:group:100", now = 2_000L)
        assertEquals(first.id, duplicate.id)

        val message = DynamicMessage(
            did = "did-1", mid = 1L, name = "name", type = DynamicType.DYNAMIC_TYPE_WORD,
            time = "now", timestamp = 1, content = "content", images = emptyList(), links = emptyList(),
            contact = first.contact, deliveryId = first.id,
        )
        assertEquals(DeliveryStage.READY, DeliveryCoordinator.markReady(first.id, message)?.stage)
        assertEquals(DeliveryStage.DELIVERED, DeliveryCoordinator.recordReceipt(DeliveryReceipt(first.id, true, occurredAtEpochMillis = 3_000L))?.stage)
    }

    /** 发送失败保留消息快照并在到期后返回重试，不提前标记成功。 */
    @Test
    fun `failed delivery should remain retryable with message snapshot`() {
        val record = DeliveryCoordinator.discover(DeliveryKind.DYNAMIC, "did-2", "onebot11:group:200", now = 1_000L)
        val message = DynamicMessage(
            did = "did-2", mid = 2L, name = "name", type = DynamicType.DYNAMIC_TYPE_WORD,
            time = "now", timestamp = 1, content = "content", images = emptyList(), links = emptyList(),
            contact = record.contact, deliveryId = record.id,
        )
        DeliveryCoordinator.markReady(record.id, message)
        val failed = DeliveryCoordinator.recordReceipt(DeliveryReceipt(record.id, false, "network", 2_000L))

        assertNotNull(failed)
        assertEquals(DeliveryStage.RETRY_WAIT, failed.stage)
        assertTrue(DeliveryCoordinator.dueRetries(failed.nextRetryAtEpochMillis).any { it.id == record.id })
    }

    /** 下播记录只允许首次构建，平台成功后闭合开播配对且不能被轮询重新激活。 */
    @Test
    fun `live close should preserve pairing until receipt and never rebuild terminal record`() {
        val now = System.currentTimeMillis()
        val open = DeliveryCoordinator.discover(DeliveryKind.LIVE_OPEN, "room-1:1000", "onebot11:group:300", now = now)
        val openMessage = LiveMessage(
            rid = 1L, mid = 2L, name = "anchor", time = "now", timestamp = 1,
            title = "title", cover = "", area = "area", link = "https://live.bilibili.com/1",
            contact = open.contact, deliveryId = open.id,
        )
        DeliveryCoordinator.markReady(open.id, openMessage)
        DeliveryCoordinator.recordReceipt(DeliveryReceipt(open.id, true, occurredAtEpochMillis = now + 1_000L))

        val deliveredOpen = DeliveryCoordinator.deliveredLiveOpenRecords().single { it.id == open.id }
        val close = DeliveryCoordinator.discoverLiveClose(deliveredOpen, "room-1:1000:close", now = now + 2_000L)
        assertTrue(DeliveryCoordinator.requiresInitialBuild(close.id))
        DeliveryCoordinator.markInvalid(close.id, "invalid close", now = now + 3_000L)

        assertTrue(!DeliveryCoordinator.requiresInitialBuild(close.id))
        assertTrue(DeliveryCoordinator.deliveredLiveOpenRecords().any { it.id == open.id })
    }
}
