package top.bilibili.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import top.bilibili.connector.PlatformKnownGroup
import top.bilibili.connector.validPlatformGroupId
import top.bilibili.utils.json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 配置层独占群目录文件写入；独立于订阅 YAML，避免事件更新触发业务配置重载。 */
class PlatformGroupDirectoryStore(private val file: File = File("data/platform-groups.json")) {
    /** 缺失表示首次启用；损坏或未知版本必须报告且保留原文件，不能覆盖为默认数据。 */
    fun load(): List<PlatformKnownGroup> {
        if (!file.exists()) return emptyList()
        require(file.length() <= 16 * 1024 * 1024) { "群目录文件超过大小限制" }
        val document = json.decodeFromString<GroupDirectoryDocument>(file.readText(Charsets.UTF_8))
        require(document.version == 1) { "不支持的群目录版本" }
        validate(document.groups)
        return document.groups
    }

    /** 临时文件完成 UTF-8 编码和同步后原子替换，保留一个备份；失败不破坏现有目录。 */
    fun save(groups: List<PlatformKnownGroup>) {
        validate(groups)
        val parent = file.absoluteFile.parentFile
        Files.createDirectories(parent.toPath())
        val content = json.encodeToString(GroupDirectoryDocument(groups = groups)).toByteArray(Charsets.UTF_8)
        require(content.size <= 16 * 1024 * 1024) { "群目录文件超过大小限制" }
        val temp = Files.createTempFile(parent.toPath(), "platform-groups-", ".tmp")
        try {
            FileOutputStream(temp.toFile()).use { output -> output.write(content); output.fd.sync() }
            if (file.exists()) Files.copy(file.toPath(), File(file.path + ".bak").toPath(), StandardCopyOption.REPLACE_EXISTING)
            try {
                Files.move(temp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { Files.deleteIfExists(temp) }
    }

    /** 防止坏文件引入重复身份、无效 OpenID 或无界元数据。 */
    private fun validate(groups: List<PlatformKnownGroup>) {
        require(groups.size <= 10_000) { "群目录容量超限" }
        require(groups.all { it.accountId.isNotBlank() && it.accountId.length <= 256 && validPlatformGroupId(it.id) && it.name.length <= 256 && it.alias.length <= 80 }) { "群目录记录无效" }
        require(groups.map { it.accountId to it.id }.distinct().size == groups.size) { "群目录存在重复记录" }
    }
}

/** 独立版本只影响群目录文件，不改变 BiliData 的迁移版本。 */
@Serializable
private data class GroupDirectoryDocument(val version: Int = 1, val groups: List<PlatformKnownGroup> = emptyList())
