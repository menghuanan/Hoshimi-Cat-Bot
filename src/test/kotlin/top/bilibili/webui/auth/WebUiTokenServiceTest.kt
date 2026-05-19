package top.bilibili.webui.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WebUiTokenServiceTest {
    @Test
    fun `issued token should verify until ttl expires`() {
        var now = 1_000L
        val service = WebUiTokenService(
            tokenTtlSeconds = 60L,
            clock = { now },
        )

        val session = service.issueToken(tokenVersion = 1L)

        assertNotNull(service.verifyToken(session.token, 1L))
        now += 61L
        assertNull(service.verifyToken(session.token, 1L))
    }

    @Test
    fun `token verification should reject version mismatch and revoked tokens`() {
        val service = WebUiTokenService(tokenTtlSeconds = 300L)
        val session = service.issueToken(tokenVersion = 1L)

        assertNull(service.verifyToken(session.token, 2L))
        service.revokeAll()
        assertNull(service.verifyToken(session.token, 1L))
    }

    @Test
    fun `issued token should carry current token version metadata`() {
        val service = WebUiTokenService(tokenTtlSeconds = 300L)
        val session = service.issueToken(tokenVersion = 3L)

        assertEquals(3L, session.tokenVersion)
    }
}
