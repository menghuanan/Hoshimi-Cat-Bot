package top.bilibili.webui.auth

/**
 * 密码策略只约束 WebUI 本地账号，避免路由层各自拼接校验规则。
 */
object WebUiPasswordPolicy {
    /**
     * 统一返回密码校验结果，便于登录页与强制改密流程共享相同错误语义。
     */
    fun validate(password: String): WebUiPasswordValidationResult {
        val errors = mutableListOf<String>()
        if (password.length < 8) {
            errors += "密码长度至少需要 8 位"
        }
        if (password.any(Char::isWhitespace)) {
            errors += "密码不能包含空格"
        }
        if (password.none(Char::isLetter)) {
            errors += "密码必须包含字母"
        }
        if (password.none(Char::isDigit)) {
            errors += "密码必须包含数字"
        }
        if (password.none { !it.isLetterOrDigit() && !it.isWhitespace() }) {
            errors += "密码必须包含特殊字符"
        }
        return WebUiPasswordValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
        )
    }
}

/**
 * WebUI 密码校验结果保持轻量，只暴露是否通过和用户可读错误集合。
 */
data class WebUiPasswordValidationResult(
    val isValid: Boolean,
    val errors: List<String>,
)
