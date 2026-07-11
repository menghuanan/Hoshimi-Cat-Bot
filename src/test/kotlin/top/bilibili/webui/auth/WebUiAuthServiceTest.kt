package top.bilibili.webui.auth

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebUiAuthServiceTest {
    private val tempRoot = Files.createTempDirectory("webui-auth-service")

    @AfterTest
    fun cleanup() {
        tempRoot.toFile().deleteRecursively()
    }

    @Test
    fun `login should issue token only on successful credential match`() = runBlocking {
        val store = WebUiCredentialStore(tempRoot.resolve("webui-credentials.json").toFile())
        val bootstrap = store.loadOrCreate()
        val service = WebUiAuthService(
            credentialStore = store,
            tokenService = WebUiTokenService(tokenTtlSeconds = 300L),
        )

        val failed = service.login("wrong-password")
        val success = service.login(bootstrap.initialPassword!!)

        assertFalse(failed.success)
        assertNull(failed.token)
        assertTrue(success.success)
        assertNotNull(success.token)
        assertTrue(success.mustChangePassword)
    }

    /**
     * 失败节流对外仍返回通用认证失败消息，但应携带内部计数和等待时间供路由审计使用。
     */
    @Test
    fun `repeated login failures should trigger per ip lockout with generic visible message`() = runBlocking {
        var now = 1_000L
        val store = WebUiCredentialStore(tempRoot.resolve("webui-credentials.json").toFile())
        val bootstrap = store.loadOrCreate()
        val service = WebUiAuthService(
            credentialStore = store,
            tokenService = WebUiTokenService(tokenTtlSeconds = 300L, clock = { now / 1000L }),
            timeProvider = { now },
        )
        val context = WebUiLoginContext(
            sourceIp = "192.0.2.10",
            userAgent = "Mozilla/5.0 Test Browser",
        )

        repeat(5) {
            val failed = service.login("wrong-password", context)
            assertFalse(failed.success)
            assertEquals("invalid credentials", failed.message)
        }
        val locked = service.login(bootstrap.initialPassword!!, context)
        val otherIp = service.login(
            bootstrap.initialPassword!!,
            context.copy(sourceIp = "192.0.2.11"),
        )
        now += locked.retryAfterMillis
        val afterDelay = service.login(bootstrap.initialPassword!!, context)

        assertFalse(locked.success)
        assertEquals("invalid credentials", locked.message)
        assertTrue(locked.retryAfterMillis > 0L)
        assertTrue(locked.failureCount >= 5)
        assertTrue(otherIp.success)
        assertTrue(afterDelay.success)
    }

    @Test
    fun `password change should invalidate prior tokens and clear forced change flag`() = runBlocking {
        val store = WebUiCredentialStore(tempRoot.resolve("webui-credentials.json").toFile())
        val bootstrap = store.loadOrCreate()
        val tokenService = WebUiTokenService(tokenTtlSeconds = 300L)
        val service = WebUiAuthService(
            credentialStore = store,
            tokenService = tokenService,
        )

        val login = service.login(bootstrap.initialPassword!!)
        val token = login.token!!
        val changed = service.changePassword(
            currentPassword = bootstrap.initialPassword,
            newPassword = "Better123!@",
        )
        val relogin = service.login("Better123!@")

        assertTrue(changed.success)
        assertTrue(changed.requiresReauthentication)
        assertNull(tokenService.verifyToken(token, store.loadState().tokenVersion))
        assertFalse(store.loadState().mustChangePassword)
        assertTrue(relogin.success)
        assertFalse(relogin.mustChangePassword)
        assertFalse(service.login(bootstrap.initialPassword).success)
    }

    /**
     * 登出只应撤销当前会话 token，避免同一管理员在其他浏览器中的有效会话被意外踢下线。
     */
    @Test
    fun `logout should revoke only the current token`() = runBlocking {
        val store = WebUiCredentialStore(tempRoot.resolve("webui-credentials.json").toFile())
        val bootstrap = store.loadOrCreate()
        val tokenService = WebUiTokenService(tokenTtlSeconds = 300L)
        val service = WebUiAuthService(
            credentialStore = store,
            tokenService = tokenService,
        )

        val firstToken = service.login(bootstrap.initialPassword!!).token!!
        val secondToken = service.login(bootstrap.initialPassword).token!!
        val loggedOut = service.logout(firstToken)

        assertTrue(loggedOut)
        assertNull(service.resolveSession(firstToken))
        assertNotNull(service.resolveSession(secondToken))
    }

    @Test
    fun `high risk confirmation should require the current password`() = runBlocking {
        val store = WebUiCredentialStore(tempRoot.resolve("webui-credentials.json").toFile())
        val bootstrap = store.loadOrCreate()
        val service = WebUiAuthService(
            credentialStore = store,
            tokenService = WebUiTokenService(tokenTtlSeconds = 300L),
        )
        val session = service.resolveSession(service.login(bootstrap.initialPassword!!).token!!)!!

        val beforeChange = service.confirmHighRiskOperation(session, "wrong-password")
        service.changePassword(
            currentPassword = bootstrap.initialPassword!!,
            newPassword = "Better123!@",
        )
        val oldPasswordConfirmation = service.confirmHighRiskOperation(session, bootstrap.initialPassword)
        val newPasswordConfirmation = service.confirmHighRiskOperation(session, "Better123!@")
        val reusedGrantConfirmation = service.confirmHighRiskOperation(session, "")

        assertFalse(beforeChange.confirmed)
        assertFalse(oldPasswordConfirmation.confirmed)
        assertTrue(newPasswordConfirmation.confirmed)
        assertFalse(newPasswordConfirmation.reusedGrant)
        assertTrue(reusedGrantConfirmation.confirmed)
        assertTrue(reusedGrantConfirmation.reusedGrant)
    }

    /**
     * 确认窗口应当受 TTL 约束，过期后必须重新输入当前密码。
     */
    @Test
    fun `high risk confirmation should expire after the configured ttl`() = runBlocking {
        var now = 1_000L
        val store = WebUiCredentialStore(tempRoot.resolve("webui-credentials.json").toFile())
        val bootstrap = store.loadOrCreate()
        val service = WebUiAuthService(
            credentialStore = store,
            tokenService = WebUiTokenService(tokenTtlSeconds = 300L),
            confirmationTtlMillis = 500L,
            timeProvider = { now },
        )
        val session = service.resolveSession(service.login(bootstrap.initialPassword!!).token!!)!!

        val initialConfirmation = service.confirmHighRiskOperation(session, bootstrap.initialPassword)
        now += 200L
        val reusedConfirmation = service.confirmHighRiskOperation(session, "")
        now += 600L
        val expiredConfirmation = service.confirmHighRiskOperation(session, "")

        assertTrue(initialConfirmation.confirmed)
        assertFalse(initialConfirmation.reusedGrant)
        assertTrue(reusedConfirmation.confirmed)
        assertTrue(reusedConfirmation.reusedGrant)
        assertFalse(expiredConfirmation.confirmed)
        assertTrue(expiredConfirmation.message.contains("expired"))
    }

    /** 两个并发改密请求使用同一旧密码时，只允许锁内先完成者成功。 */
    @Test
    fun `concurrent password changes should allow exactly one winner`() = runBlocking {
        val store = WebUiCredentialStore(tempRoot.resolve("webui-credentials.json").toFile())
        val bootstrap = store.loadOrCreate()
        val service = WebUiAuthService(store, WebUiTokenService(tokenTtlSeconds = 300L))
        val currentPassword = requireNotNull(bootstrap.initialPassword)

        val first = async { service.changePassword(currentPassword, "FirstBetter123!@") }
        val second = async { service.changePassword(currentPassword, "SecondBetter123!@") }
        val results = listOf(first.await(), second.await())

        assertEquals(1, results.count { it.success })
        assertEquals(1, results.count { !it.success && it.message == "invalid credentials" })
        assertTrue(service.login("FirstBetter123!@").success xor service.login("SecondBetter123!@").success)
    }
}
