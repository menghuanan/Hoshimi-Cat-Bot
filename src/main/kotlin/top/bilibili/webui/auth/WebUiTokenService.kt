package top.bilibili.webui.auth

import java.security.SecureRandom
import java.util.Base64

/**
 * WebUI token 服务只维护本地内存会话，不把认证 token 持久化到磁盘或业务配置。
 */
class WebUiTokenService(
    private val tokenTtlSeconds: Long,
    private val maxSessions: Int = 256,
    private val random: SecureRandom = SecureRandom(),
    private val clock: () -> Long = { System.currentTimeMillis() / 1000L },
) {
    private val sessionLock = Any()
    private val sessionCapacity = maxSessions.coerceAtLeast(1)
    private val sessions = linkedMapOf<String, WebUiTokenSession>()

    /**
     * 为当前 tokenVersion 签发新的会话 token，并附带同源 CSRF 材料供 double-submit 校验使用。
     */
    fun issueToken(tokenVersion: Long): WebUiTokenSession = synchronized(sessionLock) {
        pruneExpiredSessions()
        val issuedAt = clock()
        val session = WebUiTokenSession(
            token = generateTokenValue(),
            csrfToken = generateTokenValue(16),
            tokenVersion = tokenVersion,
            issuedAtEpochSecond = issuedAt,
            expiresAtEpochSecond = issuedAt + tokenTtlSeconds,
        )
        evictOldestIfNeeded()
        sessions[session.token] = session
        session
    }

    /**
     * 校验 token 是否仍然存在、未过期且与当前凭据版本匹配。
     */
    fun verifyToken(token: String, expectedTokenVersion: Long): WebUiTokenSession? = synchronized(sessionLock) {
        pruneExpiredSessions()
        val session = sessions[token] ?: return@synchronized null
        return when {
            session.tokenVersion != expectedTokenVersion -> {
                sessions.remove(token)
                null
            }
            else -> session
        }
    }

    /**
     * 登出只撤销当前浏览器持有的 token，避免影响同一账号的其他有效会话。
     */
    fun revokeToken(token: String): Boolean = synchronized(sessionLock) { sessions.remove(token) != null }

    /**
     * 当前只有单一 WebUI 本地账号，因此改密时直接清空全部会话即可。
     */
    fun revokeAll() = synchronized(sessionLock) { sessions.clear() }

    /**
     * 当前内存会话数用于测试和容量回收自检，不对外暴露具体会话内容。
     */
    fun activeSessionCount(): Int = synchronized(sessionLock) {
        pruneExpiredSessions()
        sessions.size
    }

    /**
     * 过期会话在每次操作前先清理，避免容量回收被陈旧条目占位。
     */
    private fun pruneExpiredSessions() {
        val now = clock()
        sessions.entries.removeIf { (_, session) -> session.expiresAtEpochSecond <= now }
    }

    /**
     * 容量到达上限时按插入顺序淘汰最旧会话，避免长期运行时无限增长。
     */
    private fun evictOldestIfNeeded() {
        while (sessions.size >= sessionCapacity && sessions.isNotEmpty()) {
            val oldestKey = sessions.entries.first().key
            sessions.remove(oldestKey)
        }
    }

    /**
     * 使用 URL-safe Base64 生成 token，避免前端 header 传输时出现额外转义负担。
     */
    private fun generateTokenValue(size: Int = 32): String {
        val bytes = ByteArray(size)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

/**
 * token 会话元数据只记录 WebUI 本地认证所需的最小信息。
 */
data class WebUiTokenSession(
    val token: String,
    val csrfToken: String,
    val tokenVersion: Long,
    val issuedAtEpochSecond: Long,
    val expiresAtEpochSecond: Long,
)
