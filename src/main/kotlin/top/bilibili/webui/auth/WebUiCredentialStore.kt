package top.bilibili.webui.auth

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * WebUI 凭据存储只管理独立认证状态文件，避免把密码材料混入受 ConfigManager 管理的 YAML 配置。
 */
class WebUiCredentialStore(
    private val credentialFile: File,
    private val random: SecureRandom = SecureRandom(),
    private val clock: () -> Long = { System.currentTimeMillis() / 1000L },
) {
    private val credentialLock = Any()
    /**
     * 登录和改密仍使用 PBKDF2，但把迭代数收回到更轻的窗口，减少每次校验的等待时间。
     */
    private val pbkdf2Iterations = 60_000
    private val pbkdf2KeyLengthBits = 256
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /**
     * 读取已有凭据；文件缺失时生成初始密码并写入受控状态文件。
     */
    fun loadOrCreate(): WebUiCredentialBootstrap = synchronized(credentialLock) {
        if (credentialFile.exists()) {
            val existingState = loadStateOrNull()
                ?: error("WebUI credential file is blank or damaged: ${credentialFile.absolutePath}")
            return@synchronized WebUiCredentialBootstrap(
                state = existingState,
                initialPassword = null,
            )
        }

        val initialPassword = generateInitialPassword()
        val salt = generateSalt()
        val now = clock()
        val state = WebUiCredentialState(
            passwordHash = hashPassword(initialPassword, salt),
            passwordSalt = salt,
            hashAlgorithm = "PBKDF2WithHmacSHA256",
            hashIterations = pbkdf2Iterations,
            mustChangePassword = true,
            tokenVersion = 1L,
            createdAtEpochSecond = now,
            updatedAtEpochSecond = now,
        )
        saveState(state)
        WebUiCredentialBootstrap(
            state = state,
            initialPassword = initialPassword,
        )
    }

    /**
     * 读取当前凭据状态；调用方若要求存在但文件缺失，应直接视为启动前置条件未满足。
     */
    fun loadState(): WebUiCredentialState = synchronized(credentialLock) {
        loadStateOrNull() ?: error("missing WebUI credential state: ${credentialFile.absolutePath}")
    }

    /**
     * 将新的认证状态原样写回受控文件，供后续认证和 token 版本校验复用。
     */
    fun saveState(state: WebUiCredentialState) = synchronized(credentialLock) {
        saveStateLocked(state)
    }

    /** 编码后重新解码校验，再以临时文件和原子替换提交凭据状态。 */
    private fun saveStateLocked(state: WebUiCredentialState) {
        credentialFile.parentFile?.mkdirs()
        val encoded = json.encodeToString(state)
        json.decodeFromString(WebUiCredentialState.serializer(), encoded)
        val tempFile = File(credentialFile.parentFile, ".${credentialFile.name}.tmp")
        tempFile.writeText(encoded, StandardCharsets.UTF_8)
        try {
            // 现有凭据在替换前保留一份最近备份；损坏加载时只报告，不自动恢复或覆盖。
            if (credentialFile.exists()) {
                Files.copy(
                    credentialFile.toPath(),
                    File(credentialFile.parentFile, "${credentialFile.name}.bak").toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            Files.move(
                tempFile.toPath(),
                credentialFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tempFile.toPath(), credentialFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    /**
     * 用新密码替换现有凭据，并同步推进 tokenVersion 以便会话层完成失效控制。
     */
    suspend fun replacePassword(
        currentState: WebUiCredentialState,
        newPassword: String,
        mustChangePassword: Boolean = false,
    ): WebUiCredentialState {
        // 改密时把 PBKDF2 计算和状态落盘一起放到 IO 调度器，避免请求线程同时背负散列和文件写入。
        return withContext(Dispatchers.IO) {
            val salt = generateSalt()
            val now = clock()
            val nextState = currentState.copy(
                passwordHash = hashPassword(newPassword, salt),
                passwordSalt = salt,
                hashAlgorithm = "PBKDF2WithHmacSHA256",
                hashIterations = pbkdf2Iterations,
                version = 2,
                mustChangePassword = mustChangePassword,
                tokenVersion = currentState.tokenVersion + 1L,
                updatedAtEpochSecond = now,
            )
            saveState(nextState)
            nextState
        }
    }

    /**
     * 全局串行改密事务：锁内重新读取当前状态、重验旧密码并推进 tokenVersion。
     */
    suspend fun replacePasswordIfMatches(
        currentPassword: String,
        newPassword: String,
        mustChangePassword: Boolean = false,
    ): WebUiCredentialState? = withContext(Dispatchers.IO) {
        synchronized(credentialLock) {
            val currentState = loadStateOrNull() ?: return@synchronized null
            if (!matchesPasswordBlocking(currentState, currentPassword)) return@synchronized null
            val salt = generateSalt()
            val now = clock()
            val nextState = currentState.copy(
                passwordHash = hashPassword(newPassword, salt),
                passwordSalt = salt,
                hashAlgorithm = "PBKDF2WithHmacSHA256",
                hashIterations = pbkdf2Iterations,
                version = 2,
                mustChangePassword = mustChangePassword,
                tokenVersion = currentState.tokenVersion + 1L,
                updatedAtEpochSecond = now,
            )
            saveStateLocked(nextState)
            nextState
        }
    }

    /**
     * 为后续密码校验提供统一散列入口，避免路由层自行处理密码材料。
     */
    fun hashPassword(password: String, salt: String, iterations: Int = pbkdf2Iterations): String {
        val keySpec = PBEKeySpec(password.toCharArray(), salt.toByteArray(StandardCharsets.UTF_8), iterations, pbkdf2KeyLengthBits)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val encoded = factory.generateSecret(keySpec).encoded
        keySpec.clearPassword()
        return Base64.getEncoder().encodeToString(encoded)
    }

    /**
     * 允许后续认证服务按同一套规则校验明文密码是否匹配当前状态；旧版本成功后会自动升级到 PBKDF2。
     */
    suspend fun matchesPassword(state: WebUiCredentialState, password: String): Boolean {
        // 登录校验同样切到 IO 调度器，避免 PBKDF2 占住默认计算线程池导致请求排队。
        return withContext(Dispatchers.IO) {
            val matches = matchesPasswordBlocking(state, password)
            if (matches && (state.version < 2 || state.hashAlgorithm != "PBKDF2WithHmacSHA256")) {
                migrateLegacyState(state, password)
            }
            matches
        }
    }

    /** 在调用方已选择 IO 调度器时执行无 suspend 的密码比较。 */
    private fun matchesPasswordBlocking(state: WebUiCredentialState, password: String): Boolean {
        return when {
            state.version < 2 || state.hashAlgorithm != "PBKDF2WithHmacSHA256" -> legacyMatchesPassword(state, password)
            else -> hashPassword(password, state.passwordSalt, state.hashIterations) == state.passwordHash
        }
    }

    /**
     * 只在状态文件存在且可解析时返回结果，避免首次引导流程被异常分支污染。
     */
    private fun loadStateOrNull(): WebUiCredentialState? {
        if (!credentialFile.exists()) {
            return null
        }
        val content = credentialFile.readText(StandardCharsets.UTF_8)
        if (content.isBlank()) error("WebUI credential file is blank: ${credentialFile.absolutePath}")
        return json.decodeFromString(WebUiCredentialState.serializer(), content)
    }

    /**
     * 旧状态使用 SHA-256 派生字符串；仅在兼容旧文件和自动迁移时保留。
     */
    private fun legacyMatchesPassword(state: WebUiCredentialState, password: String): Boolean {
        return legacyHashPassword(password, state.passwordSalt) == state.passwordHash
    }

    /**
     * 成功匹配旧格式时立即写回 PBKDF2 版本并生成新 salt，避免下一次登录继续走旧散列路径。
     */
    private fun migrateLegacyState(state: WebUiCredentialState, password: String) {
        val salt = generateSalt()
        val now = clock()
        val migratedState = state.copy(
            version = 2,
            hashAlgorithm = "PBKDF2WithHmacSHA256",
            hashIterations = pbkdf2Iterations,
            passwordHash = hashPassword(password, salt),
            passwordSalt = salt,
            tokenVersion = state.tokenVersion + 1L,
            updatedAtEpochSecond = now,
        )
        saveState(migratedState)
    }

    /**
     * 兼容旧状态的 SHA-256 计算只用于迁移，不再作为新密码的持久化格式。
     */
    private fun legacyHashPassword(password: String, salt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest("$salt:$password".toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    /**
     * 初始密码同时包含字母、数字和特殊字符，为强制改密前提供可用但不弱的默认口令。
     */
    private fun generateInitialPassword(length: Int = 14): String {
        val lower = "abcdefghijkmnpqrstuvwxyz"
        val upper = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        val digits = "23456789"
        val special = "!@#$%^&*()-_=+"
        val all = lower + upper + digits + special
        val required = mutableListOf(
            lower.random(random),
            upper.random(random),
            digits.random(random),
            special.random(random),
        )
        repeat((length - required.size).coerceAtLeast(0)) {
            required += all.random(random)
        }
        return required.shuffled(random).joinToString("")
    }

    /**
     * salt 使用 URL-safe Base64，便于 JSON 持久化和日志定位时保持单行可读。
     */
    private fun generateSalt(size: Int = 24): String {
        val bytes = ByteArray(size)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

/**
 * 使用 SecureRandom 挑选字符，避免把凭据生成退化为可预测的伪随机序列。
 */
private fun String.random(random: SecureRandom): Char = this[random.nextInt(length)]

/**
 * 按 SecureRandom 洗牌字符列表，避免初始密码的字符类别顺序固定可猜。
 */
private fun <T> List<T>.shuffled(random: SecureRandom): List<T> {
    val mutable = toMutableList()
    for (index in mutable.lastIndex downTo 1) {
        val swapIndex = random.nextInt(index + 1)
        val current = mutable[index]
        mutable[index] = mutable[swapIndex]
        mutable[swapIndex] = current
    }
    return mutable
}
