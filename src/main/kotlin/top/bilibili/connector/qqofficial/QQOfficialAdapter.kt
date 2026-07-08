package top.bilibili.connector.qqofficial

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.flow.collect
import org.slf4j.LoggerFactory
import top.bilibili.config.QQOfficialConfig
import top.bilibili.connector.CapabilityGuardResult
import top.bilibili.connector.CapabilityRequest
import top.bilibili.connector.ConnectionBackoffPolicy
import top.bilibili.connector.ImageSource
import top.bilibili.connector.OutgoingPart
import top.bilibili.connector.PlatformAdapter
import top.bilibili.connector.PlatformChatType
import top.bilibili.connector.PlatformCapability
import top.bilibili.connector.PlatformContact
import top.bilibili.connector.PlatformInboundMessage
import top.bilibili.connector.PlatformObservabilitySnapshot
import top.bilibili.connector.PlatformRuntimeStatus
import top.bilibili.connector.PlatformType
import top.bilibili.utils.toSubject
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal class QQOfficialAdapter(
    private val config: QQOfficialConfig = QQOfficialConfig(),
    internal val transport: QQOfficialTransport = KtorQQOfficialTransport(),
    private val imageUrlResolver: suspend (ImageSource) -> String? = ::defaultImageUrlResolver,
    private val currentTimeMillis: () -> Long = { System.currentTimeMillis() },
    internal val reachableContactTtlMillis: Long = DEFAULT_REACHABLE_CONTACT_TTL_MILLIS,
    internal val reachableContactsMaxSize: Int = DEFAULT_REACHABLE_CONTACTS_MAX_SIZE,
    internal val botQpmLimit: Int = DEFAULT_BOT_QPM_LIMIT,
    internal val groupQpmLimit: Int = DEFAULT_GROUP_QPM_LIMIT,
    internal val groupPassiveReplyWindowMillis: Long = DEFAULT_GROUP_PASSIVE_REPLY_WINDOW_MILLIS,
    internal val privatePassiveReplyWindowMillis: Long = DEFAULT_PRIVATE_PASSIVE_REPLY_WINDOW_MILLIS,
) : PlatformAdapter {
    private val logger = LoggerFactory.getLogger(QQOfficialAdapter::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val authMutex = Mutex()
    private val reachableContactsMutex = Mutex()
    private val connected = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val reconnectGuard = AtomicBoolean(false)
    private val gatewayReconnectDisabled = AtomicBoolean(false)
    private val reconnectAttempts = AtomicInteger(0)
    private val reconnectBackoffPolicy = ConnectionBackoffPolicy(
        baseDelayMillis = 3_000L,
        maxDelayMillis = 60_000L,
    )
    private val inboundPressureActive = AtomicBoolean(false)
    private val inboundDroppedEvents = AtomicInteger(0)
    private val effectiveReachableContactTtlMillis = reachableContactTtlMillis.coerceAtLeast(1L)
    private val effectiveReachableContactsMaxSize = reachableContactsMaxSize.coerceAtLeast(1)
    private val reachableContacts = LinkedHashMap<String, Long>()
    private val passiveReplyDeadlines = LinkedHashMap<String, Long>()
    private val groupRateLimiters = LinkedHashMap<String, QQOfficialQpmLimiter>()
    private val botRateLimiter = QQOfficialQpmLimiter(botQpmLimit, currentTimeMillis)
    private val replyMsgSeqMutex = Mutex()
    private val replyMsgSeqByMessageId = LinkedHashMap<String, Int>()
    private val _eventFlow = MutableSharedFlow<PlatformInboundMessage>(replay = 0, extraBufferCapacity = 64)
    private var gatewaySession: QQOfficialGatewaySession? = null
    private var gatewayCollectJob: Job? = null
    private var gatewayCloseWatchJob: Job? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var readySignal: CompletableDeferred<Unit>? = null
    private var accessToken: String? = null
    private var accessTokenExpireAtMillis: Long = 0L
    private var sessionId: String? = null
    private var lastSeq: Int? = null
    private var selfOpenId: String = ""
    private val heartbeatInFlight = AtomicBoolean(false)
    private val lastHeartbeatAckAtMillis = AtomicLong(0L)
    private val lastHeartbeatSentAtMillis = AtomicLong(0L)

    override val eventFlow: Flow<PlatformInboundMessage> = _eventFlow.asSharedFlow()

    /**
     * QQ 官方显式声明仅支持基础发送/图片/回复能力，@全体等特性继续通过 guard 走降级或停止。
     */
    override fun declaredCapabilities(): Set<PlatformCapability> {
        return setOf(
            PlatformCapability.SEND_MESSAGE,
            PlatformCapability.SEND_IMAGES,
            PlatformCapability.REPLY,
            PlatformCapability.LINK_RESOLVE,
        )
    }

    /**
     * QQ 官方对 @全体给出显式 guard reason，图片能力交给 file_data/URL 解析路径判断。
     */
    override suspend fun guardCapability(request: CapabilityRequest): CapabilityGuardResult {
        if (request.capability == PlatformCapability.AT_ALL) {
            return CapabilityGuardResult.Unsupported(reason = "QQ Official does not support @全体")
        }
        return super.guardCapability(request)
    }

    /**
     * 启动 QQ 官方网关并等待首轮握手完成，避免外层把仅创建对象误判为已可用连接。
     */
    override fun start() {
        if (!started.compareAndSet(false, true)) {
            logger.info("QQ 官方适配器已在运行中，忽略重复启动")
            return
        }
        try {
            check(hasCredentials()) { "QQ 官方平台缺少 app_id 或 app_secret，无法启动" }
            gatewayReconnectDisabled.set(false)

            // 启动阶段等待首轮 Hello/Ready 完成，避免外层把“骨架启动”误判成“平台可用”。
            runBlocking {
                connectGateway(initialBootstrap = true)
            }
            logger.info("QQ 官方适配器已完成首轮网关握手")
        } catch (throwable: Throwable) {
            started.set(false)
            connected.set(false)
            throw throwable
        }
    }

    /**
     * 停止 QQ 官方适配器并回收网关、缓存与传输资源，避免退场后保留旧会话状态。
     */
    override suspend fun stop() {
        if (!started.compareAndSet(true, false)) {
            return
        }
        connected.set(false)
        reconnectJob?.cancelAndJoin()
        heartbeatJob?.cancelAndJoin()
        gatewayCloseWatchJob?.cancelAndJoin()
        gatewayCollectJob?.cancelAndJoin()
        gatewaySession?.close("adapter stop")
        clearReachableContacts()
        clearReplySequences()
        scope.cancel()
        transport.close()
    }

    /**
     * 按 QQ 官方平台约束执行发送，并在图片或 @全体 不可用时显式走失败或文本降级。
     */
    override suspend fun sendMessage(contact: PlatformContact, message: List<OutgoingPart>): Boolean {
        if (contact.platform != PlatformType.QQ_OFFICIAL) return false
        if (!started.get() || !connected.get()) return false

        return runCatching {
            val sendPlan = buildSendPlan(message)
            if (!canSendForPlan(contact, sendPlan)) {
                logger.warn("QQ 官方联系人当前不可达或已超过被动回复窗口，跳过发送: {}", contact.toSubject())
                return false
            }
            if (sendPlan.unsupportedAtAll) {
                logger.warn("QQ 官方平台不支持 @全体，要求上层触发显式降级: {}", contact.toSubject())
                return false
            }
            if (sendPlan.replyId != null && !canReply(contact)) {
                logger.warn("QQ 官方联系人当前不支持回复消息: {}", contact.toSubject())
                return false
            }

            if (sendPlan.images.isEmpty()) {
                if (sendPlan.content.isBlank()) {
                    return false
                }
                return postMessage(contact, msgType = 0, content = sendPlan.content, media = null, replyId = sendPlan.replyId)
            }

            val mediaUploads = resolveMediaUploads(sendPlan.images)
            if (mediaUploads.size != sendPlan.images.size) {
                if (sendPlan.content.isBlank()) {
                    logger.warn("QQ 官方平台当前无法解析全部图片，且消息无可降级文本: {}", contact.toSubject())
                    return false
                }
                // 图片上传前解析失败时只降级为文本，避免构造缺少 media 的富媒体请求。
                logger.warn("QQ 官方平台图片解析失败，已降级为纯文本发送: {}", contact.toSubject())
                return postMessage(contact, msgType = 0, content = sendPlan.content, media = null, replyId = sendPlan.replyId)
            }

            mediaUploads.forEachIndexed { index, mediaUpload ->
                val media = uploadMedia(contact, mediaUpload)
                // 文本和回复只放在第一条图片消息里，避免多图拆分后把同一段内容重复发给用户。
                val content = if (index == 0) sendPlan.content else ""
                val sent = postMessage(
                    contact = contact,
                    msgType = 7,
                    content = content,
                    media = media,
                    replyId = if (index == 0) sendPlan.replyId else null,
                )
                if (!sent) return false
            }
            true
        }.onFailure {
            logger.error("QQ 官方发送失败: ${contact.toSubject()} - ${it.message}", it)
        }.getOrDefault(false)
    }

    /**
     * 汇总 QQ 官方当前连接态、重连次数与入站背压状态，供运行时监控统一读取。
     */
    override fun runtimeStatus(): PlatformRuntimeStatus {
        return PlatformRuntimeStatus(
            connected = connected.get(),
            reconnectAttempts = reconnectAttempts.get(),
            inboundPressureActive = inboundPressureActive.get(),
            inboundDroppedEvents = inboundDroppedEvents.get(),
        )
    }

    /**
     * 透传 QQ 官方 transport 的底层 OkHttp 资源快照，供 manager 与 guardian 统一采集平台观测信息。
     */
    override fun runtimeObservability(): PlatformObservabilitySnapshot {
        return transport.runtimeObservability()
    }

    // QQ 官方的群聊/私聊可达性依赖运行时已接受的会话，避免业务层误判“所有 openid 都可主动发送”。
    /**
     * 仅把运行期已建立过会话的联系人视为可达，避免对任意 openid 盲目尝试主动发送。
     */
    override suspend fun isContactReachable(contact: PlatformContact): Boolean {
        if (contact.platform != PlatformType.QQ_OFFICIAL) return false
        if (!connected.get()) return false
        return reachableContactsMutex.withLock {
            val now = currentTimeMillis()
            pruneReachableContactsLocked(now)
            val subject = contact.toSubject()
            reachableContacts.containsKey(subject) || isPassiveReplyAllowedLocked(subject, now)
        }
    }

    /**
     * QQ 官方主动发送仍依赖运行时会话可达性，这里显式暴露业务层使用的发送能力判断。
     */
    override suspend fun canSendMessage(contact: PlatformContact): Boolean {
        return isContactReachable(contact)
    }

    /**
     * 发送前结合回复窗口判断本次计划是否允许执行，避免无 replyId 的能力查询误放行过期回复。
     */
    private suspend fun canSendForPlan(contact: PlatformContact, sendPlan: QQOfficialSendPlan): Boolean {
        if (!canSendMessage(contact)) return false
        if (sendPlan.replyId != null && !isPassiveReplyAllowed(contact)) return false
        return true
    }

    /**
     * QQ 官方支持公网 URL 与 file_data 上传，本地/二进制图片在这里提前验证可解析性。
     */
    override suspend fun canSendImages(contact: PlatformContact, images: List<ImageSource>): Boolean {
        if (!canSendMessage(contact)) return false
        if (images.isEmpty()) return true
        return resolveMediaUploads(images).size == images.size
    }

    /**
     * QQ 官方回复必须处于被动回复窗口内，避免对过期 msg_id 继续发起无效请求。
     */
    override suspend fun canReply(contact: PlatformContact): Boolean {
        return connected.get() && isPassiveReplyAllowed(contact)
    }

    // QQ 官方平台没有 OneBot 风格的 @全体能力，这里始终显式返回 false 触发上层降级。
    /**
     * 显式声明 QQ 官方不支持 OneBot 风格的 @全体，要求上层改走降级或提示逻辑。
     */
    override suspend fun canAtAll(contact: PlatformContact): Boolean = false

    private fun hasCredentials(): Boolean {
        return config.appId.isNotBlank() &&
            config.appSecret.isNotBlank()
    }

    /**
     * 建立首轮或重连网关，并在握手完成后切换到新的会话状态。
     */
    private suspend fun connectGateway(initialBootstrap: Boolean) {
        if (gatewayReconnectDisabled.get()) return
        val gateway = fetchGateway()
        val token = currentAccessToken(forceRefresh = false)
        val newReadySignal = CompletableDeferred<Unit>()
        readySignal = newReadySignal
        val session = transport.openGateway(
            url = gateway.url,
            headers = apiHeaders(token),
        )

        gatewaySession = session
        gatewayCollectJob?.cancelAndJoin()
        gatewayCollectJob = scope.launch {
            session.incoming.collect { text ->
                handleGatewayFrame(text)
            }
        }
        gatewayCloseWatchJob?.cancelAndJoin()
        gatewayCloseWatchJob = scope.launch {
            val closeEvent = session.closeSignal.await()
            if (!started.get()) return@launch
            connected.set(false)
            if (closeEvent.failure != null) {
                logger.warn("QQ 官方网关连接异常断开: ${closeEvent.failure.message}", closeEvent.failure)
            } else if (closeEvent.code != null) {
                logger.warn("QQ 官方网关 Close: code={}, reason={}", closeEvent.code, closeEvent.reason.orEmpty())
            } else {
                logger.info("QQ 官方网关连接已关闭")
            }
            handleGatewayClose(closeEvent)
        }

        try {
            withTimeout(15_000) {
                // 首轮或重连都必须等 READY/RESUMED，避免外层拿到一个尚未完成鉴权的会话。
                newReadySignal.await()
            }
            reconnectAttempts.set(0)
            reconnectGuard.set(false)
        } catch (throwable: Throwable) {
            session.close("bootstrap timeout")
            if (initialBootstrap) {
                throw throwable
            }
            if (!gatewayReconnectDisabled.get()) {
                requestReconnect()
            }
        }
    }

    /**
     * 解析网关帧并驱动 hello/ready/业务事件/管理事件的运行时状态更新。
     */
    private suspend fun handleGatewayFrame(text: String) {
        val frame = QQOfficialJson.decodeFromString<QQOfficialGatewayFrame>(text)
        when (frame.op) {
            0 -> {
                if (handleDispatchFrame(frame)) {
                    commitSeq(frame)
                }
            }
            7 -> {
                logger.warn("QQ 官方网关要求客户端重连")
                requestReconnect()
            }
            9 -> {
                logger.warn("QQ 官方网关返回 invalid session，清理会话后重连")
                clearGatewayResumeState()
                requestReconnect()
            }
            10 -> handleHelloFrame(frame.d)
            11 -> recordHeartbeatAck()
            else -> logger.debug("忽略未处理的 QQ 官方网关 op={}", frame.op)
        }
    }

    /**
     * 收到 Hello 后立刻启动心跳，并根据是否持有 session_id 决定 identify 还是 resume。
     */
    private suspend fun handleHelloFrame(payload: JsonElement?) {
        val hello = payload?.let { QQOfficialJson.decodeFromJsonElement<QQOfficialHelloData>(it) } ?: QQOfficialHelloData()
        startHeartbeat(hello.heartbeatInterval)
        if (sessionId.isNullOrBlank()) {
            sendIdentify()
        } else {
            sendResume()
        }
    }

    /**
     * 分发 Ready/Resumed/消息事件/管理事件，统一更新运行时联系人状态。
     */
    private suspend fun handleDispatchFrame(frame: QQOfficialGatewayFrame): Boolean {
        return when (frame.t) {
            "READY" -> {
                val ready = frame.d?.let { QQOfficialJson.decodeFromJsonElement<QQOfficialReadyData>(it) } ?: return false
                sessionId = ready.sessionId
                selfOpenId = ready.user.id
                connected.set(true)
                readySignal?.complete(Unit)
                logger.info("QQ 官方网关 READY: self={}", selfOpenId)
                true
            }
            "RESUMED" -> {
                connected.set(true)
                readySignal?.complete(Unit)
                logger.info("QQ 官方网关已恢复会话: session={}", sessionId)
                true
            }
            "GROUP_AT_MESSAGE_CREATE" -> {
                val inbound = normalizeMessage(frame, PlatformChatType.GROUP) ?: return false
                markPassiveReplyWindow(inbound.chatContact)
                recordInboundEvent(inbound)
            }
            "C2C_MESSAGE_CREATE" -> {
                val inbound = normalizeMessage(frame, PlatformChatType.PRIVATE) ?: return false
                markPassiveReplyWindow(inbound.chatContact)
                recordInboundEvent(inbound)
            }
            "GROUP_ADD_ROBOT", "GROUP_MSG_RECEIVE" -> {
                val event = frame.d?.let { QQOfficialJson.decodeFromJsonElement<QQOfficialGroupManageEvent>(it) } ?: return false
                event.groupOpenId?.let { groupOpenId ->
                    markReachable(PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.GROUP, groupOpenId))
                }
                true
            }
            "GROUP_DEL_ROBOT", "GROUP_MSG_REJECT" -> {
                val event = frame.d?.let { QQOfficialJson.decodeFromJsonElement<QQOfficialGroupManageEvent>(it) } ?: return false
                event.groupOpenId?.let { groupOpenId ->
                    markUnreachable(PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.GROUP, groupOpenId))
                }
                true
            }
            "FRIEND_ADD", "C2C_MSG_RECEIVE" -> {
                val event = frame.d?.let { QQOfficialJson.decodeFromJsonElement<QQOfficialC2CManageEvent>(it) } ?: return false
                event.openid?.let { openId ->
                    markReachable(PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.PRIVATE, openId))
                }
                true
            }
            "FRIEND_DEL", "C2C_MSG_REJECT" -> {
                val event = frame.d?.let { QQOfficialJson.decodeFromJsonElement<QQOfficialC2CManageEvent>(it) } ?: return false
                event.openid?.let { openId ->
                    markUnreachable(PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.PRIVATE, openId))
                }
                true
            }
            else -> {
                logger.debug("忽略未订阅的 QQ 官方 dispatch t={}", frame.t)
                true
            }
        }
    }

    /**
     * 将 QQ 官方 dispatch 消息归一化为平台无关的入站模型，供命令链与监听链直接消费。
     */
    private fun normalizeMessage(frame: QQOfficialGatewayFrame, chatType: PlatformChatType): PlatformInboundMessage? {
        val message = frame.d?.let { QQOfficialJson.decodeFromJsonElement<QQOfficialMessageEvent>(it) } ?: return null
        val groupOpenId = message.groupOpenId
        val memberOpenId = message.author.memberOpenId
        val userOpenId = message.author.userOpenId
        val chatContact = when (chatType) {
            PlatformChatType.GROUP -> {
                val requiredGroupOpenId = groupOpenId ?: return null
                PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.GROUP, requiredGroupOpenId)
            }
            PlatformChatType.PRIVATE -> {
                val requiredUserOpenId = userOpenId ?: return null
                PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.PRIVATE, requiredUserOpenId)
            }
        }
        val senderContact = when (chatType) {
            PlatformChatType.GROUP -> chatContact
            PlatformChatType.PRIVATE -> {
                val requiredUserOpenId = userOpenId ?: return null
                PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.PRIVATE, requiredUserOpenId)
            }
        }
        val searchTexts = buildList {
            message.content.trim().takeIf { it.isNotEmpty() }?.let(::add)
            message.attachments.mapNotNullTo(this) { attachment ->
                attachment.url?.trim()?.takeIf { it.isNotEmpty() }
            }
        }.distinct()

        return PlatformInboundMessage(
            platform = PlatformType.QQ_OFFICIAL,
            chatType = chatType,
            chatContact = chatContact,
            senderContact = senderContact,
            selfContact = PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.PRIVATE, selfOpenId),
            messageText = message.content,
            searchTexts = searchTexts,
            hasMention = chatType == PlatformChatType.GROUP,
            fromSelf = listOfNotNull(userOpenId, memberOpenId).any { it == selfOpenId },
            rawPayload = frame,
            eventId = frame.id,
            messageId = message.id.ifBlank { null },
            metadata = buildOpenIdMetadata(groupOpenId, memberOpenId, userOpenId),
        )
    }

    /**
     * 显式保留 QQ 官方三类 openid，避免 member_openid 与 user_openid 被误当成同一种身份。
     */
    private fun buildOpenIdMetadata(
        groupOpenId: String?,
        memberOpenId: String?,
        userOpenId: String?,
    ): Map<String, String> {
        return buildMap {
            groupOpenId?.takeIf { it.isNotBlank() }?.let { put("group_openid", it) }
            memberOpenId?.takeIf { it.isNotBlank() }?.let { put("member_openid", it) }
            userOpenId?.takeIf { it.isNotBlank() }?.let { put("user_openid", it) }
        }
    }

    /**
     * 启动 QQ 官方网关心跳，维持主动发送所需的在线状态。
     */
    private fun startHeartbeat(intervalMillis: Int) {
        heartbeatJob?.cancel()
        val effectiveIntervalMillis = intervalMillis.coerceAtLeast(1)
        heartbeatInFlight.set(false)
        lastHeartbeatAckAtMillis.set(currentTimeMillis())
        lastHeartbeatSentAtMillis.set(0L)
        heartbeatJob = scope.launch {
            while (started.get() && !gatewayReconnectDisabled.get()) {
                val now = currentTimeMillis()
                if (heartbeatInFlight.get()) {
                    val overdueMillis = now - lastHeartbeatSentAtMillis.get()
                    if (overdueMillis >= effectiveIntervalMillis * 2L) {
                        logger.warn("QQ 官方心跳 ACK 超时，关闭当前网关会话触发重连")
                        connected.set(false)
                        gatewaySession?.close("heartbeat ack timeout")
                        requestReconnect()
                        return@launch
                    }
                    delay(effectiveIntervalMillis.toLong())
                    continue
                }
                val payload = buildJsonObject {
                    put("op", 1)
                    put("d", lastSeq?.let(::JsonPrimitive) ?: JsonNull)
                }
                val sent = runCatching {
                    gatewaySession?.sendText(payload.toString())
                }.onFailure {
                    logger.warn("QQ 官方心跳发送失败，准备重连: {}", it.message)
                }.isSuccess
                if (!sent) {
                    connected.set(false)
                    requestReconnect()
                    return@launch
                }
                heartbeatInFlight.set(true)
                lastHeartbeatSentAtMillis.set(now)
                delay(effectiveIntervalMillis.toLong())
            }
        }
    }

    /**
     * 发送 identify，声明当前仅监听公域群聊/C2C 事件。
     */
    private suspend fun sendIdentify() {
        val token = currentAccessToken(forceRefresh = false)
        val payload = buildJsonObject {
            put("op", 2)
            put("d", buildJsonObject {
                put("token", "QQBot $token")
                put("intents", QQ_OFFICIAL_GATEWAY_INTENT_PUBLIC_MESSAGES)
                put("shard", buildJsonArray {
                    add(JsonPrimitive(0))
                    add(JsonPrimitive(1))
                })
                put("properties", buildJsonObject {
                    put("\$os", "windows")
                    put("\$browser", "hoshimi-cat-bot")
                    put("\$device", "hoshimi-cat-bot")
                })
            })
        }
        gatewaySession?.sendText(payload.toString())
    }

    /**
     * 发送 resume，在网关允许续传时尽量保留上一条 seq 的上下文。
     */
    private suspend fun sendResume() {
        val token = currentAccessToken(forceRefresh = false)
        val payload = buildJsonObject {
            put("op", 6)
            put("d", buildJsonObject {
                put("token", "QQBot $token")
                put("session_id", sessionId.orEmpty())
                put("seq", lastSeq ?: 0)
            })
        }
        gatewaySession?.sendText(payload.toString())
    }

    /**
     * 获取当前 access token，并在接近过期时自动刷新。
     */
    private suspend fun currentAccessToken(forceRefresh: Boolean): String = authMutex.withLock {
        val now = currentTimeMillis()
        val cached = accessToken
        if (!forceRefresh && cached != null && now < accessTokenExpireAtMillis) {
            return cached
        }

        val response = transport.postJson(
            url = QQ_OFFICIAL_TOKEN_URL,
            body = buildJsonObject {
                put("appId", config.appId)
                put("clientSecret", config.appSecret)
            },
            headers = emptyMap(),
        )
        val token = response["access_token"]?.jsonPrimitive?.content.orEmpty()
        val expiresInSeconds = response["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        check(token.isNotBlank()) { "QQ 官方 access token 获取失败: $response" }

        // 官方会在旧 token 过期前 60 秒返回新 token，这里按同一窗口提前刷新。
        accessToken = token
        accessTokenExpireAtMillis = now + (expiresInSeconds * 1000L) - ACCESS_TOKEN_REFRESH_LEEWAY_MILLIS
        return token
    }

    /**
     * 读取网关接入点，确保启动时能明确知道握手目标而不是静默降级。
     */
    private suspend fun fetchGateway(): QQOfficialGatewayResponse {
        val response = authenticatedGetJson("$QQ_OFFICIAL_API_BASE/gateway/bot")
        val gateway = QQOfficialJson.decodeFromJsonElement<QQOfficialGatewayResponse>(response)
        check(gateway.url.isNotBlank()) { "QQ 官方 gateway/bot 返回为空: $response" }
        return gateway
    }

    /**
     * 将发送片段收敛为 QQ 官方最小可用发送计划，避免业务层继续感知平台差异。
     */
    private fun buildSendPlan(parts: List<OutgoingPart>): QQOfficialSendPlan {
        val textBuilder = StringBuilder()
        val images = mutableListOf<ImageSource>()
        var replyId: String? = null
        var unsupportedAtAll = false

        parts.forEach { part ->
            when (part) {
                is OutgoingPart.Text -> textBuilder.append(part.text)
                is OutgoingPart.Image -> images += part.source
                is OutgoingPart.Reply -> replyId = part.messageId
                is OutgoingPart.MentionAll -> unsupportedAtAll = true
            }
        }

        return QQOfficialSendPlan(
            content = textBuilder.toString(),
            images = images,
            replyId = replyId,
            unsupportedAtAll = unsupportedAtAll,
        )
    }

    /**
     * 解析 QQ 官方富媒体上传来源，优先使用外部 URL，其次用 file_data 承载本地/二进制图片。
     */
    private suspend fun resolveMediaUploads(images: List<ImageSource>): List<QQOfficialMediaUploadSource> {
        return images.mapNotNull { source -> resolveMediaUpload(source) }
    }

    /**
     * 将单个图片来源归一到 QQ 官方 files 接口可接受的 url 或 file_data 字段。
     */
    private suspend fun resolveMediaUpload(source: ImageSource): QQOfficialMediaUploadSource? {
        val resolvedUrl = imageUrlResolver(source)?.trim().orEmpty()
        if (resolvedUrl.startsWith("http://") || resolvedUrl.startsWith("https://")) {
            return QQOfficialMediaUploadSource.Url(resolvedUrl)
        }
        return when (source) {
            is ImageSource.RemoteUrl -> null
            is ImageSource.LocalFile -> encodeLocalFileAsBase64(source.path)?.let(QQOfficialMediaUploadSource::FileData)
            is ImageSource.Binary -> QQOfficialMediaUploadSource.FileData(encodeBytesAsBase64(source.bytes))
        }
    }

    /**
     * 上传单张图片并返回 media 描述，供随后的 msg_type=7 发送请求复用。
     */
    private suspend fun uploadMedia(contact: PlatformContact, source: QQOfficialMediaUploadSource): QQOfficialMedia {
        val response = authenticatedPostJson(
            url = filesUrl(contact),
            body = buildJsonObject {
                put("file_type", 1)
                when (source) {
                    is QQOfficialMediaUploadSource.Url -> put("url", source.url)
                    is QQOfficialMediaUploadSource.FileData -> put("file_data", source.fileData)
                }
                put("srv_send_msg", false)
            },
        )
        return QQOfficialJson.decodeFromJsonElement(response)
    }

    /**
     * 向群聊或 C2C 发送一条最终消息，请求体统一收口在这里。
     */
    private suspend fun postMessage(
        contact: PlatformContact,
        msgType: Int,
        content: String,
        media: QQOfficialMedia?,
        replyId: String?,
    ): Boolean {
        if (!consumeOutgoingQuota(contact)) {
            logger.warn("QQ 官方发送触发平台侧 qpm guard: {}", contact.toSubject())
            return false
        }
        val replySeq = replyId?.takeIf { it.isNotBlank() }?.let { nextReplyMsgSeq(it) }
        authenticatedPostJson(
            url = messagesUrl(contact),
            body = buildJsonObject {
                put("msg_type", msgType)
                if (content.isNotBlank()) {
                    put("content", content)
                }
                if (!replyId.isNullOrBlank()) {
                    put("msg_id", replyId)
                    put("msg_seq", replySeq ?: 1)
                }
                if (media != null) {
                    put("media", QQOfficialJson.encodeToJsonElement(media))
                }
            },
        )
        return true
    }

    /**
     * 执行带 QQBot 鉴权的 GET 请求，遇到 token 失效只强制刷新并重试一次。
     */
    private suspend fun authenticatedGetJson(url: String): JsonElement {
        val firstToken = currentAccessToken(forceRefresh = false)
        return runCatching {
            transport.getJson(url, apiHeaders(firstToken))
        }.getOrElse { throwable ->
            if (!isAuthorizationFailure(throwable)) throw throwable
            val refreshedToken = currentAccessToken(forceRefresh = true)
            transport.getJson(url, apiHeaders(refreshedToken))
        }
    }

    /**
     * 执行带 QQBot 鉴权的 POST 请求，统一兜住 OpenAPI 401/鉴权失败后的单次 token 刷新。
     */
    private suspend fun authenticatedPostJson(url: String, body: JsonElement): JsonElement {
        val firstToken = currentAccessToken(forceRefresh = false)
        return runCatching {
            transport.postJson(url, body, apiHeaders(firstToken))
        }.getOrElse { throwable ->
            if (!isAuthorizationFailure(throwable)) throw throwable
            val refreshedToken = currentAccessToken(forceRefresh = true)
            transport.postJson(url, body, apiHeaders(refreshedToken))
        }
    }

    /**
     * 兼容 HTTP 401 和 QQ 官方响应体中的鉴权失败描述，避免只识别一种失败形态。
     */
    private fun isAuthorizationFailure(throwable: Throwable): Boolean {
        val httpFailure = throwable as? QQOfficialHttpException ?: return false
        if (httpFailure.statusCode == 401) return true
        val body = httpFailure.responseBody.lowercase()
        return body.contains("access token") ||
            body.contains("authorization") ||
            body.contains("unauthorized") ||
            body.contains("鉴权")
    }

    /**
     * 读取本地图片并编码为 QQ 官方 file_data 需要的原始 base64 字符串。
     */
    private suspend fun encodeLocalFileAsBase64(path: String): String? {
        return runCatching {
            val filePath = resolveLocalImagePath(path)
            withContext(Dispatchers.IO) {
                encodeBytesAsBase64(Files.readAllBytes(filePath))
            }
        }.onFailure {
            logger.warn("QQ 官方本地图片读取失败: path={}, reason={}", path, it.message)
        }.getOrNull()
    }

    /**
     * 兼容普通本地路径与 file:// URL，避免调用方需要先自行转换路径格式。
     */
    private fun resolveLocalImagePath(path: String): Path {
        return if (path.startsWith("file://")) {
            Path.of(URI(path))
        } else {
            Path.of(path)
        }
    }

    /**
     * 对二进制图片统一执行 base64 编码，供 file_data 上传路径复用。
     */
    private fun encodeBytesAsBase64(bytes: ByteArray): String {
        return Base64.getEncoder().encodeToString(bytes)
    }

    /**
     * 统一生成 OpenAPI 请求头，保持 Authorization 与 X-Union-Appid 一致。
     */
    private fun apiHeaders(token: String): Map<String, String> {
        return mapOf(
            "Authorization" to "QQBot $token",
            "X-Union-Appid" to config.appId,
            "User-Agent" to "hoshimi-cat-bot",
        )
    }

    /**
     * 记录已接受主动消息的联系人，供能力判断和后续发送入口复用。
     */
    private suspend fun markReachable(contact: PlatformContact) {
        val subject = contact.toSubject()
        val now = currentTimeMillis()
        reachableContactsMutex.withLock {
            // 先清掉已过期条目，再刷新当前联系人，避免 7x24 运行时缓存无界增长。
            pruneReachableContactsLocked(now)
            reachableContacts.remove(subject)
            reachableContacts[subject] = now
            enforceReachableContactLimitLocked()
        }
    }

    /**
     * 收到用户消息时开启被动回复窗口，群聊和单聊按官方限制使用不同有效期。
     */
    private suspend fun markPassiveReplyWindow(contact: PlatformContact) {
        val subject = contact.toSubject()
        val now = currentTimeMillis()
        val windowMillis = when (contact.type) {
            PlatformChatType.GROUP -> groupPassiveReplyWindowMillis
            PlatformChatType.PRIVATE -> privatePassiveReplyWindowMillis
        }.coerceAtLeast(1L)
        reachableContactsMutex.withLock {
            pruneReachableContactsLocked(now)
            passiveReplyDeadlines.remove(subject)
            passiveReplyDeadlines[subject] = now + windowMillis
            enforceReachableContactLimitLocked()
        }
    }

    /**
     * 在群退群/拒收等事件到来时移除联系人，避免继续误发主动消息。
     */
    private suspend fun markUnreachable(contact: PlatformContact) {
        reachableContactsMutex.withLock {
            val subject = contact.toSubject()
            reachableContacts.remove(subject)
            passiveReplyDeadlines.remove(subject)
        }
    }

    /**
     * 停机时显式清空联系人缓存，避免当前代 adapter 退场后仍短暂持有大批历史联系人状态。
     */
    private suspend fun clearReachableContacts() {
        reachableContactsMutex.withLock {
            reachableContacts.clear()
            passiveReplyDeadlines.clear()
            groupRateLimiters.clear()
        }
    }

    /**
     * 在同一把锁内统一执行过期回收与容量裁剪，保证联系人缓存始终受 TTL 和上限双重约束。
     */
    private fun pruneReachableContactsLocked(now: Long) {
        val expireBefore = now - effectiveReachableContactTtlMillis
        val iterator = reachableContacts.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value <= expireBefore) {
                iterator.remove()
            }
        }
        val passiveIterator = passiveReplyDeadlines.entries.iterator()
        while (passiveIterator.hasNext()) {
            val entry = passiveIterator.next()
            if (entry.value <= now) {
                passiveIterator.remove()
            }
        }
        enforceReachableContactLimitLocked()
    }

    /**
     * 缓存超上限时按最旧活跃时间淘汰，确保主动发送能力只保留最近接触的联系人。
     */
    private fun enforceReachableContactLimitLocked() {
        while (reachableContacts.size > effectiveReachableContactsMaxSize) {
            val oldestSubject = reachableContacts.entries.firstOrNull()?.key ?: return
            reachableContacts.remove(oldestSubject)
        }
        while (passiveReplyDeadlines.size > effectiveReachableContactsMaxSize) {
            val oldestSubject = passiveReplyDeadlines.entries.firstOrNull()?.key ?: return
            passiveReplyDeadlines.remove(oldestSubject)
        }
        while (groupRateLimiters.size > effectiveReachableContactsMaxSize) {
            val oldestSubject = groupRateLimiters.entries.firstOrNull()?.key ?: return
            groupRateLimiters.remove(oldestSubject)
        }
    }

    /**
     * 判断联系人是否仍处于被动回复窗口内，供 reply 能力和发送计划共同复用。
     */
    private suspend fun isPassiveReplyAllowed(contact: PlatformContact): Boolean {
        if (contact.platform != PlatformType.QQ_OFFICIAL) return false
        return reachableContactsMutex.withLock {
            val now = currentTimeMillis()
            pruneReachableContactsLocked(now)
            isPassiveReplyAllowedLocked(contact.toSubject(), now)
        }
    }

    /**
     * 在持锁状态下读取被动回复 deadline，避免窗口判断与过期清理发生竞态。
     */
    private fun isPassiveReplyAllowedLocked(subject: String, now: Long): Boolean {
        return (passiveReplyDeadlines[subject] ?: return false) > now
    }

    /**
     * 消耗 QQ 官方主动发送限频额度，按 bot 全局和单群两个维度同时守护。
     */
    private suspend fun consumeOutgoingQuota(contact: PlatformContact): Boolean {
        val now = currentTimeMillis()
        return reachableContactsMutex.withLock {
            pruneReachableContactsLocked(now)
            val groupLimiter = if (contact.type == PlatformChatType.GROUP) {
                groupRateLimiters.getOrPut(contact.toSubject()) {
                    QQOfficialQpmLimiter(groupQpmLimit, currentTimeMillis)
                }
            } else {
                null
            }
            if (!botRateLimiter.canAcquire(now) || groupLimiter?.canAcquire(now) == false) {
                return@withLock false
            }
            botRateLimiter.acquire(now)
            groupLimiter?.acquire(now)
            true
        }
    }

    /**
     * 为同一个 QQ 官方 msg_id 分配递增 msg_seq，避免重复回复同一消息时始终发送 1。
     */
    private suspend fun nextReplyMsgSeq(messageId: String): Int = replyMsgSeqMutex.withLock {
        val next = (replyMsgSeqByMessageId[messageId] ?: 0) + 1
        replyMsgSeqByMessageId.remove(messageId)
        replyMsgSeqByMessageId[messageId] = next
        while (replyMsgSeqByMessageId.size > DEFAULT_REPLY_SEQ_CACHE_MAX_SIZE) {
            val oldestMessageId = replyMsgSeqByMessageId.entries.firstOrNull()?.key ?: break
            replyMsgSeqByMessageId.remove(oldestMessageId)
        }
        next
    }

    /**
     * 停机时清空回复序列缓存，避免下一代 adapter 继承旧会话中的 msg_seq。
     */
    private suspend fun clearReplySequences() {
        replyMsgSeqMutex.withLock {
            replyMsgSeqByMessageId.clear()
        }
    }

    /**
     * 显式记录入站 SharedFlow 溢出，避免 QQ 官方链路在高负载下静默丢事件。
     */
    private fun recordInboundEvent(inbound: PlatformInboundMessage): Boolean {
        if (_eventFlow.tryEmit(inbound)) {
            inboundPressureActive.set(false)
            return true
        }
        inboundPressureActive.set(true)
        val droppedCount = inboundDroppedEvents.incrementAndGet()
        logger.warn("QQ 官方入站事件背压触发，已累计丢弃 $droppedCount 条事件")
        return false
    }

    /**
     * 只在 dispatch 处理成功后提交 seq，确保 resume 不会跳过尚未投递的事件。
     */
    private fun commitSeq(frame: QQOfficialGatewayFrame) {
        frame.s?.let { lastSeq = it }
    }

    /**
     * 收到 op=11 后确认上一轮心跳健康，供下一轮心跳继续发送。
     */
    private fun recordHeartbeatAck() {
        heartbeatInFlight.set(false)
        lastHeartbeatAckAtMillis.set(currentTimeMillis())
    }

    /**
     * 按 QQ 官方 close code 区分 resume、identify 与不可恢复的停止重连状态。
     */
    private fun handleGatewayClose(closeEvent: QQOfficialGatewayClose) {
        when (closeEvent.code) {
            4009 -> {
                logger.warn("QQ 官方网关 session timeout，保留 session_id/seq 优先 resume")
                requestReconnect()
            }
            4006, 4007 -> {
                logger.warn("QQ 官方网关 session 不可续传，清理 session_id/seq 后重新 identify")
                clearGatewayResumeState()
                requestReconnect()
            }
            4013, 4014 -> {
                disableGatewayReconnect("QQ 官方网关 intents 配置错误或无权限: code=${closeEvent.code}")
            }
            4914, 4915 -> {
                disableGatewayReconnect("QQ 官方机器人状态不可用或被封禁/下架: code=${closeEvent.code}")
            }
            else -> requestReconnect()
        }
    }

    /**
     * 清理 resume 必需状态，使下一次 Hello 走 identify 而不是带旧 seq 续传。
     */
    private fun clearGatewayResumeState() {
        sessionId = null
        lastSeq = null
    }

    /**
     * 遇到不可恢复 close code 时停止自动重连，并让 runtimeStatus 保持不可用。
     */
    private fun disableGatewayReconnect(reason: String) {
        gatewayReconnectDisabled.set(true)
        reconnectGuard.set(false)
        connected.set(false)
        readySignal?.completeExceptionally(IllegalStateException(reason))
        logger.error(reason)
    }

    /**
     * 统一调度重连，避免同一时刻并发建立多条网关连接。
     */
    private fun requestReconnect() {
        if (!started.get()) return
        if (gatewayReconnectDisabled.get()) return
        reconnectGuard.set(true)
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            runReconnectLoop()
        }
    }

    /**
     * 使用单一重连循环串行处理 QQ 官方重连，避免递归调度造成重试风暴。
     */
    private suspend fun runReconnectLoop() {
        while (started.get() && reconnectGuard.get() && !gatewayReconnectDisabled.get()) {
            val attempt = reconnectAttempts.incrementAndGet()
            // 先停掉旧代际的心跳和收帧协程，避免新旧连接并发写同一份 session 状态。
            heartbeatJob?.cancelAndJoin()
            gatewayCollectJob?.cancelAndJoin()
            gatewaySession?.close("reconnect")
            val backoffDelay = reconnectBackoffPolicy.nextDelayMillis(attempt)
            delay(backoffDelay)
            val connectedNow = runCatching {
                connectGateway(initialBootstrap = false)
                true
            }.getOrElse {
                logger.error("QQ 官方网关重连失败: ${it.message}", it)
                false
            }
            if (connectedNow) {
                reconnectGuard.set(false)
                return
            }
        }
    }

    /**
     * 根据联系人类型拼接消息发送地址，避免业务层继续拼接群/私聊路径。
     */
    private fun messagesUrl(contact: PlatformContact): String {
        return when (contact.type) {
            PlatformChatType.GROUP -> "$QQ_OFFICIAL_API_BASE/v2/groups/${contact.id}/messages"
            PlatformChatType.PRIVATE -> "$QQ_OFFICIAL_API_BASE/v2/users/${contact.id}/messages"
        }
    }

    /**
     * 根据联系人类型拼接富媒体上传地址，与发送地址保持同一命名约定。
     */
    private fun filesUrl(contact: PlatformContact): String {
        return when (contact.type) {
            PlatformChatType.GROUP -> "$QQ_OFFICIAL_API_BASE/v2/groups/${contact.id}/files"
            PlatformChatType.PRIVATE -> "$QQ_OFFICIAL_API_BASE/v2/users/${contact.id}/files"
        }
    }

    companion object {
        internal const val DEFAULT_REACHABLE_CONTACT_TTL_MILLIS: Long = 24L * 60L * 60L * 1000L
        internal const val DEFAULT_REACHABLE_CONTACTS_MAX_SIZE: Int = 10_000
        internal const val DEFAULT_BOT_QPM_LIMIT: Int = 180
        internal const val DEFAULT_GROUP_QPM_LIMIT: Int = 20
        internal const val DEFAULT_GROUP_PASSIVE_REPLY_WINDOW_MILLIS: Long = 5L * 60L * 1000L
        internal const val DEFAULT_PRIVATE_PASSIVE_REPLY_WINDOW_MILLIS: Long = 60L * 60L * 1000L
        internal const val ACCESS_TOKEN_REFRESH_LEEWAY_MILLIS: Long = 60L * 1000L
        private const val DEFAULT_REPLY_SEQ_CACHE_MAX_SIZE: Int = 10_000
    }
}

