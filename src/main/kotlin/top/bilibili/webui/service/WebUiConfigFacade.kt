package top.bilibili.webui.service

import top.bilibili.BiliConfig
import top.bilibili.BiliConfigManager
import top.bilibili.BiliData
import top.bilibili.config.BotConfig
import top.bilibili.config.ConfigManager
import top.bilibili.webui.model.WebUiConfigFileDto
import top.bilibili.webui.model.WebUiSubscriptionListDto

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
        val snapshot = buildBiliConfigSnapshot(config)
        return buildConfigFileDto(
            sourceFile = "BiliConfig.yml",
            title = "BiliConfig",
            fields = snapshot.fields,
            rawSnapshot = snapshot.rawSnapshot,
        )
    }

    /**
     * `BiliData.yml` 视图当前只暴露系统维护字段和聚合计数，不返回可变订阅对象本身。
     */
    fun readBiliData(): WebUiConfigFileDto {
        val data = biliDataProvider()
        val snapshot = buildBiliDataSnapshot(data)
        return buildConfigFileDto(
            sourceFile = "BiliData.yml",
            title = "BiliData",
            fields = snapshot.fields,
            rawSnapshot = snapshot.rawSnapshot,
        )
    }

    /**
     * 订阅管理页读取卡片级聚合视图，避免浏览器端从完整字段树里推导业务关系。
     */
    fun readSubscriptions(): WebUiSubscriptionListDto {
        return buildSubscriptionOverview(biliDataProvider())
    }

    /**
     * `bot.yml` 视图保持平台与 WebUI 运行参数的只读快照，并对 token 做脱敏处理。
     */
    fun readBotConfig(): WebUiConfigFileDto {
        val config = botConfigProvider()
        val snapshot = buildBotConfigSnapshot(config)
        return buildConfigFileDto(
            sourceFile = "bot.yml",
            title = "BotConfig",
            fields = snapshot.fields,
            rawSnapshot = snapshot.rawSnapshot,
        )
    }

    /**
     * 统一把字段快照封装为文件 DTO，并基于最终响应内容生成乐观并发所需的 snapshot token。
     */
    private fun buildConfigFileDto(
        sourceFile: String,
        title: String,
        fields: List<top.bilibili.webui.model.WebUiConfigFieldDto>,
        rawSnapshot: Map<String, String>,
    ): WebUiConfigFileDto {
        return WebUiConfigFileDto(
            sourceFile = sourceFile,
            title = title,
            fields = fields,
            snapshotToken = computeWebUiSnapshotToken(sourceFile, title, rawSnapshot),
        )
    }
}
