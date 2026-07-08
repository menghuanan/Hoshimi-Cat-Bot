package top.bilibili.connector.qqofficial

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readReason
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import java.io.Closeable
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import top.bilibili.connector.PlatformHttpClientSnapshot
import top.bilibili.connector.PlatformObservabilitySnapshot

internal const val QQ_OFFICIAL_TOKEN_URL = "https://bots.qq.com/app/getAppAccessToken"
internal const val QQ_OFFICIAL_API_BASE = "https://api.sgroup.qq.com"
// 同时订阅公域消息与群成员事件，覆盖开发平台已勾选的群聊交互入口。
internal const val QQ_OFFICIAL_GATEWAY_INTENT_PUBLIC_MESSAGES = 1 shl 25
internal const val QQ_OFFICIAL_GATEWAY_INTENT_GROUP_MEMBERS = 1 shl 24
internal const val QQ_OFFICIAL_GATEWAY_INTENT_SUBSCRIBED_EVENTS =
    QQ_OFFICIAL_GATEWAY_INTENT_PUBLIC_MESSAGES or QQ_OFFICIAL_GATEWAY_INTENT_GROUP_MEMBERS

internal val QQOfficialJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
}

internal interface QQOfficialTransport : Closeable {
    /**
     * 获取 JSON 响应，供 token/gateway/bootstrap 流程复用。
     */
    suspend fun getJson(url: String, headers: Map<String, String>): JsonObject

    /**
     * 提交 JSON 请求，供消息发送与媒体上传复用。
     */
    suspend fun postJson(url: String, body: JsonElement, headers: Map<String, String>): JsonObject

    /**
     * 打开 QQ 官方网关会话，并将文本帧暴露给适配器处理。
     */
    suspend fun openGateway(url: String, headers: Map<String, String>): QQOfficialGatewaySession

    /**
     * 返回 QQ 官方底层 HttpClient / OkHttp 资源快照，供平台守护汇总 transport 运行态。
     */
    fun runtimeObservability(): PlatformObservabilitySnapshot
}

internal interface QQOfficialGatewaySession {
    val incoming: Flow<String>
    val closeSignal: CompletableDeferred<QQOfficialGatewayClose>

    /**
     * 发送单个文本帧，供 identify/resume/heartbeat 使用。
     */
    suspend fun sendText(text: String)

    /**
     * 主动关闭当前网关会话，避免停机时留下悬挂连接。
     */
    suspend fun close(reason: String = "shutdown")
}

/**
 * QQ 官方网关 Close 帧需要按 code 分流，failure 只保留传输异常本身。
 */
internal data class QQOfficialGatewayClose(
    val code: Int? = null,
    val reason: String? = null,
    val failure: Throwable? = null,
)

internal class QQOfficialHttpException(
    val statusCode: Int,
    val responseBody: String,
) : IllegalStateException("QQ 官方接口请求失败: status=$statusCode, body=$responseBody")

/**
 * QQ 官方网关与 OpenAPI 的 Ktor 传输实现。
 */
