package top.bilibili.webui.service

import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import top.bilibili.BiliConfig
import top.bilibili.BiliData
import top.bilibili.BiliDataWrapper
import top.bilibili.config.BotConfig
import top.bilibili.webui.model.WebUiConfigFieldDto
import top.bilibili.webui.model.WebUiFieldCapability
import top.bilibili.utils.json
import top.bilibili.utils.md5
import java.util.LinkedHashMap

/**
 * WebUI 配置快照支持层统一负责字段展开、能力标注和 snapshot token 生成，避免 facade 重复处理序列化细节。
 */
internal data class WebUiConfigSnapshot(
    val fields: List<WebUiConfigFieldDto>,
    val rawSnapshot: Map<String, String>,
)

/**
 * `BiliConfig.yml` 的快照把整份配置递归展开，保留少量可编辑字段的写权限标记。
 */
internal fun buildBiliConfigSnapshot(config: BiliConfig): WebUiConfigSnapshot {
    return buildSnapshot(
        root = config,
        serializer = BiliConfig.serializer(),
        capabilityResolver = ::biliConfigCapability,
    )
}

/**
 * `BiliData.yml` 的快照通过 wrapper 展开当前持久化结构，包含模板策略和业务数据的完整只读视图。
 */
internal fun buildBiliDataSnapshot(data: BiliData): WebUiConfigSnapshot {
    val snapshot = buildSnapshot(
        root = BiliDataWrapper.from(data),
        serializer = BiliDataWrapper.serializer(),
        capabilityResolver = ::biliDataCapability,
    )
    val rawSnapshot = LinkedHashMap(snapshot.rawSnapshot)
    rawSnapshot["dynamic.count"] = data.dynamic.size.toString()
    rawSnapshot["group.count"] = data.group.size.toString()
    val fields = rawSnapshot.entries.map { (key, value) ->
        val capability = biliDataCapability(key)
        WebUiConfigFieldDto(
            key = key,
            label = labelFromKey(key),
            value = displayValue(key, value, capability),
            capability = capability,
            editable = capability == WebUiFieldCapability.EDITABLE || capability == WebUiFieldCapability.MASKED,
        )
    }
    return WebUiConfigSnapshot(fields = fields, rawSnapshot = rawSnapshot)
}

/**
 * `bot.yml` 的快照展开平台、targets、admins 和 WebUI 运行参数，保留当前允许写入的字段边界。
 */
internal fun buildBotConfigSnapshot(config: BotConfig): WebUiConfigSnapshot {
    return buildSnapshot(
        root = config,
        serializer = BotConfig.serializer(),
        capabilityResolver = ::botConfigCapability,
    )
}

/**
 * snapshot token 统一由原始快照值派生，避免脱敏后的显示文本让不同 secret 产生同一个并发版本号。
 */
internal fun computeWebUiSnapshotToken(
    sourceFile: String,
    title: String,
    rawSnapshot: Map<String, String>,
): String {
    val payload = WebUiConfigSnapshotTokenPayload(
        sourceFile = sourceFile,
        title = title,
        rawSnapshot = rawSnapshot,
    )
    return json.encodeToString(
        WebUiConfigSnapshotTokenPayload.serializer(),
        payload,
    ).md5()
}

/**
 * 统一的序列化展开入口先生成原始字段快照，再按字段能力转换成 WebUI 视图 DTO。
 */
private fun <T> buildSnapshot(
    root: T,
    serializer: KSerializer<T>,
    capabilityResolver: (String) -> WebUiFieldCapability,
): WebUiConfigSnapshot {
    val element = json.encodeToJsonElement(serializer, root)
    val rawSnapshot = LinkedHashMap<String, String>()
    flattenJsonElement(
        element = element,
        path = "",
        rawSnapshot = rawSnapshot,
    )
    val fields = rawSnapshot.entries.map { (key, value) ->
        val capability = capabilityResolver(key)
        WebUiConfigFieldDto(
            key = key,
            label = labelFromKey(key),
            value = displayValue(key, value, capability),
            capability = capability,
            editable = capability == WebUiFieldCapability.EDITABLE || capability == WebUiFieldCapability.MASKED,
        )
    }
    return WebUiConfigSnapshot(fields = fields, rawSnapshot = rawSnapshot)
}

/**
 * 递归展开 JsonElement，把对象、数组和叶子值统一转换成稳定的点分路径字段。
 */
