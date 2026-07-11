package top.bilibili.data

import kotlin.test.Test
import kotlin.test.assertEquals

class BiliCookieReplacementTest {
    /** 完整替换时缺失字段必须清空旧值，不能沿用上一代 Cookie。 */
    @Test
    fun `missing fields should clear previous cookie values`() {
        val runtime = BiliCookie(sessData = "old-session", biliJct = "old-csrf")

        runtime.replaceWith(runtime.fromHeader("SESSDATA=new-session"))

        assertEquals("new-session", runtime.sessData)
        assertEquals("", runtime.biliJct)
    }

    /** Cookie 值中的等号属于值本体，解析时不得被无界 split 截断。 */
    @Test
    fun `cookie parser should preserve equals in value`() {
        val parsed = BiliCookie().fromHeader("SESSDATA=abc==; bili_jct=csrf")
        assertEquals("abc==", parsed.sessData)
        assertEquals("csrf", parsed.biliJct)
    }
}
