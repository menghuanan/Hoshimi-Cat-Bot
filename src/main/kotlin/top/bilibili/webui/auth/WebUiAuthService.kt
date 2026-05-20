package top.bilibili.webui.auth

/**
 * WebUI 认证服务只组合凭据存储、密码策略与 token 生命周期，不承载 HTTP 细节。
 */
class WebUiAuthService(
    private val credentialStore: WebUiCredentialStore,
    private val tokenService: WebUiTokenService,
    private val passwordPolicy: WebUiPasswordPolicy = WebUiPasswordPolicy,
    private val confirmationTtlMillis: Long = 60_000L,
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    private val confirmationExpiryByToken: MutableMap<String, Long> = mutableMapOf(),
) {
    /**
     * 启动期确保凭据状态存在，供 WebUI 在开放登录入口前完成本地认证前置条件准备。
     */
    fun bootstrapCredentials(): WebUiCredentialBootstrap = credentialStore.loadOrCreate()

    /**
     * 校验密码并在成功时签发 token；首次密码仍有效时保留 mustChangePassword 标记。
     */
    fun login(password: String): WebUiLoginResult {
        val state = credentialStore.loadState()
        if (!credentialStore.matchesPassword(state, password)) {
            return WebUiLoginResult(
                success = false,
                message = "invalid credentials",
            )
        }
        val session = tokenService.issueToken(state.tokenVersion)
        return WebUiLoginResult(
            success = true,
            token = session.token,
            mustChangePassword = state.mustChangePassword,
        )
    }

    /**
     * 统一解析当前 token 对应的认证状态，供 route guard 和会话探针复用。
     */
    fun resolveSession(token: String?): WebUiAuthenticatedSession? {
        val resolvedToken = token?.takeIf { it.isNotBlank() } ?: return null
        val state = credentialStore.loadState()
        val verified = tokenService.verifyToken(resolvedToken, state.tokenVersion) ?: return null
        return WebUiAuthenticatedSession(
            token = verified.token,
            tokenVersion = verified.tokenVersion,
            mustChangePassword = state.mustChangePassword,
        )
    }

    /**
     * 登出只处理当前 token 的内存会话，凭据文件和其他已登录会话保持不变。
     */
    fun logout(token: String?): Boolean {
        val resolvedToken = token?.takeIf { it.isNotBlank() } ?: return false
        return tokenService.revokeToken(resolvedToken)
    }

    /**
     * 强制改密和常规改密共用同一入口；成功后统一清空旧 token 并要求重新认证。
     */
    fun changePassword(
        currentPassword: String,
        newPassword: String,
    ): WebUiPasswordChangeResult {
        val state = credentialStore.loadState()
        if (!credentialStore.matchesPassword(state, currentPassword)) {
            return WebUiPasswordChangeResult(
                success = false,
                message = "invalid credentials",
            )
        }
        val validation = passwordPolicy.validate(newPassword)
        if (!validation.isValid) {
            return WebUiPasswordChangeResult(
                success = false,
                message = validation.errors.joinToString("; "),
            )
        }
        credentialStore.replacePassword(
            currentState = state,
            newPassword = newPassword,
            mustChangePassword = false,
        )
        // 改密后立即清空旧确认状态，避免旧密码确认窗口跨越 token 失效周期继续生效。
        confirmationExpiryByToken.clear()
        tokenService.revokeAll()
        return WebUiPasswordChangeResult(
            success = true,
            requiresReauthentication = true,
        )
    }

    /**
     * 高风险操作统一要求显式确认；当前密码校验成功后会在当前会话内缓存一个短时确认窗口。
     */
    fun confirmHighRiskOperation(
        session: WebUiAuthenticatedSession,
        currentPassword: String,
    ): WebUiHighRiskConfirmationResult {
        val now = timeProvider()
        val cachedExpiry = confirmationExpiryByToken[session.token]
        if (currentPassword.isBlank() && cachedExpiry != null && cachedExpiry > now) {
            return WebUiHighRiskConfirmationResult(
                confirmed = true,
                reusedGrant = true,
                expiresAtEpochMillis = cachedExpiry,
                message = "confirmed",
            )
        }
        if (currentPassword.isBlank()) {
            if (cachedExpiry != null && cachedExpiry <= now) {
                confirmationExpiryByToken.remove(session.token)
                return WebUiHighRiskConfirmationResult(
                    confirmed = false,
                    message = "confirmation expired, re-enter current password",
                )
            }
            return WebUiHighRiskConfirmationResult(
                confirmed = false,
                message = "confirmation password required",
            )
        }
        pruneExpiredConfirmations()
        return confirmWithPassword(session, currentPassword, now)
    }

    /**
     * 不带会话上下文的确认入口只用于本地服务测试；它不会缓存确认窗口。
     */
    fun confirmHighRiskOperation(currentPassword: String): WebUiHighRiskConfirmationResult {
        val state = credentialStore.loadState()
        val confirmed = credentialStore.matchesPassword(state, currentPassword)
        return WebUiHighRiskConfirmationResult(
            confirmed = confirmed,
            message = if (confirmed) "confirmed" else "invalid confirmation password",
        )
    }

    /**
     * 确认成功后把 TTL 绑定到当前 session token，避免不同浏览器标签或旧 token 互相复用高风险授权。
     */
    private fun confirmWithPassword(
        session: WebUiAuthenticatedSession,
        currentPassword: String,
        now: Long,
    ): WebUiHighRiskConfirmationResult {
        val state = credentialStore.loadState()
        val confirmed = credentialStore.matchesPassword(state, currentPassword)
        if (!confirmed) {
            return WebUiHighRiskConfirmationResult(
                confirmed = false,
                message = "invalid confirmation password",
            )
        }
        val expiresAt = now + confirmationTtlMillis
        confirmationExpiryByToken[session.token] = expiresAt
        return WebUiHighRiskConfirmationResult(
            confirmed = true,
            reusedGrant = false,
            expiresAtEpochMillis = expiresAt,
            message = "confirmed",
        )
    }

    /**
     * 过期确认窗口在每次检查前清理，避免服务端长期保留已经无效的高风险授权状态。
     */
    private fun pruneExpiredConfirmations() {
        val now = timeProvider()
        confirmationExpiryByToken.entries.removeIf { (_, expiresAt) -> expiresAt <= now }
    }
}

/**
 * 登录结果只描述认证成败和前端下一步动作，不暴露内部凭据状态细节。
 */
data class WebUiLoginResult(
    val success: Boolean,
    val token: String? = null,
    val mustChangePassword: Boolean = false,
    val message: String = "",
)

/**
 * 改密结果只描述是否成功以及是否需要重新登录。
 */
data class WebUiPasswordChangeResult(
    val success: Boolean,
    val requiresReauthentication: Boolean = false,
    val message: String = "",
)

/**
 * 已解析会话只暴露 route guard 需要的最小状态，避免 HTTP 层依赖凭据存储细节。
 */
data class WebUiAuthenticatedSession(
    val token: String,
    val tokenVersion: Long,
    val mustChangePassword: Boolean,
)

/**
 * 高风险确认结果既描述是否通过，也描述是否复用了短时确认窗口，供路由和测试统一消费。
 */
data class WebUiHighRiskConfirmationResult(
    val confirmed: Boolean,
    val reusedGrant: Boolean = false,
    val expiresAtEpochMillis: Long = 0L,
    val message: String = "",
)
