package top.bilibili.delivery

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import top.bilibili.data.BiliMessage
import top.bilibili.data.DynamicDetail
import top.bilibili.data.LiveDetail

/**
 * 联系人级交付状态机，统一推进构建、平台回执、重试预算和直播配对。
 */
object DeliveryCoordinator {
    /** 原子发现返回记录及是否本次创建，轮询据此避免复用记录重复入队。 */
    data class DiscoveryResult(val record: DeliveryRecord, val created: Boolean)
    private const val MAX_ATTEMPTS = 6
    private const val RETRY_WINDOW_MS = 24L * 60L * 60L * 1_000L
    private const val TERMINAL_RETENTION_MS = 7L * 24L * 60L * 60L * 1_000L
    private const val TERMINAL_CAPACITY = 5_000
    private const val CHANNEL_LEASE_MS = 5L * 60L * 1_000L
    private val retryDelays = longArrayOf(30_000L, 2 * 60_000L, 10 * 60_000L, 30 * 60_000L, 2 * 60 * 60_000L, 6 * 60 * 60_000L)
    private val lock = ReentrantLock()
    private var store = DeliveryLedgerStore()
    private var ledger = DeliveryLedger()
    @Volatile private var initialized = false

    /** 启动时加载账本并导入旧 dynamic_history 为完成历史，避免升级后重复推送。 */
    fun initialize() = lock.withLock {
        if (initialized) return
        ledger = store.load()
        // 崩溃前已构建但尚未收到平台回执的消息，重启后立即进入可重试状态。
        ledger.records.replaceAll { _, record ->
            if (record.stage == DeliveryStage.READY && record.message != null) {
                record.copy(stage = DeliveryStage.RETRY_WAIT, nextRetryAtEpochMillis = System.currentTimeMillis())
            } else if (record.stage == DeliveryStage.BUILD_QUEUED && (record.dynamicDetail != null || record.liveDetail != null)) {
                record.copy(stage = DeliveryStage.BUILD_RETRY_WAIT, nextRetryAtEpochMillis = System.currentTimeMillis())
            } else record
        }
        if (!ledger.legacyDynamicHistoryImported) importLegacyDynamicHistoryLocked()
        compactLocked(System.currentTimeMillis())
        store.save(ledger)
        initialized = true
    }

    /**
     * 为事件联系人创建稳定记录；已有记录直接复用以实现跨重启和轮询去重。
     */
    fun discover(kind: DeliveryKind, businessId: String, contact: String, now: Long = System.currentTimeMillis()): DeliveryRecord = lock.withLock {
        ensureInitializedLocked()
        val id = stableId(kind, businessId, contact)
        ledger.records[id]?.let { return it }
        val record = DeliveryRecord(id, kind, businessId, contact, now)
        ledger.records[id] = record
        store.save(ledger)
        record
    }

    /** 发现动态联系人时同步持久化构建输入，轮询游标前移后仍可独立恢复。 */
    fun discoverDynamic(businessId: String, detail: DynamicDetail, now: Long = System.currentTimeMillis()): DeliveryRecord = lock.withLock {
        val contact = requireNotNull(detail.contact) { "动态交付联系人不能为空" }
        discoverWithBuildInputLocked(DeliveryKind.DYNAMIC, businessId, contact, now, dynamicDetail = detail)
    }

    /** 发现开播联系人时同步持久化构建输入，channel 取消不会丢失事件。 */
    fun discoverLiveOpen(businessId: String, detail: LiveDetail, now: Long = System.currentTimeMillis()): DeliveryRecord = lock.withLock {
        val contact = requireNotNull(detail.contact) { "直播交付联系人不能为空" }
        discoverWithBuildInputLocked(DeliveryKind.LIVE_OPEN, businessId, contact, now, liveDetail = detail)
    }

    /** 构建输入放入 channel 前设置租约，进程内消费停滞也会在租约到期后恢复。 */
    fun markBuildQueued(deliveryId: String, now: Long = System.currentTimeMillis()): DeliveryRecord? = update(deliveryId) { record ->
        if (record.stage in setOf(DeliveryStage.DISCOVERED, DeliveryStage.BUILD_RETRY_WAIT, DeliveryStage.BUILD_QUEUED)) {
            record.copy(stage = DeliveryStage.BUILD_QUEUED, nextRetryAtEpochMillis = now + CHANNEL_LEASE_MS)
        } else record
    }

    /** 构建失败按统一次数和时间预算重试原始详情，不要求已生成消息快照。 */
    fun recordBuildFailure(deliveryId: String, error: String?, now: Long = System.currentTimeMillis()): DeliveryRecord? = update(deliveryId) { record ->
        scheduleFailure(record, error, now, DeliveryStage.BUILD_RETRY_WAIT)
    }