internal class KtorQQOfficialTransport(
    private val json: Json = QQOfficialJson,
) : QQOfficialTransport {
    private val connectionPool = ConnectionPool(
        3,
        3,
        TimeUnit.MINUTES,
    )
    private val okHttpDispatcher = Dispatcher()
    private val transportScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val webSocketSessionActive = AtomicBoolean(false)
    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 30_000
        }
        install(WebSockets)
        engine {
            config {
                followRedirects(true)
                dispatcher(okHttpDispatcher)
                connectionPool(connectionPool)
            }
        }
    }

    /**
     * 执行 GET 请求并强制解析为 JSON 对象，避免调用方重复处理状态码。
     */
    override suspend fun getJson(url: String, headers: Map<String, String>): JsonObject {
        val response = client.get(url) {
            headers.forEach { (name, value) ->
                header(name, value)
            }
        }
        return parseJsonResponse(response.status.value, response.bodyAsText())
    }

    /**
     * 执行 POST 请求并返回 JSON 对象，供消息与媒体相关接口统一复用。
     */
    override suspend fun postJson(url: String, body: JsonElement, headers: Map<String, String>): JsonObject {
        val response = client.post(url) {
            contentType(ContentType.Application.Json)
            headers.forEach { (name, value) ->
                header(name, value)
            }
            setBody(body.toString())
        }
        return parseJsonResponse(response.status.value, response.bodyAsText())
    }

    /**
     * 建立真实 WebSocket 连接，并将收发行为包装为适配器可测试的会话对象。
     */
    override suspend fun openGateway(url: String, headers: Map<String, String>): QQOfficialGatewaySession {
        val incomingFlow = MutableSharedFlow<String>(replay = 32, extraBufferCapacity = 64)
        val closeSignal = CompletableDeferred<QQOfficialGatewayClose>()
        val outgoingChannel = Channel<String>(64)
        val sessionRef = AtomicReference<DefaultClientWebSocketSession?>()
        // 每次建连只创建 session 级 job，统一挂到 transportScope 下，避免重连时遗留新的根协程树。
        val sessionJob = SupervisorJob(transportScope.coroutineContext[Job])
        val sessionScope = CoroutineScope(transportScope.coroutineContext + sessionJob)
        val job = sessionScope.launch {
            var failure: Throwable? = null
            var closeFrame: QQOfficialGatewayClose? = null
            try {
                client.webSocket(
                    urlString = url,
                    request = {
                        headers.forEach { (name, value) ->
                            header(name, value)
                        }
                    },
                ) {
                    sessionRef.set(this)
                    webSocketSessionActive.set(true)
                    val sendJob = launch {
                        for (text in outgoingChannel) {
                            send(Frame.Text(text))
                        }
                    }
                    try {
                        for (frame in incoming) {
                            when (frame) {
                                is Frame.Text -> incomingFlow.emit(frame.readText())
                                is Frame.Close -> {
                                    val closeReason = frame.readReason()
                                    // Ktor 可能给出空 close reason，adapter 仍需收到一个结构化 close 事件。
                                    closeFrame = QQOfficialGatewayClose(
                                        code = closeReason?.code?.toInt(),
                                        reason = closeReason?.message,
                                    )
                                    break
                                }
                                else -> Unit
                            }
                        }
                    } finally {
                        sendJob.cancelAndJoin()
                    }
                }
            } catch (throwable: Throwable) {
                failure = throwable
            } finally {
                outgoingChannel.close()
                webSocketSessionActive.set(false)
                if (!closeSignal.isCompleted) {
                    closeSignal.complete(closeFrame ?: QQOfficialGatewayClose(failure = failure))
                }
            }
        }

        return object : QQOfficialGatewaySession {
            override val incoming: Flow<String> = incomingFlow
            override val closeSignal: CompletableDeferred<QQOfficialGatewayClose> = closeSignal

            /**
             * 将 identify/resume/heartbeat 文本帧写入当前网关连接。
             */
            override suspend fun sendText(text: String) {
                outgoingChannel.send(text)
            }

            /**
             * 停机或重连时主动关闭底层连接，确保 closeSignal 能及时结束。
             */
            override suspend fun close(reason: String) {
                outgoingChannel.close()
                sessionRef.get()?.close(CloseReason(CloseReason.Codes.NORMAL, reason))
                sessionJob.cancel()
                job.cancelAndJoin()
                if (!closeSignal.isCompleted) {
                    closeSignal.complete(
                        QQOfficialGatewayClose(
                            code = CloseReason.Codes.NORMAL.code.toInt(),
                            reason = reason,
                        ),
                    )
                }
            }
        }
    }

    /**
     * 导出 QQ 官方底层 OkHttp 连接池、调度器与网关会话活跃态，供 guardian 独立记录平台 transport 资源。
     */
    override fun runtimeObservability(): PlatformObservabilitySnapshot {
        return PlatformObservabilitySnapshot(
            clients = listOf(
                PlatformHttpClientSnapshot(
                    adapterName = "qq_official",
                    transportName = "gateway",
                    connectionCount = connectionPool.connectionCount(),
                    idleConnectionCount = connectionPool.idleConnectionCount(),
                    queuedCallsCount = okHttpDispatcher.queuedCallsCount(),
                    runningCallsCount = okHttpDispatcher.runningCallsCount(),
                    webSocketSessionActive = webSocketSessionActive.get(),
                ),
            ),
        )
    }

    /**
     * 关闭 Ktor 客户端，释放 HTTP 与 WebSocket 资源。
     */
    override fun close() {
        transportScope.cancel()
        client.close()
        connectionPool.evictAll()
        okHttpDispatcher.executorService.shutdown()
    }

    /**
     * 统一按 HTTP 状态码解析 QQ 官方返回值，失败时抛出带响应体的异常。
     */
    private fun parseJsonResponse(statusCode: Int, text: String): JsonObject {
        if (statusCode !in 200..299) {
            throw QQOfficialHttpException(statusCode, text)
        }
        if (text.isBlank()) {
            return JsonObject(emptyMap())
        }
        val parsed = json.parseToJsonElement(text)
        return parsed as? JsonObject ?: JsonObject(emptyMap())
    }
}

