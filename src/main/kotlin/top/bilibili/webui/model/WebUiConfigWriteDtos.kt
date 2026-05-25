package top.bilibili.webui.model

import kotlinx.serialization.Serializable

/**
 * `BiliConfig.yml` 写请求保持文件级边界；空 secret 和 preserve 代理模式表示保留后端当前敏感配置。
 */
@Serializable
data class WebUiBiliConfigWriteRequestDto(
    val snapshotToken: String,
    val admin: Long = 0L,
    val adminContact: String = "",
    val debugMode: Boolean = false,
    val drawEnable: Boolean = true,
    val pushDrawEnable: Boolean = true,
    val notifyEnable: Boolean = true,
    val liveCloseNotifyEnable: Boolean = true,
    val lowSpeedEnable: Boolean = true,
    val translateEnable: Boolean = false,
    val proxyEnable: Boolean = false,
    val cacheClearEnable: Boolean = true,
    val cookie: String = "",
    val autoFollow: Boolean = true,
    val followGroup: String = "Bot关注",
    val proxies: List<String> = emptyList(),
    val proxyUpdateMode: String = "preserve",
    val lowSpeedTime: String = "22-8",
    val lowSpeedRange: String = "60-240",
    val normalRange: String = "30-120",
    val checkReportInterval: Int = 10,
    val timeout: Int = 10,
    val quality: String = "1000w",
    val theme: String = "v3",
    val font: String = "",
    val defaultColor: String = "#d3edfa",
    val cardOrnament: String = "FanCard",
    val timeDisplayMode: String = "ABSOLUTE",
    val hueStep: Int = 30,
    val lockSB: Boolean = true,
    val saturation: Float = 0.25f,
    val brightness: Float = 1.0f,
    val leftBadgeEnable: Boolean = true,
    val rightBadgeEnable: Boolean = false,
    val dynamicFooter: String = "",
    val liveFooter: String = "",
    val footerAlign: String = "LEFT",
    val downloadOriginal: Boolean = true,
    val cacheExpires: Map<String, Int> = emptyMap(),
    val messageInterval: Long = 100L,
    val pushInterval: Long = 500L,
    val toShortLink: Boolean = false,
    val defaultDynamicPush: String = "OneMsg",
    val defaultLivePush: String = "OneMsg",
    val defaultLiveClose: String = "SimpleMsg",
    val dynamicPush: Map<String, String> = emptyMap(),
    val livePush: Map<String, String> = emptyMap(),
    val liveClose: Map<String, String> = emptyMap(),
    val triggerMode: String = "At",
    val linkResolveDrawEnable: Boolean = true,
    val linkResolveReturnLink: Boolean = false,
    val cutLine: String = "\n\n〓〓〓 翻译 〓〓〓\n",
    val baiduAppId: String = "",
    val baiduSecurityKey: String = "",
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
 * 单个预置推送目标的 WebUI 写入 DTO，避免前端直接依赖平台配置内部可变集合。
 */
@Serializable
data class WebUiTargetConfigWriteDto(
    val type: String,
    val id: Long,
    val contact: String = "",
)

/**
 * 单个群管理员映射的 WebUI 写入 DTO，联系人字段由后端统一归一化和持久化。
 */
@Serializable
data class WebUiGroupAdminConfigWriteDto(
    val groupId: Long = 0L,
    val userIds: List<Long> = emptyList(),
    val groupContact: String = "",
    val userContacts: List<String> = emptyList(),
)

/**
 * `bot.yml` 写请求保持文件级独立边界；空 token/appSecret 值表示保留后端当前敏感配置。
 * message_format 缺省时由后端规范化，admins 缺省时表示本次保存不修改管理员配置。
 */
@Serializable
data class WebUiBotConfigWriteRequestDto(
    val snapshotToken: String,
    val platformType: String,
    val adapter: String,
    val oneBot11Host: String,
    val oneBot11Port: Int,
    val oneBot11Token: String = "",
    val oneBot11UseTls: Boolean = false,
    val oneBot11HeartbeatInterval: Long = 30000L,
    val oneBot11ReconnectInterval: Long = 5000L,
    val oneBot11MessageFormat: String? = null,
    val oneBot11SendMode: String = "base64",
    val oneBot11MaxReconnectAttempts: Int = -1,
    val oneBot11ConnectTimeout: Long = 10000L,
    val qqOfficialAppId: String = "",
    val qqOfficialAppSecret: String = "",
    val qqOfficialBotToken: String = "",
    val webUiEnabled: Boolean = false,
    val webUiHost: String = "127.0.0.1",
    val webUiPort: Int = 18080,
    val webUiCredentialFile: String = "webui-credentials.json",
    val webUiTokenTtlSeconds: Long = 3600L,
    val webUiStaticDir: String = "",
    val targets: List<WebUiTargetConfigWriteDto> = emptyList(),
    val admins: List<WebUiGroupAdminConfigWriteDto>? = null,
    val confirmationPassword: String = "",
)