    /** 返回需要重新进入动态或直播构建 channel 的持久化输入。 */
    fun dueBuildRetries(now: Long = System.currentTimeMillis()): List<DeliveryRecord> = lock.withLock {
        ensureInitializedLocked()
        ledger.records.values.filter { record ->
            val dueStage = record.stage == DeliveryStage.DISCOVERED ||
                record.stage == DeliveryStage.BUILD_RETRY_WAIT && record.nextRetryAtEpochMillis <= now ||
                record.stage == DeliveryStage.BUILD_QUEUED && record.nextRetryAtEpochMillis <= now
            dueStage && (record.dynamicDetail != null || record.liveDetail != null)
        }
    }

    /** 构建成功后保存可重试消息快照，供发送失败或重启恢复重新入队。 */
    fun markReady(deliveryId: String, message: BiliMessage): DeliveryRecord? = update(deliveryId) { record ->
        record.copy(
            stage = DeliveryStage.READY,
            message = message,
            dynamicDetail = null,
            liveDetail = null,
            nextRetryAtEpochMillis = System.currentTimeMillis() + CHANNEL_LEASE_MS,
            lastError = null,
        )
    }

    /** 语义无效属于确定性终态，只隔离当前联系人记录。 */
    fun markInvalid(deliveryId: String, reason: String, now: Long = System.currentTimeMillis()): DeliveryRecord? = update(deliveryId) { record ->
        record.copy(stage = DeliveryStage.INVALID, lastError = reason, completedAtEpochMillis = now)
    }

    /** 平台回执是唯一交付终态推进入口；失败按次数与 24 小时双预算安排重试。 */
    fun recordReceipt(receipt: DeliveryReceipt): DeliveryRecord? = update(receipt.deliveryId) { record ->
        if (receipt.success) {
            record.copy(stage = DeliveryStage.DELIVERED, completedAtEpochMillis = receipt.occurredAtEpochMillis, lastError = null)
        } else {
            scheduleFailure(record, receipt.error, receipt.occurredAtEpochMillis, DeliveryStage.RETRY_WAIT)
        }
    }

    /** 返回到期且保留完整消息快照的记录，调用方成功入队后无需提前推进为成功。 */
    fun dueRetries(now: Long = System.currentTimeMillis()): List<DeliveryRecord> = lock.withLock {
        ensureInitializedLocked()
        ledger.records.values.filter { record ->
            record.stage in setOf(DeliveryStage.RETRY_WAIT, DeliveryStage.READY) &&
                record.nextRetryAtEpochMillis <= now && record.message != null
        }
    }

    /** 终态记录不应被上游轮询重复入队。 */
    fun isTerminal(deliveryId: String): Boolean = lock.withLock {
        ensureInitializedLocked()
        ledger.records[deliveryId]?.stage in setOf(DeliveryStage.DELIVERED, DeliveryStage.PERMANENT_FAILURE, DeliveryStage.INVALID)
    }

    /** 旧 history 导入记录按动态业务 ID 去重，升级后不得为真实联系人重新发送历史动态。 */
    fun isLegacyDynamicCompleted(businessId: String): Boolean = lock.withLock {
        ensureInitializedLocked()
        ledger.records.values.any { record ->
            record.kind == DeliveryKind.DYNAMIC && record.businessId == businessId &&
                record.contact == "legacy-history" && record.stage == DeliveryStage.DELIVERED
        }
    }

    /** 只有刚发现且尚未构建的记录允许业务轮询生成消息，避免重复入队或终态回退。 */
    fun requiresInitialBuild(deliveryId: String): Boolean = lock.withLock {
        ensureInitializedLocked()
        ledger.records[deliveryId]?.stage == DeliveryStage.DISCOVERED
    }

    /** 重试消息成功放回 channel 后先离开到期集合，后续仍由平台回执决定最终状态。 */
    fun markRetryQueued(deliveryId: String, now: Long = System.currentTimeMillis()): DeliveryRecord? = update(deliveryId) { record ->
        record.copy(stage = DeliveryStage.READY, nextRetryAtEpochMillis = now + CHANNEL_LEASE_MS)
    }

    /** 返回已成功收到开播通知、尚未完成下播配对的联系人记录。 */
    fun deliveredLiveOpenRecords(): List<DeliveryRecord> = lock.withLock {
        ensureInitializedLocked()
        ledger.records.values.filter { it.kind == DeliveryKind.LIVE_OPEN && it.stage == DeliveryStage.DELIVERED && !it.closeCompleted }
    }

