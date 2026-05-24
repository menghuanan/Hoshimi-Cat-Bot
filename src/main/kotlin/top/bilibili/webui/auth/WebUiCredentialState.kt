package top.bilibili.webui.auth

import kotlinx.serialization.Serializable

/**
 * WebUI 凭据状态只保存本地认证材料，不承担任何业务配置或平台账号信息。
 * 版本 2 起使用 PBKDF2，并保留算法元数据以便向后兼容旧散列格式。
 */
@Serializable
data class WebUiCredentialState(
    val version: Int = 2,
    val hashAlgorithm: String = "PBKDF2WithHmacSHA256",
    val hashIterations: Int = 120_000,
    val passwordHash: String,
    val passwordSalt: String,
    val mustChangePassword: Boolean = true,
    val tokenVersion: Long = 1L,
    val createdAtEpochSecond: Long,
    val updatedAtEpochSecond: Long,
)

/**
 * 凭据引导结果只供服务端启动和内部服务消费，不直接暴露给 WebUI API。
 */
data class WebUiCredentialBootstrap(
    val state: WebUiCredentialState,
    val initialPassword: String?,
)