private fun flattenJsonElement(
    element: JsonElement,
    path: String,
    rawSnapshot: LinkedHashMap<String, String>,
) {
    when (element) {
        is JsonObject -> {
            if (path.isNotBlank()) {
                rawSnapshot[path] = containerSummary(path, element)
            }
            if (element.isEmpty()) {
                return
            }
            element.entries
                .sortedBy { it.key }
                .forEach { (key, child) ->
                    val normalizedKey = normalizeSnapshotSegment(key)
                    val childPath = if (path.isBlank()) normalizedKey else "$path.$normalizedKey"
                    flattenJsonElement(child, childPath, rawSnapshot)
                }
        }
        is JsonArray -> {
            if (path.isNotBlank()) {
                rawSnapshot[path] = containerSummary(path, element)
            }
            if (element.isEmpty()) {
                return
            }
            element.forEachIndexed { index, child ->
                val childPath = if (path.isBlank()) index.toString() else "$path.$index"
                flattenJsonElement(child, childPath, rawSnapshot)
            }
        }
        is JsonPrimitive -> {
            rawSnapshot[path] = element.content
        }
    }
}

/**
 * 复杂容器默认保留 JSON 片段，特殊编辑字段再做更适合表单的定制格式化。
 */
private fun containerSummary(path: String, element: JsonElement): String {
    return when {
        path == "linkParseBlacklistContacts" && element is JsonArray -> {
            element.joinToString("\n") { item -> item.jsonPrimitive.content }
        }
        else -> element.toString()
    }
}

/**
 * 字段标签优先取最后一个路径段，再做简单的可读化处理，保持 overview 可扫读。
 */
private fun labelFromKey(key: String): String {
    val leaf = key.substringAfterLast('.')
    return leaf
        .replace('_', ' ')
        .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
        .split(' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { segment ->
            segment.lowercase().replaceFirstChar { char ->
                if (char.isLowerCase()) char.titlecase() else char.toString()
            }
        }
}

/**
 * 脱敏字段统一替换为固定掩码，避免把 secret 原文显示到 WebUI overview。
 */
private fun displayValue(
    key: String,
    value: String,
    capability: WebUiFieldCapability,
): String {
    return when {
        capability == WebUiFieldCapability.MASKED && value.isNotBlank() -> "******"
        key == "linkParseBlacklistContacts" -> value
        else -> value
    }
}

/**
 * `BiliConfig.yml` 的写入边界只开放当前 WebUI 已支持的安全编辑字段。
 */
private fun biliConfigCapability(key: String): WebUiFieldCapability {
    return when (key) {
        "adminContact",
        "translateConfig.baidu.APP_ID",
        "enableConfig.debugMode" -> WebUiFieldCapability.EDITABLE
        "accountConfig.cookie",
        "translateConfig.baidu.SECURITY_KEY",
        "platform.onebot11.token" -> WebUiFieldCapability.MASKED
        else -> WebUiFieldCapability.READ_ONLY
    }
}

/**
 * `BiliData.yml` 的写入边界当前只开放链接解析黑名单联系人集合，其他字段统一只读展示。
 */
private fun biliDataCapability(key: String): WebUiFieldCapability {
    return when (key) {
        "dataVersion" -> WebUiFieldCapability.SYSTEM_MANAGED
        "dynamic.count",
        "group.count" -> WebUiFieldCapability.SYSTEM_MANAGED
        "linkParseBlacklistContacts" -> WebUiFieldCapability.EDITABLE
        else -> WebUiFieldCapability.READ_ONLY
    }
}

/**
 * `bot.yml` 的写入边界只开放当前连接参数和平台选择，其余节点保持只读或系统维护。
 */
private fun botConfigCapability(key: String): WebUiFieldCapability {
    return when (key) {
        "platform.type",
        "platform.adapter",
        "platform.onebot11.host",
        "platform.onebot11.port" -> WebUiFieldCapability.EDITABLE
        "platform.onebot11.token" -> WebUiFieldCapability.MASKED
        "firstRunFlag" -> WebUiFieldCapability.SYSTEM_MANAGED
        else -> WebUiFieldCapability.READ_ONLY
    }
}

/**
 * 序列化字段名统一收敛到 WebUI 既有的 camelCase 路径，避免前端和测试同时感知底层 YAML 命名风格。
 */
private fun normalizeSnapshotSegment(segment: String): String {
    return when (segment) {
        "admin_contact" -> "adminContact"
        "first_run_flag" -> "firstRunFlag"
        "credential_file" -> "credentialFile"
        "token_ttl_seconds" -> "tokenTtlSeconds"
        "static_dir" -> "staticDir"
        "qq_official" -> "qqOfficial"
        "app_id" -> "appId"
        "app_secret" -> "appSecret"
        "bot_token" -> "botToken"
        "group_contact" -> "groupContact"
        "user_contacts" -> "userContacts"
        else -> segment
    }
}

/**
 * snapshot token 输入结构固定绑定文件名、标题和原始字段快照，避免把 token 自身递归纳入哈希输入。
 */
@kotlinx.serialization.Serializable
private data class WebUiConfigSnapshotTokenPayload(
    val sourceFile: String,
    val title: String,
    val rawSnapshot: Map<String, String>,
)