    /** 下播记录成功后闭合对应开播联系人配对，失败时保持配对供后续重试。 */
    fun completeLivePair(closeDeliveryId: String): Boolean = lock.withLock {
        ensureInitializedLocked()
        val closeRecord = ledger.records[closeDeliveryId] ?: return false
        val openId = closeRecord.pairedOpenDeliveryId ?: return false
        val openRecord = ledger.records[openId] ?: return false
        ledger.records[openId] = openRecord.copy(closeCompleted = true)
        store.save(ledger)
        true
    }

    /** 创建与已交付开播记录绑定的联系人级下播记录。 */
    fun discoverLiveClose(openRecord: DeliveryRecord, businessId: String, now: Long = System.currentTimeMillis()): DeliveryRecord = lock.withLock {
        ensureInitializedLocked()
        val id = stableId(DeliveryKind.LIVE_CLOSE, businessId, openRecord.contact)
        ledger.records[id]?.let { return it }
        val record = DeliveryRecord(
            id = id,
            kind = DeliveryKind.LIVE_CLOSE,
            businessId = businessId,
            contact = openRecord.contact,
            discoveredAtEpochMillis = now,
            pairedOpenDeliveryId = openRecord.id,
        )
        ledger.records[id] = record
        store.save(ledger)
        record
    }

    /** 下播消息在同一账本事务中创建记录和 READY 快照，消除 DISCOVERED 到 markReady 之间的崩溃窗口。 */
    fun discoverReadyLiveClose(
        openRecord: DeliveryRecord,
        businessId: String,
        now: Long = System.currentTimeMillis(),
        buildMessage: (String) -> BiliMessage,
    ): DiscoveryResult = lock.withLock {
        ensureInitializedLocked()
        val id = stableId(DeliveryKind.LIVE_CLOSE, businessId, openRecord.contact)
        ledger.records[id]?.let { return DiscoveryResult(it, created = false) }
        val record = DeliveryRecord(
            id = id,
            kind = DeliveryKind.LIVE_CLOSE,
            businessId = businessId,
            contact = openRecord.contact,
            discoveredAtEpochMillis = now,
            stage = DeliveryStage.READY,
            nextRetryAtEpochMillis = now + CHANNEL_LEASE_MS,
            message = buildMessage(id),
            pairedOpenDeliveryId = openRecord.id,
        )
        ledger.records[id] = record
        store.save(ledger)
        DiscoveryResult(record, created = true)
    }

    /** 停机检查点显式保存当前账本，确保 worker 回收前状态已落盘。 */
    fun flush() = lock.withLock {
        ensureInitializedLocked()
        compactLocked(System.currentTimeMillis())
        store.save(ledger)
    }

    /** 测试隔离入口替换临时存储并清空内存态，生产代码不会调用。 */
    internal fun resetForTest(testStore: DeliveryLedgerStore) = lock.withLock {
        store = testStore
        ledger = DeliveryLedger()
        initialized = false
    }

    /** 锁内更新单条记录并同步原子落盘。 */
    private fun update(deliveryId: String, transform: (DeliveryRecord) -> DeliveryRecord): DeliveryRecord? = lock.withLock {
        ensureInitializedLocked()
        val current = ledger.records[deliveryId] ?: return null
        val updated = transform(current)
        ledger.records[deliveryId] = updated
        compactLocked(System.currentTimeMillis())
        store.save(ledger)
        updated
    }

    /** 锁内创建带构建输入的稳定记录；重复发现只复用原记录，避免覆盖正在推进的阶段。 */
    private fun discoverWithBuildInputLocked(
        kind: DeliveryKind,
        businessId: String,
        contact: String,
        now: Long,
        dynamicDetail: DynamicDetail? = null,
        liveDetail: LiveDetail? = null,
    ): DeliveryRecord {
        ensureInitializedLocked()
        val id = stableId(kind, businessId, contact)
        ledger.records[id]?.let { existing ->
            // 兼容上一版只写 DISCOVERED 而未保存构建输入的账本，重复轮询时原位补齐恢复材料。
            if (existing.stage == DeliveryStage.DISCOVERED && existing.dynamicDetail == null && existing.liveDetail == null) {
                val repaired = existing.copy(
                    dynamicDetail = dynamicDetail?.copy(deliveryId = id),
                    liveDetail = liveDetail?.copy(deliveryId = id),
                )
                ledger.records[id] = repaired
                store.save(ledger)
                return repaired
            }
            return existing
        }
        val record = DeliveryRecord(
            id = id,
            kind = kind,
            businessId = businessId,
            contact = contact,
            discoveredAtEpochMillis = now,
            dynamicDetail = dynamicDetail?.copy(deliveryId = id),
            liveDetail = liveDetail?.copy(deliveryId = id),
        )
        ledger.records[id] = record
        store.save(ledger)
        return record
    }

