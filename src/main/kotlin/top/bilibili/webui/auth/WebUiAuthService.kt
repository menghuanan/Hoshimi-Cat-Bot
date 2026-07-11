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
    private val failureStateByIp: MutableMap<String, WebUiLoginFailureState> = mutableMapOf(),
    private var globalFailureState: WebUiGlobalFailureState = WebUiGlobalFailureState(),
) {
    private val authStateLock = Any()
    /**
     * 启动期确保凭据状态存在，供 WebUI 在开放登录入口前完成本地认证前置条件准备。
     */
    fun bootstrapCredentials(): WebUiCredentialBootstrap = credentialStore.loadOrCreate()

    /**
     * 校验密码并在成功时签发 session/csrf 材料；首次密码仍有效时保留 mustChangePassword 标记。
     */
    suspend fun login(password: String): WebUiLoginResult {
        return login(password, WebUiLoginContext())
    }

    /**
     * 校验密码并在成功时签发 session/csrf 材料；失败时保留通用错误并记录节流信息。
     */
    suspend fun login(password: String, context: WebUiLoginContext): WebUiLoginResult {
        val state = credentialStore.loadState()
        val now = timeProvider()
        val throttled = synchronized(authStateLock) { resolveThrottleState(context.sourceIp, now) }
        if (throttled != null) {
            return WebUiLoginResult(
                success = false,
                message = "invalid credentials",
                failureCount = throttled.failureCount,
                retryAfterMillis = throttled.retryAfterMillis,
            )
        }
        if (!credentialStore.matchesPassword(state, password)) {
            val failureState = synchronized(authStateLock) { recordFailure(context.sourceIp, now) }
            return WebUiLoginResult(
                success = false,
                message = "invalid credentials",
                failureCount = failureState.failureCount,
                retryAfterMillis = failureState.retryAfterMillis,
            )
        }
        synchronized(authStateLock) { clearFailureState(context.sourceIp) }
        val currentState = credentialStore.loadState()
        val session = tokenService.issueToken(currentState.tokenVersion)
        return WebUiLoginResult(
            success = true,
            token = session.token,
            csrfToken = session.csrfToken,
            mustChangePassword = currentState.mustChangePassword,
        )
    }

    /**
     * 统一解析当前 cookie session 对应的认证状态，供 route guard 和会话探针复用。
     */
    fun resolveSession(token: String?): WebUiAuthenticatedSession? {
        val resolvedToken = token?.takeIf { it.isNotBlank() } ?: return null
        val state = credentialStore.loadState()
        val verified = tokenService.verifyToken(resolvedToken, state.tokenVersion) ?: return null
        return WebUiAuthenticatedSession(
            token = verified.token,
            csrfToken = verified.csrfToken,
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
     * Cookie 会话注销优先接收已解析会话对象，避免 route 层再次接触原始 token。
     */
    fun logout(session: WebUiAuthenticatedSession?): Boolean {
        return logout(session?.token)
    }

    /**
     * 强制改密和常规改密共用同一入口；成功后统一清空旧 token 并要求重新认证。
     */
    suspend fun changePassword(
        currentPassword: String,
        newPassword: String,
    ): WebUiPasswordChangeResult {
        val validation = passwordPolicy.validate(newPassword)
        if (!validation.isValid) {
            return WebUiPasswordChangeResult(
                success = false,
                message = validation.errors.joinToString("; "),
            )
        }
        val replaced = credentialStore.replacePasswordIfMatches(
            currentPassword = currentPassword,
            newPassword = newPassword,
            mustChangePassword = false,
        )
        if (replaced == null) {
            return WebUiPasswordChangeResult(success = false, message = "invalid credentials")
        }
        // 改密后立即清空旧确认状态，避免旧密码确认窗口跨越 token 失效周期继续生效。
        tokenService.revokeAll()
        synchronized(authStateLock) { confirmationExpiryByToken.clear() }
        return WebUiPasswordChangeResult(
            success = true,
            requiresReauthentication = true,
        )
    }

    /**
     * 高风险操作统一要求显式确认；当前密码校验成功后会在当前会话内缓存一个短时确认窗口。
     */
    suspend fun confirmHighRiskOperation(
        session: WebUiAuthenticatedSession,
        currentPassword: String,
    ): WebUiHighRiskConfirmationResult {
        val now = timeProvider()
        val cachedExpiry = synchronized(authStateLock) { confirmationExpiryByToken[session.token] }
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
                synchronized(authStateLock) { confirmationExpiryByToken.remove(session.token) }
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
        synchronized(authStateLock) { pruneExpiredConfirmations() }
        return confirmWithPassword(session, currentPassword, now)
    }

    /**
     * 不带会话上下文的确认入口只用于本地服务测试；它不会缓存确认窗口。
     */
    suspend fun confirmHighRiskOperation(currentPassword: String): WebUiHighRiskConfirmationResult {
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
    private suspend fun confirmWithPassword(
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
        synchronized(authStateLock) { confirmationExpiryByToken[session.token] = expiresAt }
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

    /**
     * 按源 IP 做节流状态查询，允许成功后的下一次登录直接恢复，不额外泄露具体原因。
     */
    private fun resolveThrottleState(sourceIp: String?, now: Long): WebUiLoginThrottleState? {
        val ipKey = normalizeSourceIp(sourceIp)
        val perIpState = ipKey?.let { failureStateByIp[it] }
        val perIpRetryAfter = perIpState?.retryAfterMillis(now) ?: 0L
        val globalRetryAfter = globalFailureState.retryAfterMillis(now)
        val retryAfterMillis = maxOf(perIpRetryAfter, globalRetryAfter)
        if (retryAfterMillis <= 0L) {
            return null
        }
        return WebUiLoginThrottleState(
            failureCount = maxOf(perIpState?.failureCount ?: 0, globalFailureState.failureCount),
            retryAfterMillis = retryAfterMillis,
        )
    }

    /**
     * 失败时分别推进源 IP 与全局计数，生成对外仍然通用的节流结果。
     */
    private fun recordFailure(sourceIp: String?, now: Long): WebUiLoginThrottleState {
        val ipKey = normalizeSourceIp(sourceIp)
        val perIpState = if (ipKey != null) {
            val current = failureStateByIp[ipKey]
            val next = (current ?: WebUiLoginFailureState()).increment(now)
            failureStateByIp[ipKey] = next
            next
        } else {
            WebUiLoginFailureState().increment(now)
        }
        globalFailureState = globalFailureState.increment(now)
        return WebUiLoginThrottleState(
            failureCount = maxOf(perIpState.failureCount, globalFailureState.failureCount),
            retryAfterMillis = maxOf(perIpState.retryAfterMillis(now), globalFailureState.retryAfterMillis(now)),
        )
    }

    /**
     * 成功登录后清空当前源 IP 的失败计数，并让全局节流自然回退到未锁定状态。
     */
    private fun clearFailureState(sourceIp: String?) {
        normalizeSourceIp(sourceIp)?.let { failureStateByIp.remove(it) }
        // 成功登录只清理对应来源；全局风险由时间窗自然衰减，不能抹掉其他并发请求的失败累计。
    }

    /**
     * 源 IP 为空时仍可使用固定标记聚合同一匿名来源，避免节流逻辑依赖空字符串分支。
     */
    private fun normalizeSourceIp(sourceIp: String?): String? {
        return sourceIp?.trim()?.takeIf { it.isNotBlank() }
    }
}

/**
 * 登录结果只描述认证成败和前端下一步动作，不暴露内部凭据状态细节。
 */
data class WebUiLoginResult(
    val success: Boolean,
    val token: String? = null,
    val csrfToken: String? = null,
    val mustChangePassword: Boolean = false,
    val failureCount: Int = 0,
    val retryAfterMillis: Long = 0L,
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
    val csrfToken: String,
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

/**
 * 登录请求上下文只携带节流和审计需要的最小来源信息。
 */
data class WebUiLoginContext(
    val sourceIp: String? = null,
    val userAgent: String? = null,
)

/**
 * 源 IP 节流状态只记录失败次数与临时封禁截止时间。
 */
data class WebUiLoginFailureState(
    val failureCount: Int = 0,
    val lockedUntilEpochMillis: Long = 0L,
) {
    /**
     * 每次失败都会推进计数，并按指数延迟生成新的临时封禁时间。
     */
    fun increment(now: Long): WebUiLoginFailureState {
        val nextCount = failureCount + 1
        val lockDuration = computeRetryAfterMillis(nextCount)
        return copy(
            failureCount = nextCount,
            lockedUntilEpochMillis = now + lockDuration,
        )
    }

    /**
     * 只在锁定窗口内返回剩余等待时间；过期后由调用方在下一次失败时自然推进。
     */
    fun retryAfterMillis(now: Long): Long {
        return (lockedUntilEpochMillis - now).coerceAtLeast(0L)
    }
}

/**
 * 全局节流状态和单 IP 逻辑保持一致，但用独立计数避免单点暴力尝试绕过总体 backoff。
 */
data class WebUiGlobalFailureState(
    val failureCount: Int = 0,
    val lockedUntilEpochMillis: Long = 0L,
) {
    /**
     * 全局失败次数也按指数增长，但会使用更短的初始等待窗口，避免误伤过重。
     */
    fun increment(now: Long): WebUiGlobalFailureState {
        val nextCount = failureCount + 1
        val lockDuration = computeGlobalRetryAfterMillis(nextCount)
        return copy(
            failureCount = nextCount,
            lockedUntilEpochMillis = now + lockDuration,
        )
    }

    /**
     * 全局锁定窗口返回剩余等待时间；超时后下次失败会重新计算。
     */
    fun retryAfterMillis(now: Long): Long {
        return (lockedUntilEpochMillis - now).coerceAtLeast(0L)
    }
}

/**
 * 节流结果对路由和审计只暴露失败次数与等待时间，不暴露内部锁定实现。
 */
private data class WebUiLoginThrottleState(
    val failureCount: Int,
    val retryAfterMillis: Long,
)

/**
 * 单 IP 节流采用指数 backoff，从第 4 次失败开始快速拉长等待时间。
 */
private fun computeRetryAfterMillis(failureCount: Int): Long {
    if (failureCount <= 4) {
        return 0L
    }
    val exponent = (failureCount - 4).coerceAtMost(6)
    return (1L shl exponent) * 1_000L
}

/**
 * 全局 backoff 的初始窗口略低于单 IP lockout，用于把分布式暴力尝试也拉进同一退避节奏。
 */
private fun computeGlobalRetryAfterMillis(failureCount: Int): Long {
    if (failureCount <= 8) {
        return 0L
    }
    val exponent = (failureCount - 8).coerceAtMost(5)
    return (1L shl exponent) * 750L
}
