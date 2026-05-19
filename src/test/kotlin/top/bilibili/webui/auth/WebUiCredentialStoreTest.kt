package top.bilibili.webui.auth

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebUiCredentialStoreTest {
    private val tempRoot: Path = Files.createTempDirectory("webui-credential-store")

    @AfterTest
    fun cleanup() {
        tempRoot.toFile().deleteRecursively()
    }

    @Test
    fun `loadOrCreate should bootstrap controlled credential state when file is missing`() {
        val credentialFile = tempRoot.resolve("webui-credentials.json")
        val store = WebUiCredentialStore(credentialFile.toFile())

        val bootstrap = store.loadOrCreate()
        val persisted = Files.readString(credentialFile, StandardCharsets.UTF_8)
        val initialPassword = bootstrap.initialPassword

        assertTrue(Files.exists(credentialFile))
        assertNotNull(initialPassword)
        assertTrue(initialPassword.isNotBlank())
        assertTrue(bootstrap.state.mustChangePassword)
        assertFalse(persisted.contains(initialPassword))
        assertTrue(persisted.contains("passwordHash"))
    }

    @Test
    fun `loadOrCreate should reuse existing credential state without regenerating password`() {
        val credentialFile = tempRoot.resolve("webui-credentials.json")
        val store = WebUiCredentialStore(credentialFile.toFile())

        val first = store.loadOrCreate()
        val second = store.loadOrCreate()

        assertNotNull(first.initialPassword)
        assertNull(second.initialPassword)
        assertEquals(first.state.passwordHash, second.state.passwordHash)
        assertEquals(first.state.mustChangePassword, second.state.mustChangePassword)
    }
}
