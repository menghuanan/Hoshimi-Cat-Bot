package top.bilibili.utils.translate

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransApiRuntimeConfigRegressionTest {
    /**
     * 翻译客户端不能在顶层捕获百度密钥，否则 WebUI 热重载后校验和实际签名会分裂。
     */
    @Test
    fun `translation should not cache baidu client at top level`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/top/bilibili/utils/translate/TransApi.kt"),
            StandardCharsets.UTF_8,
        )

        assertFalse(source.contains("private val api = TransApi("))
        assertTrue(source.contains("currentBaiduTranslateApi()"))
    }
}
