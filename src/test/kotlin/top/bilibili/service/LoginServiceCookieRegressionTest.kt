package top.bilibili.service

import kotlin.test.Test
import kotlin.test.assertEquals

class LoginServiceCookieRegressionTest {
    /** 新版 ticket 兑换响应中的 Set-Cookie 必须转换为现有提交链路可解析的凭据。 */
    @Test
    fun `ticket cookies should become login callback parameters`() {
        val callbackUrl = cookiesToCallbackUrl(
            listOf(
                "SESSDATA=session%2Cvalue; Path=/; Secure",
                "bili_jct=csrf-token; Path=/; Secure",
                "DedeUserID=12345; Path=/; Secure",
                "sid=session-id; Path=/",
            ),
        )

        val payload = LoginService.parseLoginCallback(callbackUrl ?: error("ticket cookies were not converted"))
        assertEquals("SESSDATA=session%2Cvalue; bili_jct=csrf-token;", payload.cookie)
        assertEquals("12345", payload.dedeUserId)
    }
}
