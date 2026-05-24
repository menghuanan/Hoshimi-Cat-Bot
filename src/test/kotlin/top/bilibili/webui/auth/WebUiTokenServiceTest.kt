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

    /**
     * 会话仓库达到容量上限时应淘汰最旧会话，并在签发新会话前先清理过期项。
     */
    @Test
    fun `session store should prune expired sessions and evict oldest session at capacity`() {
        var now = 1_000L
        val service = WebUiTokenService(
            tokenTtlSeconds = 10L,
            maxSessions = 2,
            clock = { now },
        )

        val first = service.issueToken(tokenVersion = 1L)
        val second = service.issueToken(tokenVersion = 1L)
        val third = service.issueToken(tokenVersion = 1L)
        now += 11L
        val fourth = service.issueToken(tokenVersion = 1L)

        assertNull(service.verifyToken(first.token, 1L))
        assertNull(service.verifyToken(second.token, 1L))
        assertNull(service.verifyToken(third.token, 1L))
        assertNotNull(service.verifyToken(fourth.token, 1L))
        assertEquals(1, service.activeSessionCount())
    }
}
