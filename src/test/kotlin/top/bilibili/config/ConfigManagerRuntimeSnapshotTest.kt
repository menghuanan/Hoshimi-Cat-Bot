package top.bilibili.config

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigManagerRuntimeSnapshotTest {
    /**
     * bot.yml 候选持久化只写磁盘，不能在平台候选资源准备前替换当前运行态配置。
     */
    @Test
    fun `persistConfigSnapshot should write bot config without installing runtime state`() {
        val oldConfig = BotConfig(firstRunFlag = 1)
        val candidateConfig = BotConfig(firstRunFlag = 2)
        val originalConfig = runCatching { ConfigManager.botConfig }.getOrNull()
        val botFile = Path.of("config", "bot.yml")
        val originalFileBytes = if (Files.exists(botFile)) Files.readAllBytes(botFile) else null
        try {
            Files.createDirectories(botFile.parent)
            ConfigManager.installRuntimeSnapshot(oldConfig)

            val saved = ConfigManager.persistConfigSnapshot(candidateConfig)

            assertTrue(saved)
            assertEquals(1, ConfigManager.botConfig.firstRunFlag)
        } finally {
            originalConfig?.let { ConfigManager.installRuntimeSnapshot(it) }
            if (originalFileBytes != null) {
                Files.createDirectories(botFile.parent)
                Files.write(botFile, originalFileBytes)
            } else {
                Files.deleteIfExists(botFile)
            }
        }
    }
}