@Serializable
internal data class QQOfficialGatewayResponse(
    val url: String = "",
    val shards: Int = 1,
    @SerialName("session_start_limit")
    val sessionStartLimit: QQOfficialSessionStartLimit = QQOfficialSessionStartLimit(),
)

@Serializable
internal data class QQOfficialSessionStartLimit(
    val total: Int = 0,
    val remaining: Int = 0,
    @SerialName("reset_after")
    val resetAfter: Int = 0,
    @SerialName("max_concurrency")
    val maxConcurrency: Int = 1,
)

@Serializable
internal data class QQOfficialGatewayFrame(
    val op: Int,
    val s: Int? = null,
    val t: String? = null,
    val id: String? = null,
    val d: JsonElement? = null,
)

@Serializable
internal data class QQOfficialHelloData(
    @SerialName("heartbeat_interval")
    val heartbeatInterval: Int = 30_000,
)

@Serializable
internal data class QQOfficialReadyData(
    val version: Int = 0,
    @SerialName("session_id")
    val sessionId: String = "",
    val user: QQOfficialReadyUser = QQOfficialReadyUser(),
    val shard: List<Int> = emptyList(),
)

@Serializable
internal data class QQOfficialReadyUser(
    val id: String = "",
    val username: String = "",
    val bot: Boolean = true,
)

@Serializable
internal data class QQOfficialMessageEvent(
    val id: String = "",
    val content: String = "",
    @SerialName("group_openid")
    val groupOpenId: String? = null,
    val author: QQOfficialAuthor = QQOfficialAuthor(),
    val attachments: List<QQOfficialAttachment> = emptyList(),
)

@Serializable
internal data class QQOfficialAuthor(
    @SerialName("openid")
    val openId: String? = null,
    @SerialName("member_openid")
    val memberOpenId: String? = null,
    @SerialName("user_openid")
    val userOpenId: String? = null,
)

@Serializable
internal data class QQOfficialAttachment(
    val url: String? = null,
)

@Serializable
internal data class QQOfficialGroupManageEvent(
    @SerialName("group_openid")
    val groupOpenId: String? = null,
    @SerialName("member_openid")
    val memberOpenId: String? = null,
    @SerialName("op_member_openid")
    val opMemberOpenId: String? = null,
    val timestamp: Long? = null,
)

@Serializable
internal data class QQOfficialC2CManageEvent(
    val openid: String? = null,
)

// 订阅状态事件只用于日志与 seq 提交，状态字段保持宽松以兼容平台侧枚举形态变化。
@Serializable
internal data class QQOfficialSubscribeMessageStatusEvent(
    val openid: String? = null,
    @SerialName("user_openid")
    val userOpenId: String? = null,
    @SerialName("group_openid")
    val groupOpenId: String? = null,
    @SerialName("subscribe_id")
    val subscribeId: String? = null,
    val status: JsonElement? = null,
    val timestamp: JsonElement? = null,
)

@Serializable
internal data class QQOfficialMedia(
    @SerialName("file_uuid")
    val fileUuid: String? = null,
    @SerialName("file_info")
    val fileInfo: String = "",
    val ttl: Int? = null,
)
