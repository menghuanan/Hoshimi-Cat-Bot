package top.bilibili.webui.auth

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

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
     * 新生成和改密后的凭据都应使用统一的较低 PBKDF2 迭代数，避免登录校验继续放大等待时间。
     */
    @Test
    fun `new credential states should use the reduced pbkdf2 iteration count`() {
        val credentialFile = tempRoot.resolve("webui-credentials.json")
        val store = WebUiCredentialStore(credentialFile.toFile())

        val bootstrap = store.loadOrCreate()
        val replaced = runBlocking { store.replacePassword(bootstrap.state, "Better123!@") }

        assertEquals(60_000, bootstrap.state.hashIterations)
        assertEquals(60_000, replaced.hashIterations)
        assertEquals(60_000, store.loadState().hashIterations)
    }

    /**
     * 升级前已经落盘的 120k 凭据仍应可用，避免只改默认迭代数就把历史状态锁死在门外。
     */
    @Test
    fun `existing pbkdf2 credential states should honor the stored iteration count`() = runBlocking {
        val credentialFile = tempRoot.resolve("webui-credentials.json")
        val store = WebUiCredentialStore(credentialFile.toFile())
        val password = "LegacyStillValid123!@"
        val salt = "legacy-pbkdf2-salt"
        val persistedHash = pbkdf2Hash(password, salt, 120_000)

        store.saveState(
            WebUiCredentialState(
                version = 2,
                hashAlgorithm = "PBKDF2WithHmacSHA256",
                hashIterations = 120_000,
                passwordHash = persistedHash,
                passwordSalt = salt,
                mustChangePassword = false,
                tokenVersion = 5L,
                createdAtEpochSecond = 100L,
                updatedAtEpochSecond = 100L,
            ),
        )

        val matched = store.matchesPassword(store.loadState(), password)

        assertTrue(matched)
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

        val matched = runBlocking { store.matchesPassword(store.loadState(), legacyPassword) }
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

    /** 已有损坏凭据必须保留原文并拒绝生成新密码。 */
    @Test
    fun `corrupt credential file should fail without overwrite or bootstrap`() {
        val credentialFile = tempRoot.resolve("webui-credentials.json")
        val original = "{corrupt-json"
        Files.writeString(credentialFile, original, StandardCharsets.UTF_8)
        val store = WebUiCredentialStore(credentialFile.toFile())

        assertFailsWith<IllegalStateException> { store.loadOrCreate() }
        assertEquals(original, Files.readString(credentialFile, StandardCharsets.UTF_8))
    }

    /**
     * 测试内只复现历史凭据格式，避免依赖生产代码继续暴露旧散列入口。
     */
    private fun legacySha256(password: String, salt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest("$salt:$password".toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    /**
     * 测试内显式指定 PBKDF2 迭代数，避免把迁移回归绑定到生产实现的默认常量。
     */
    private fun pbkdf2Hash(password: String, salt: String, iterations: Int): String {
        val keySpec = javax.crypto.spec.PBEKeySpec(
            password.toCharArray(),
            salt.toByteArray(StandardCharsets.UTF_8),
            iterations,
            256,
        )
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val encoded = factory.generateSecret(keySpec).encoded
        keySpec.clearPassword()
        return Base64.getEncoder().encodeToString(encoded)
    }
}
