package top.bilibili.webui.auth

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
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

    /**
     * 成功登录旧版 SHA-256 凭据时应自动迁移到版本化 PBKDF2，避免继续保存弱散列格式。
     */
    @Test
    fun `successful legacy credential match should migrate persisted state to pbkdf2`() {
        val credentialFile = tempRoot.resolve("webui-credentials.json")
        val store = WebUiCredentialStore(credentialFile.toFile())
        val legacyPassword = "Legacy123!@"
        val legacySalt = "legacy-salt"
        store.saveState(
            WebUiCredentialState(
                version = 1,
                passwordHash = legacySha256(legacyPassword, legacySalt),
                passwordSalt = legacySalt,
                mustChangePassword = false,
                tokenVersion = 7L,
                createdAtEpochSecond = 100L,
                updatedAtEpochSecond = 100L,
            ),
        )

        val matched = store.matchesPassword(store.loadState(), legacyPassword)
        val migrated = store.loadState()
        val persisted = Files.readString(credentialFile, StandardCharsets.UTF_8)

        assertTrue(matched)
        assertEquals(2, migrated.version)
        assertEquals(8L, migrated.tokenVersion)
        assertNotNull(migrated.passwordSalt)
        assertFalse(migrated.passwordSalt.isBlank())
        assertFalse(migrated.passwordSalt == legacySalt)
        assertEquals("PBKDF2WithHmacSHA256", migrated.hashAlgorithm)
        assertEquals(
            migrated.passwordHash,
            store.hashPassword(legacyPassword, migrated.passwordSalt),
        )
        assertTrue(persisted.contains("\"tokenVersion\": 8"))
        assertFalse(persisted.contains(legacySha256(legacyPassword, legacySalt)))
    }

    /**
     * 测试内只复现历史凭据格式，避免依赖生产代码继续暴露旧散列入口。
     */
    private fun legacySha256(password: String, salt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest("$salt:$password".toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
