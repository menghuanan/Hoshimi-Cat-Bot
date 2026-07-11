package top.bilibili.delivery

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.bilibili.data.DynamicMessage
import top.bilibili.data.DynamicDetail
import top.bilibili.data.DynamicItem
import top.bilibili.data.DynamicType
import top.bilibili.data.ModuleAuthor
import top.bilibili.data.ModuleDynamic
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

    /** 初次入构建 channel 失败时，账本必须保留构建输入并立即暴露给重试扫描。 */
    @Test
    fun `discovered dynamic should retain build input until build channel accepts it`() {
        val detail = dynamicDetail("onebot11:group:400")
        val record = DeliveryCoordinator.discoverDynamic("did-build-1", detail, now = 10_000L)

        val due = DeliveryCoordinator.dueBuildRetries(now = 10_000L).single { it.id == record.id }
        assertEquals(detail.copy(deliveryId = record.id), due.dynamicDetail)
        assertEquals(null, due.message)
    }

    /** 构建异常使用独立预算重试原始详情，不能依赖尚未生成的 BiliMessage。 */
    @Test
    fun `retryable build failure should remain retryable without message snapshot`() {
        val detail = dynamicDetail("onebot11:group:500")
        val record = DeliveryCoordinator.discoverDynamic("did-build-2", detail, now = 20_000L)
        DeliveryCoordinator.markBuildQueued(record.id, now = 20_000L)

        val failed = DeliveryCoordinator.recordBuildFailure(record.id, "render failed", now = 21_000L)

        assertNotNull(failed)
        assertEquals(DeliveryStage.BUILD_RETRY_WAIT, failed.stage)
        assertTrue(DeliveryCoordinator.dueBuildRetries(failed.nextRetryAtEpochMillis).any { it.id == record.id })
    }

    /** 构建 channel 租约到期前不重复，超时后自动恢复，避免消费者退出造成永久卡死。 */
    @Test
    fun `build queue lease should suppress duplicates and recover after expiry`() {
        val record = DeliveryCoordinator.discoverDynamic("did-build-3", dynamicDetail("onebot11:group:600"), now = 30_000L)
        val queued = DeliveryCoordinator.markBuildQueued(record.id, now = 30_000L)

        assertNotNull(queued)
        assertTrue(DeliveryCoordinator.dueBuildRetries(queued.nextRetryAtEpochMillis - 1L).none { it.id == record.id })
        assertTrue(DeliveryCoordinator.dueBuildRetries(queued.nextRetryAtEpochMillis).any { it.id == record.id })
    }

    /** READY 消息在发送 channel 未完成时也使用租约恢复，不依赖平台失败回执。 */
    @Test
    fun `ready message should become retryable when send channel lease expires`() {
        val record = DeliveryCoordinator.discover(DeliveryKind.DYNAMIC, "did-send-lease", "onebot11:group:700", now = System.currentTimeMillis())
        val message = DynamicMessage(
            did = "did-send-lease", mid = 7L, name = "name", type = DynamicType.DYNAMIC_TYPE_WORD,
            time = "now", timestamp = 1, content = "content", images = emptyList(), links = emptyList(),
            contact = record.contact, deliveryId = record.id,
        )
        val ready = DeliveryCoordinator.markReady(record.id, message)

        assertNotNull(ready)
        assertTrue(DeliveryCoordinator.dueRetries(ready.nextRetryAtEpochMillis - 1L).none { it.id == record.id })
        assertTrue(DeliveryCoordinator.dueRetries(ready.nextRetryAtEpochMillis).any { it.id == record.id })
    }

    /** 发送重试成功回投后续租 READY 租约，租约到期前不得每轮重复入队。 */
    @Test
    fun `queued send retry should renew ready lease`() {
        val record = DeliveryCoordinator.discover(DeliveryKind.DYNAMIC, "did-retry-lease", "onebot11:group:750", now = 1_000L)
        val message = DynamicMessage(
            did = "did-retry-lease", mid = 7L, name = "name", type = DynamicType.DYNAMIC_TYPE_WORD,
            time = "now", timestamp = 1, content = "content", images = emptyList(), links = emptyList(),
            contact = record.contact, deliveryId = record.id,
        )
        DeliveryCoordinator.markReady(record.id, message)
        val failed = requireNotNull(DeliveryCoordinator.recordReceipt(DeliveryReceipt(record.id, false, "network", 2_000L)))
        val queued = requireNotNull(DeliveryCoordinator.markRetryQueued(record.id, now = failed.nextRetryAtEpochMillis))

        assertTrue(DeliveryCoordinator.dueRetries(queued.nextRetryAtEpochMillis - 1L).none { it.id == record.id })
        assertTrue(DeliveryCoordinator.dueRetries(queued.nextRetryAtEpochMillis).any { it.id == record.id })
    }

    /** 重启后旧构建租约立即失效，持久化详情无需等待五分钟即可恢复。 */
    @Test
    fun `restart should immediately recover persisted build queue lease`() {
        val record = DeliveryCoordinator.discoverDynamic("did-restart", dynamicDetail("onebot11:group:800"))
        DeliveryCoordinator.markBuildQueued(record.id)

        DeliveryCoordinator.resetForTest(store)
        DeliveryCoordinator.initialize()

        assertTrue(DeliveryCoordinator.dueBuildRetries().any { it.id == record.id })
    }

    /** 构建与发送失败共享六次预算，第六次必须进入永久失败并停止重试。 */
    @Test
    fun `sixth build failure should exhaust shared delivery budget`() {
        val record = DeliveryCoordinator.discoverDynamic("did-exhaust", dynamicDetail("onebot11:group:900"), now = 100_000L)
        var failed = record
        repeat(6) { attempt ->
            failed = requireNotNull(DeliveryCoordinator.recordBuildFailure(record.id, "failure-$attempt", now = 101_000L + attempt))
        }

        assertEquals(DeliveryStage.PERMANENT_FAILURE, failed.stage)
        assertTrue(DeliveryCoordinator.dueBuildRetries(Long.MAX_VALUE).none { it.id == record.id })
    }

    /** 上一版无构建详情的 DISCOVERED 记录在重复发现时必须原位补齐而非永久卡死。 */
    @Test
    fun `legacy discovered record should be repaired with build input`() {
        val legacy = DeliveryCoordinator.discover(DeliveryKind.DYNAMIC, "did-legacy-discovered", "onebot11:group:1000")

        val repaired = DeliveryCoordinator.discoverDynamic(
            "did-legacy-discovered",
            dynamicDetail("onebot11:group:1000"),
        )

        assertEquals(legacy.id, repaired.id)
        assertNotNull(repaired.dynamicDetail)
        assertTrue(DeliveryCoordinator.dueBuildRetries().any { it.id == legacy.id })
    }

    /** 同一动态的全部联系人终态后写回有界兼容历史，部分终态时不得提前提交。 */
    @Test
    fun `dynamic history should commit only after every discovered contact is terminal`() {
        val first = DeliveryCoordinator.discoverDynamic("did-history", dynamicDetail("onebot11:group:1100"))
        val second = DeliveryCoordinator.discoverDynamic("did-history", dynamicDetail("onebot11:group:1200"))

        DeliveryCoordinator.markInvalid(first.id, "invalid-one")
        assertTrue(!store.legacyHistoryFile.exists() || "did-history" !in store.legacyHistoryFile.readLines())
        DeliveryCoordinator.markInvalid(second.id, "invalid-two")

        assertTrue("did-history" in store.legacyHistoryFile.readLines())
    }

    /** 构造最小可序列化动态输入，测试只关注账本的构建阶段持久化。 */
    private fun dynamicDetail(contact: String): DynamicDetail = DynamicDetail(
        item = DynamicItem(
            typeStr = "DYNAMIC_TYPE_WORD",
            basic = DynamicItem.DynamicBasic(commentIdStr = "1", commentType = 11, ridStr = "1"),
            idStr = "did-fixture",
            modules = DynamicItem.Modules(
                moduleAuthor = ModuleAuthor(mid = 1L, name = "up", face = "https://example.com/avatar.png"),
                moduleDynamic = ModuleDynamic(),
            ),
        ),
        contact = contact,
    )
}