    /** 构建与平台失败共享 6 次/24 小时预算，但分别回到对应重试阶段。 */
    private fun scheduleFailure(record: DeliveryRecord, error: String?, now: Long, retryStage: DeliveryStage): DeliveryRecord {
        val nextAttempts = record.attempts + 1
        val exhausted = nextAttempts >= MAX_ATTEMPTS || now - record.discoveredAtEpochMillis >= RETRY_WINDOW_MS
        if (exhausted) {
            return record.copy(
                stage = DeliveryStage.PERMANENT_FAILURE,
                attempts = nextAttempts,
                completedAtEpochMillis = now,
                lastError = error,
            )
        }
        val baseDelay = retryDelays[(nextAttempts - 1).coerceIn(retryDelays.indices)]
        val jitter = stableJitter(record.id, baseDelay / 5L)
        return record.copy(
            stage = retryStage,
            attempts = nextAttempts,
            nextRetryAtEpochMillis = now + baseDelay + jitter,
            lastError = error,
        )
    }

    /** 旧历史按特殊联系人建立完成记录，只承担升级去重，不参与实际发送。 */
    private fun importLegacyDynamicHistoryLocked() {
        val history = store.legacyHistoryFile
        if (history.exists()) {
            history.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.forEach { did ->
                val id = stableId(DeliveryKind.DYNAMIC, did, "legacy-history")
                ledger.records.putIfAbsent(
                    id,
                    DeliveryRecord(
                        id = id,
                        kind = DeliveryKind.DYNAMIC,
                        businessId = did,
                        contact = "legacy-history",
                        discoveredAtEpochMillis = 0L,
                        stage = DeliveryStage.DELIVERED,
                        completedAtEpochMillis = System.currentTimeMillis(),
                    ),
                )
            }
        }
        ledger = ledger.copy(legacyDynamicHistoryImported = true)
    }

    /** 终态按七天和容量压缩，活跃记录不参与淘汰。 */
    private fun compactLocked(now: Long) {
        compactCompletedDynamicHistoryLocked()
        val terminalStages = setOf(DeliveryStage.DELIVERED, DeliveryStage.PERMANENT_FAILURE, DeliveryStage.INVALID)
        ledger.records.values.filter { it.stage in terminalStages && it.completedAtEpochMillis > 0L && now - it.completedAtEpochMillis >= TERMINAL_RETENTION_MS }
            .forEach { ledger.records.remove(it.id) }
        val terminal = ledger.records.values.filter { it.stage in terminalStages }.sortedBy { it.completedAtEpochMillis }
        terminal.dropLast(TERMINAL_CAPACITY).forEach { ledger.records.remove(it.id) }
    }

    /** 同一动态的已发现联系人全部终态后写回有界兼容历史，账本压缩后仍能避免升级回流重复。 */
    private fun compactCompletedDynamicHistoryLocked() {
        val terminalStages = setOf(DeliveryStage.DELIVERED, DeliveryStage.PERMANENT_FAILURE, DeliveryStage.INVALID)
        val completedBusinessIds = ledger.records.values
            .filter { it.kind == DeliveryKind.DYNAMIC && it.contact != "legacy-history" }
            .groupBy { it.businessId }
            .filterValues { records -> records.isNotEmpty() && records.all { it.stage in terminalStages } }
            .keys
        if (completedBusinessIds.isEmpty()) return

        val historyFile = store.legacyHistoryFile
        // 兼容历史只是旧版本去重副本，写入失败不得阻断权威账本的终态回执持久化。
        runCatching {
            historyFile.parentFile?.mkdirs()
            val retained = if (historyFile.exists()) historyFile.readLines(Charsets.UTF_8).filter { it.isNotBlank() } else emptyList()
            val merged = (retained + completedBusinessIds).distinct().takeLast(200)
            historyFile.writeText(merged.joinToString("\n"), Charsets.UTF_8)
        }
    }

    /** 调用公开操作前保证只加载一次持久化账本。 */
    private fun ensureInitializedLocked() {
        if (!initialized) initialize()
    }

    /** ID 使用业务类型、业务 ID 与规范联系人哈希，避免把联系人明文写入文件键。 */
    private fun stableId(kind: DeliveryKind, businessId: String, contact: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest("${kind.name}|$businessId|$contact".toByteArray())
        return bytes.take(16).joinToString("") { "%02x".format(it) }
    }

    /** 稳定抖动让跨重启的重试计划保持可预测，同时避免同批记录完全同刻回流。 */
    private fun stableJitter(id: String, bound: Long): Long = if (bound <= 0L) 0L else (id.hashCode().toLong() and Long.MAX_VALUE) % bound
}
