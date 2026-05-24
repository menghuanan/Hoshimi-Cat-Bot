package top.bilibili.webui.model

import kotlinx.serialization.Serializable

/**
 * WebUI 认证请求 DTO 只承载浏览器和认证路由之间的最小交互字段。
 */
@Serializable
data class WebUiLoginRequestDto(
    val password: String,
)

/**
 * WebUI 改密请求 DTO 保持最小字段集，避免把凭据状态直接暴露到前端。
 */
@Serializable
data class WebUiChangePasswordRequestDto(
    val currentPassword: String,
    val newPassword: String,
)

/**
 * 登录与会话响应 DTO 只描述前端下一步动作，不返回任何服务端凭据材料。
 */
@Serializable
data class WebUiSessionDto(
    val authenticated: Boolean,
    val mustChangePassword: Boolean,
)

/**
 * 认证路由统一返回认证状态和提示消息，避免前端依赖内部服务对象或 bearer token。
 */
@Serializable
data class WebUiAuthResponseDto(
    val success: Boolean,
    val token: String? = null,
    val mustChangePassword: Boolean = false,
    val message: String = "",
)