private data class QQOfficialSendPlan(
    val content: String,
    val images: List<ImageSource>,
    val replyId: String?,
    val unsupportedAtAll: Boolean,
)

private sealed interface QQOfficialMediaUploadSource {
    data class Url(val url: String) : QQOfficialMediaUploadSource
    data class FileData(val fileData: String) : QQOfficialMediaUploadSource
}

/**
 * 简单分钟窗限频器，只在 QQ 官方 adapter 内部守护平台侧 qpm 约束。
 */
private class QQOfficialQpmLimiter(
    private val limitPerMinute: Int,
    private val currentTimeMillis: () -> Long,
) {
    private var windowStartMinute: Long = Long.MIN_VALUE
    private var usedInWindow: Int = 0

    /**
     * 只检查当前窗口额度，调用方可先同时检查多个维度再统一 acquire。
     */
    fun canAcquire(now: Long = currentTimeMillis()): Boolean {
        rollWindow(now)
        return limitPerMinute <= 0 || usedInWindow < limitPerMinute
    }

    /**
     * 消耗一个发送额度；limit <= 0 用于测试或特殊配置下关闭该维度限制。
     */
    fun acquire(now: Long = currentTimeMillis()) {
        rollWindow(now)
        if (limitPerMinute > 0) {
            usedInWindow++
        }
    }

    /**
     * 将时间切到新的分钟窗口时重置计数，避免长期运行累计旧窗口用量。
     */
    private fun rollWindow(now: Long) {
        val minute = now / 60_000L
        if (minute != windowStartMinute) {
            windowStartMinute = minute
            usedInWindow = 0
        }
    }
}

/**
 * 默认只解析公网 URL；本地/二进制图片由 adapter 内部 file_data 路径处理。
 */
private suspend fun defaultImageUrlResolver(source: ImageSource): String? {
    return when (source) {
        is ImageSource.RemoteUrl -> source.url
        is ImageSource.LocalFile -> null
        is ImageSource.Binary -> null
    }
}
