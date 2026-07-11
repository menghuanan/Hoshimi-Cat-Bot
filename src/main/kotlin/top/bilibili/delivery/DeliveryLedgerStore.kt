package top.bilibili.delivery

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.encodeToString
import top.bilibili.utils.json
import kotlinx.serialization.json.Json

/**
 * `data/delivery-ledger.json` 的唯一文件边界，负责验证、备份和原子替换。
 */
class DeliveryLedgerStore(
    private val file: File = File("data/delivery-ledger.json"),
) {
    private val backupFile = File(file.parentFile, "${file.name}.bak")
    // 账本消息多态判别字段避开 DynamicMessage.type，防止 sealed 序列化字段冲突。
    private val ledgerJson = Json(json) {
        classDiscriminator = "message_type"
    }

    /** 文件缺失时返回空账本；已有文件损坏时保留原文件并抛出诊断错误。 */
    fun load(): DeliveryLedger {
        if (!file.exists()) return DeliveryLedger()
        return try {
            ledgerJson.decodeFromString(DeliveryLedger.serializer(), file.readText(StandardCharsets.UTF_8))
        } catch (error: Exception) {
            throw IllegalStateException("交付账本损坏，原文件保持不变: ${file.absolutePath}", error)
        }
    }

    /** 候选编码后重新解码验证，再轮换一份备份并原子替换。 */
    fun save(ledger: DeliveryLedger) {
        file.parentFile?.mkdirs()
        val encoded = ledgerJson.encodeToString(ledger)
        ledgerJson.decodeFromString(DeliveryLedger.serializer(), encoded)
        val tempFile = File.createTempFile("delivery-ledger", ".tmp", file.parentFile)
        try {
            tempFile.writeText(encoded, StandardCharsets.UTF_8)
            if (file.exists()) Files.copy(file.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            try {
                Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }
}
