package top.bilibili.config

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfigManagerCorruptionTest {
    /** 已有损坏文件加载失败时内容必须原样保留。 */
    @Test
    fun `damaged bot config should not be overwritten`() {
        val directory = Files.createTempDirectory("bot-config-damaged").toFile()
        val file = directory.resolve("bot.yml")
        file.writeText("platform: [broken", Charsets.UTF_8)
        val original = file.readText(Charsets.UTF_8)

        assertFailsWith<Exception> { BotConfigFileStore(directory).loadWithMetadata() }
        assertEquals(original, file.readText(Charsets.UTF_8))
    }
}
