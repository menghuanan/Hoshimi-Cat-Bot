package top.bilibili.delivery

/** 平台发送结论的内部回执，不把 connector 供应商类型泄露到业务层。 */
data class DeliveryReceipt(
    val deliveryId: String,
    val success: Boolean,
    val error: String? = null,
    val occurredAtEpochMillis: Long = System.currentTimeMillis(),
)
