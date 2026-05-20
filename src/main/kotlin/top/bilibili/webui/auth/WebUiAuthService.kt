package top.bilibili.webui.auth

/**
 * WebUI 认证服务只组合凭据存储、密码策略与 token 生命周期，不承载 HTTP 细节。
 */
class WebUiAuthService(
    private val credentialStore: WebUiCredentialStore,
    private val tokenService: WebUiTokenService,
    private val passwordPolicy: WebUiPasswordPolicy = WebUiPasswordPolicy,
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
        tokenService.revokeAll()
        return WebUiPasswordChangeResult(
            success = true,
            requiresReauthentication = true,
        )
    }

    /**
     * 高风险操作统一要求再次校验当前密码，避免单靠登录 token 就执行保存或停机动作。
     */
    fun confirmHighRiskOperation(currentPassword: String): WebUiHighRiskConfirmationResult {
        val state = credentialStore.loadState()
        val confirmed = credentialStore.matchesPassword(state, currentPassword)
        return WebUiHighRiskConfirmationResult(
            confirmed = confirmed,
            message = if (confirmed) "confirmed" else "invalid confirmation password",
        )
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
 * 高风险确认结果只描述确认是否通过，供保存与动作路由统一消费。
 */
data class WebUiHighRiskConfirmationResult(
    val confirmed: Boolean,
    val message: String = "",
)
