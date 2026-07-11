package top.bilibili.delivery

import kotlinx.serialization.Serializable
import top.bilibili.data.BiliMessage
import top.bilibili.data.DynamicDetail
import top.bilibili.data.LiveDetail

/** 交付业务类型，直播开播与下播分别建账以保持配对状态。 */
@Serializable
enum class DeliveryKind { DYNAMIC, LIVE_OPEN, LIVE_CLOSE }

/** 联系人级交付阶段，只有平台成功回执能进入 DELIVERED。 */
@Serializable
enum class DeliveryStage { DISCOVERED, BUILD_QUEUED, BUILD_RETRY_WAIT, READY, RETRY_WAIT, DELIVERED, PERMANENT_FAILURE, INVALID }

/**
 * 单个“业务事件 × 联系人”的持久化交付记录。
 */
@Serializable
data class DeliveryRecord(
    val id: String,
    val kind: DeliveryKind,
    val businessId: String,
    val contact: String,
    val discoveredAtEpochMillis: Long,
    val stage: DeliveryStage = DeliveryStage.DISCOVERED,
    val attempts: Int = 0,
    val nextRetryAtEpochMillis: Long = 0L,
    val lastError: String? = null,
    val completedAtEpochMillis: Long = 0L,
    val message: BiliMessage? = null,
    val dynamicDetail: DynamicDetail? = null,
    val liveDetail: LiveDetail? = null,
    val pairedOpenDeliveryId: String? = null,
    val closeCompleted: Boolean = false,
)

/** 独立交付账本文件模型。 */
@Serializable
data class DeliveryLedger(
    val version: Int = 1,
    val records: MutableMap<String, DeliveryRecord> = linkedMapOf(),
    val legacyDynamicHistoryImported: Boolean = false,
)
