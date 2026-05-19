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
}
