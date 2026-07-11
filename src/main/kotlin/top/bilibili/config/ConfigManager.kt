package top.bilibili.config

import org.slf4j.LoggerFactory
import top.bilibili.core.deepCopyForRuntimeSnapshot
import java.io.File

/**
 * 统一管理 `bot.yml` 的加载、保存与重载流程。
 */
object ConfigManager {
    private val logger = LoggerFactory.getLogger(ConfigManager::class.java)

    private val configDir = File("config")
    // bot.yml 只负责平台接入配置；旧业务配置与运行数据仍由 BiliConfigManager 管理。
    private val store = BotConfigFileStore(configDir)

    lateinit var botConfig: BotConfig
        private set

    /**
     * 初始化运行期配置，并在必要时创建或迁移 `bot.yml`。
     */
    fun init() {
        if (!configDir.exists()) {
            configDir.mkdirs()
            logger.info("创建配置目录: ${configDir.absolutePath}")
        }

        try {
            // 仅缺失文件允许生成默认配置；已有文件解析失败必须保留现场并拒绝启动。
            val loadResult = store.loadWithMetadata()
            botConfig = loadResult.config
            if (loadResult.createdDefault) {
                logger.info("配置文件不存在，创建默认配置")
            } else {
                logger.info("成功加载配置文件: bot.yml")
            }
            if (loadResult.rewritten) {
                logger.info("检测到旧版 bot.yml 结构，已自动迁移为 v1.8 标准配置样式")
            }
        } catch (e: Exception) {
            val backupHint = store.latestBackupFile()?.absolutePath ?: "无可用备份"
            val message = "bot.yml 已存在但无法解析，原文件保持不变: ${store.configFile().absolutePath}; " +
                "请检查文件或从最近备份人工恢复: $backupHint"
            logger.error(message, e)
            throw IllegalStateException(message, e)
        }

        if (!botConfig.validateSelectedPlatform()) {
            logger.warn("当前平台配置无效，请检查 config/bot.yml 中的 ${botConfig.selectedPlatformType()}")
        }
    }

    /**
     * 将当前运行期配置规范化后保存到磁盘，并返回真实落盘结果。
     */
    fun saveConfig(): Boolean {
        return saveConfig(botConfig)
    }

    /**
     * 将指定运行期配置规范化后保存到磁盘，供外部 owner 入口统一写回 `bot.yml`。
     * 只有磁盘写入成功后才替换运行态快照，避免出现“内存已更新、文件未提交”的半提交状态。
     */
    fun saveConfig(configToSave: BotConfig): Boolean {
        return try {
            val normalizedConfig = configToSave.normalizedBotConfig()
            store.save(normalizedConfig)
            botConfig = normalizedConfig
            logger.info("配置已保存到: ${File(configDir, "bot.yml").absolutePath}")
            true
        } catch (e: Exception) {
            logger.error("保存配置失败: ${e.message}", e)
            false
        }
    }

    /**
     * 只把候选 bot.yml 写入磁盘，不替换 ConfigManager.botConfig。
     */
    fun persistConfigSnapshot(configSnapshot: BotConfig): Boolean {
        return try {
            store.save(configSnapshot.normalizedBotConfig())
            logger.info("候选平台配置已保存到: ${File(configDir, "bot.yml").absolutePath}")
            true
        } catch (e: Exception) {
            logger.error("保存候选平台配置失败: ${e.message}", e)
            false
        }
    }

    /**
     * 导出当前 bot.yml 运行态深度快照，供热重载代际构建和失败回滚使用。
     */
    fun runtimeSnapshot(): BotConfig {
        return botConfig.deepCopyForRuntimeSnapshot()
    }

    /**
     * 安装已验证的 bot.yml 运行态快照；磁盘写入仍只能通过 saveConfig 完成。
     */
    fun installRuntimeSnapshot(configSnapshot: BotConfig) {
        botConfig = configSnapshot.normalizedBotConfig().deepCopyForRuntimeSnapshot()
    }

    /**
     * 重新执行一次完整的配置初始化流程。
     */
    fun reload() {
        logger.info("正在重新加载配置...")
        init()
    }
}
