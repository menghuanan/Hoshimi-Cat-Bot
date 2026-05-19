package top.bilibili.webui.service

import top.bilibili.BiliAccountConfig
import top.bilibili.BiliConfig
import top.bilibili.BiliConfigManager
import top.bilibili.BiliData
import top.bilibili.TranslateConfig
import top.bilibili.config.BotConfig
import top.bilibili.config.ConfigManager
import top.bilibili.webui.model.WebUiConfigFieldDto
import top.bilibili.webui.model.WebUiConfigFileDto
import top.bilibili.webui.model.WebUiFieldCapability

/**
 * WebUI 配置 facade 负责把各配置文件的当前只读视图映射为独立 DTO，避免跨文件泄露边界。
 */
class WebUiConfigFacade(
    private val biliConfigProvider: () -> BiliConfig = { runCatching { BiliConfigManager.config }.getOrDefault(BiliConfig()) },
    private val biliDataProvider: () -> BiliData = { runCatching { BiliConfigManager.data }.getOrDefault(BiliData) },
    private val botConfigProvider: () -> BotConfig = { runCatching { ConfigManager.botConfig }.getOrDefault(BotConfig()) },
) {
    /**
     * `BiliConfig.yml` 视图只暴露只读快照和脱敏后的敏感字段。
     */
    fun readBiliConfig(): WebUiConfigFileDto {
        val config = biliConfigProvider()
        return WebUiConfigFileDto(
            sourceFile = "BiliConfig.yml",
            title = "BiliConfig",
            fields = listOf(
                readOnlyField("adminContact", "管理员联系人", config.normalizedAdminSubject().orEmpty()),
                maskedField("accountConfig.cookie", "登录 Cookie", config.accountConfig.cookie),
                readOnlyField("translateConfig.baidu.APP_ID", "Baidu APP_ID", config.translateConfig.baidu.APP_ID),
                maskedField("translateConfig.baidu.SECURITY_KEY", "Baidu SECURITY_KEY", config.translateConfig.baidu.SECURITY_KEY),
                readOnlyField("enableConfig.debugMode", "调试模式", config.enableConfig.debugMode.toString()),
            ),
        )
    }

    /**
     * `BiliData.yml` 视图当前只暴露系统维护字段和聚合计数，不返回可变订阅对象本身。
     */
    fun readBiliData(): WebUiConfigFileDto {
        val data = biliDataProvider()
        return WebUiConfigFileDto(
            sourceFile = "BiliData.yml",
            title = "BiliData",
            fields = listOf(
                systemField("dataVersion", "数据版本", data.dataVersion.toString()),
                systemField("dynamic.count", "订阅数量", data.dynamic.size.toString()),
                systemField("group.count", "分组数量", data.group.size.toString()),
            ),
        )
    }

    /**
     * `bot.yml` 视图保持平台与 WebUI 运行参数的只读快照，并对 token 做脱敏处理。
     */
    fun readBotConfig(): WebUiConfigFileDto {
        val config = botConfigProvider()
        val oneBot11 = config.selectedOneBot11Config()
        return WebUiConfigFileDto(
            sourceFile = "bot.yml",
            title = "BotConfig",
            fields = listOf(
                readOnlyField("platform.type", "平台类型", config.selectedPlatformType().name),
                readOnlyField("platform.adapter", "适配器", config.selectedAdapterKind().name),
                readOnlyField("platform.onebot11.host", "OneBot11 Host", oneBot11.host),
                readOnlyField("platform.onebot11.port", "OneBot11 Port", oneBot11.port.toString()),
                maskedField("platform.onebot11.token", "OneBot11 Token", oneBot11.token),
                readOnlyField("webui.enabled", "WebUI 启用", config.webui.enabled.toString()),
                readOnlyField("webui.credentialFile", "WebUI 凭据文件", config.webui.credentialFile),
                systemField("firstRunFlag", "首次运行标记", config.firstRunFlag.toString()),
            ),
        )
    }

    /**
     * 统一创建普通只读字段，避免 route 层自行拼 capability 语义。
     */
    private fun readOnlyField(key: String, label: String, value: String): WebUiConfigFieldDto {
        return WebUiConfigFieldDto(
            key = key,
            label = label,
            value = value,
            capability = WebUiFieldCapability.READ_ONLY,
            editable = false,
        )
    }

    /**
     * 脱敏字段统一改写值文本，确保前端无法通过 DTO 直接拿到原始 secret。
     */
    private fun maskedField(key: String, label: String, rawValue: String): WebUiConfigFieldDto {
        return WebUiConfigFieldDto(
            key = key,
            label = label,
            value = mask(rawValue),
            capability = WebUiFieldCapability.MASKED,
            editable = false,
        )
    }

    /**
     * 系统维护字段在 Phase 2 明确标记为不可编辑，为后续写接口建立能力边界。
     */
    private fun systemField(key: String, label: String, value: String): WebUiConfigFieldDto {
        return WebUiConfigFieldDto(
            key = key,
            label = label,
            value = value,
            capability = WebUiFieldCapability.SYSTEM_MANAGED,
            editable = false,
        )
    }

    /**
     * 空值保持为空文本，非空 secret 统一替换为固定掩码，避免泄露长度特征。
     */
    private fun mask(rawValue: String): String {
        return if (rawValue.isBlank()) "" else "******"
    }
}
