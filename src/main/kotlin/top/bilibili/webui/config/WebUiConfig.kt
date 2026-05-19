package top.bilibili.webui.config

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

/**
 * WebUI 运行时配置只描述本地管理面的启动行为，不承载业务数据或页面状态。
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class WebUiConfig(
    @EncodeDefault
    val enabled: Boolean = false,
    @EncodeDefault
    val host: String = "127.0.0.1",
    @EncodeDefault
    val port: Int = 18080,
    // 凭据文件只保存 WebUI 本地认证状态，和 bot.yml 中的运行参数保持分离。
    @EncodeDefault
    @SerialName("credential_file")
    val credentialFile: String = "webui-credentials.json",
    // token 过期时间只影响 WebUI 会话，不影响业务层任何平台鉴权。
    @EncodeDefault
    @SerialName("token_ttl_seconds")
    val tokenTtlSeconds: Long = 3600L,
    @EncodeDefault
    @SerialName("static_dir")
    val staticDir: String = "",
) {
    /**
     * 归一化 WebUI 启动参数，避免空 host、非法端口或带空白的静态目录值进入运行时。
     */
    fun normalized(): WebUiConfig {
        val normalizedHost = host.trim().ifBlank { "127.0.0.1" }
        val normalizedPort = port.takeIf { it in 1..65535 } ?: 18080
        val normalizedCredentialFile = credentialFile.trim().ifBlank { "webui-credentials.json" }
        val normalizedTokenTtlSeconds = tokenTtlSeconds.takeIf { it > 0L } ?: 3600L
        val normalizedStaticDir = staticDir.trim()
        return if (
            normalizedHost == host &&
            normalizedPort == port &&
            normalizedCredentialFile == credentialFile &&
            normalizedTokenTtlSeconds == tokenTtlSeconds &&
            normalizedStaticDir == staticDir
        ) {
            this
        } else {
            copy(
                host = normalizedHost,
                port = normalizedPort,
                credentialFile = normalizedCredentialFile,
                tokenTtlSeconds = normalizedTokenTtlSeconds,
                staticDir = normalizedStaticDir,
            )
        }
    }

    /**
     * 将持久化配置转换为启动期只读设置，顺带解析外部静态目录是否可用。
     */
    fun toSettings(configDir: File = File("config")): WebUiSettings {
        val normalized = normalized()
        val credentialStateFile = File(normalized.credentialFile).let { file ->
            if (file.isAbsolute) file else File(configDir, normalized.credentialFile)
        }
        val externalStaticRoot = normalized.staticDir.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.isDirectory }
        return WebUiSettings(
            enabled = normalized.enabled,
            host = normalized.host,
            port = normalized.port,
            credentialStateFile = credentialStateFile,
            tokenTtlSeconds = normalized.tokenTtlSeconds,
            staticDir = externalStaticRoot?.absolutePath.orEmpty(),
        )
    }
}

/**
 * WebUI 启动设置是运行期快照，供服务器生命周期层消费，不反向写回配置文件。
 */
data class WebUiSettings(
    val enabled: Boolean,
    val host: String,
    val port: Int,
    val credentialStateFile: File,
    val tokenTtlSeconds: Long,
    val staticDir: String,
)
