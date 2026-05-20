package top.bilibili.webui.model

import kotlinx.serialization.Serializable

/**
 * `BiliConfig.yml` 写请求只暴露当前管理页允许编辑的字段，并要求客户端携带快照 token。
 */
@Serializable
data class WebUiBiliConfigWriteRequestDto(
    val snapshotToken: String,
    val adminContact: String,
    val cookie: String,
    val baiduAppId: String,
    val baiduSecurityKey: String,
    val debugMode: Boolean,
    val confirmationPassword: String = "",
)

/**
 * `BiliData.yml` 写请求当前聚焦可安全编辑的黑名单联系人集合，避免前端直接传可变内部对象。
 */
@Serializable
data class WebUiBiliDataWriteRequestDto(
    val snapshotToken: String,
    val linkParseBlacklistContacts: List<String>,
    val confirmationPassword: String = "",
)

/**
 * `bot.yml` 写请求保持文件级独立边界，只描述平台连接参数与本地管理页允许修改的字段。
 */
@Serializable
data class WebUiBotConfigWriteRequestDto(
    val snapshotToken: String,
    val platformType: String,
    val adapter: String,
    val oneBot11Host: String,
    val oneBot11Port: Int,
    val oneBot11Token: String,
    val confirmationPassword: String = "",
)
