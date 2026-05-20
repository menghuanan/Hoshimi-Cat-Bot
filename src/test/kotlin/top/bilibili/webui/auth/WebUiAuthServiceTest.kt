package top.bilibili.webui.auth

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
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
    fun `login should issue token only on successful credential match`() {
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

    @Test
    fun `password change should invalidate prior tokens and clear forced change flag`() {
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

    @Test
    fun `high risk confirmation should require the current password`() {
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
    fun `high risk confirmation should expire after the configured ttl`() {
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
}
