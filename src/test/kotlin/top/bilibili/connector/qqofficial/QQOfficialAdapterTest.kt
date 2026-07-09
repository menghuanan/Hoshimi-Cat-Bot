package top.bilibili.connector.qqofficial

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import top.bilibili.config.QQOfficialConfig
import top.bilibili.connector.CapabilityGuardResult
import top.bilibili.connector.CapabilityRequest
import top.bilibili.connector.ImageSource
import top.bilibili.connector.OutgoingPart
import top.bilibili.connector.PlatformCapability
import top.bilibili.connector.PlatformChatType
import top.bilibili.connector.PlatformContact
import top.bilibili.connector.PlatformObservabilitySnapshot
import top.bilibili.connector.PlatformType

class QQOfficialAdapterTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

    // 统一桥接适配器的 suspend 停机入口，避免每个 finally 重复包一层 runBlocking。
    private fun stopAdapter(adapter: QQOfficialAdapter) = runBlocking {
        adapter.stop()
    }

    @Test
    fun `missing credentials should keep adapter unavailable`() {
        val transport = FakeTransport()
        val adapter = QQOfficialAdapter(
            config = QQOfficialConfig(),
            transport = transport,
        )

        assertFailsWith<IllegalStateException> {
            adapter.start()
        }
        assertFalse(adapter.runtimeStatus().connected)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `token request body should use app id and client secret`() {
        val transport = FakeTransport()
        val adapter = createStartedAdapter(transport)

        try {
            val tokenRequest = transport.requests.first { it.url.endsWith("/app/getAppAccessToken") }
            // 心跳协程在 READY 后才启动，测试仍显式挑出 op=2 帧避免依赖发送顺序。
            val identifyPayload =
                transport.lastGatewaySession.sentTexts.first {
                    it.jsonObject["op"]?.jsonPrimitive?.content == "2"
                }.jsonObject

            assertEquals("POST", tokenRequest.method)
            assertEquals("demo-app", tokenRequest.body!!.jsonObject["appId"]!!.jsonPrimitive.content)
            assertEquals("demo-secret", tokenRequest.body!!.jsonObject["clientSecret"]!!.jsonPrimitive.content)
            assertEquals("GET", transport.requests.first { it.url.endsWith("/gateway/bot") }.method)
            assertEquals(2, identifyPayload["op"]!!.jsonPrimitive.content.toInt())
            assertEquals("QQBot access-token-demo", identifyPayload["d"]!!.jsonObject["token"]!!.jsonPrimitive.content)
            assertEquals((1 shl 25) or (1 shl 24), identifyPayload["d"]!!.jsonObject["intents"]!!.jsonPrimitive.content.toInt())
        } finally {
            stopAdapter(adapter)
        }
    }

    @Test
    fun `websocket dispatch payload should normalize group and c2c messages`() = runBlocking {
        val transport = FakeTransport()
        val adapter = createStartedAdapter(transport)

        try {
            val events = mutableListOf<top.bilibili.connector.PlatformInboundMessage>()
            val collectJob = launch {
                adapter.eventFlow.take(3).toList(events)
            }

            // 让 SharedFlow 收集协程先完成订阅，避免测试线程比收集线程更早发出事件。
            delay(10)
            transport.emitGatewayText(groupMessageFrame(eventType = "GROUP_MESSAGE_CREATE", content = "/login"))
            transport.emitGatewayText(
                groupMessageFrame(
                    eventType = "GROUP_AT_MESSAGE_CREATE",
                    seq = 4,
                    messageId = "msg-group-at",
                    content = "<@bot_openid_demo> /login",
                ),
            )
            transport.emitGatewayText(c2cMessageFrame())

            withTimeout(1_000) {
                collectJob.join()
            }

            val groupEvent = events[0]
            assertEquals(PlatformType.QQ_OFFICIAL, groupEvent.platform)
            assertEquals(PlatformChatType.GROUP, groupEvent.chatType)
            assertEquals("group_openid_demo", groupEvent.chatContact.id)
            assertEquals(PlatformChatType.PRIVATE, groupEvent.senderContact.type)
            assertEquals("member_openid_demo", groupEvent.senderContact.id)
            assertEquals("evt-group-1", groupEvent.eventId)
            assertEquals("msg-group-1", groupEvent.messageId)
            assertEquals("group_openid_demo", groupEvent.metadata["group_openid"])
            assertEquals("member_openid_demo", groupEvent.metadata["member_openid"])
            assertFalse(groupEvent.metadata.containsKey("user_openid"))
            assertEquals("/login", groupEvent.messageText)
            assertEquals(listOf("/login"), groupEvent.searchTexts)
            assertFalse(groupEvent.hasMention)

            val groupAtEvent = events[1]
            assertEquals(PlatformChatType.GROUP, groupAtEvent.chatType)
            assertEquals("msg-group-at", groupAtEvent.messageId)
            assertEquals("/login", groupAtEvent.messageText)
            assertEquals(listOf("/login", "<@bot_openid_demo> /login"), groupAtEvent.searchTexts)
            assertTrue(groupAtEvent.hasMention)

            val c2cEvent = events[2]
            assertEquals(PlatformChatType.PRIVATE, c2cEvent.chatType)
            assertEquals("user_openid_demo", c2cEvent.chatContact.id)
            assertEquals("user_openid_demo", c2cEvent.senderContact.id)
            assertEquals("evt-c2c-1", c2cEvent.eventId)
            assertEquals("msg-c2c-1", c2cEvent.messageId)
            assertEquals("user_openid_demo", c2cEvent.metadata["user_openid"])
            assertFalse(c2cEvent.hasMention)
        } finally {
            stopAdapter(adapter)
        }
    }

    @Test
    fun `media attachment urls should not be forwarded into searchable texts`() = runBlocking {
        val transport = FakeTransport()
        val adapter = createStartedAdapter(transport)

        try {
            val events = mutableListOf<top.bilibili.connector.PlatformInboundMessage>()
            val collectJob = launch {
                adapter.eventFlow.take(1).toList(events)
            }

            // 让事件收集先订阅，再投递带官方媒体附件的表情消息。
            delay(10)
            val attachmentUrl = "https://multimedia.nt.qq.com.cn/download?appid=1407&fileid=BVVL_L01ffaZ-demo&spec=0"
            transport.emitGatewayText(
                groupMessageFrame(
                    seq = 8,
                    eventId = "evt-group-media",
                    messageId = "msg-group-media",
                    content = "<faceType=66>",
                    attachmentUrl = attachmentUrl,
                ),
            )

            withTimeout(1_000) {
                collectJob.join()
            }

            val mediaEvent = events.single()
            assertTrue(mediaEvent.searchTexts.isEmpty(), "face-only media messages should not enter link resolution search texts")
            assertFalse(mediaEvent.searchTexts.any { it.contains("multimedia.nt.qq.com.cn") })
            assertFalse(mediaEvent.searchTexts.any { it.contains("BVVL_L01ffaZ") })
        } finally {
            stopAdapter(adapter)
        }
    }

    @Test
    fun `access token should refresh at official sixty second window`() = runBlocking {
        var now = 0L
        val transport = FakeTransport().apply {
            tokenExpiresInSeconds = "120"
        }
        val adapter = createStartedAdapter(transport, currentTimeMillis = { now })
        val groupContact = PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.GROUP, "group_openid_demo")

        try {
            transport.emitGatewayText(groupMessageFrame())
            waitForReachable(adapter, groupContact)
            val initialTokenRequests = transport.tokenRequestCount()

            now = 59_000L
            assertTrue(adapter.sendMessage(groupContact, listOf(OutgoingPart.text("未到刷新窗口"))))
            assertEquals(initialTokenRequests, transport.tokenRequestCount())

            now = 60_001L
            assertTrue(adapter.sendMessage(groupContact, listOf(OutgoingPart.text("进入刷新窗口"))))
            assertEquals(initialTokenRequests + 1, transport.tokenRequestCount())
        } finally {
            stopAdapter(adapter)
        }
    }

    @Test
    fun `openapi authorization failure should force refresh token and retry once`() = runBlocking {
        val transport = FakeTransport()
        val adapter = createStartedAdapter(transport)
        val groupContact = PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.GROUP, "group_openid_demo")

        try {
            transport.emitGatewayText(groupMessageFrame())
            waitForReachable(adapter, groupContact)
            transport.failNextMessageWith401 = true

            assertTrue(adapter.sendMessage(groupContact, listOf(OutgoingPart.text("刷新后重试"))))

            val messageRequests = transport.requests.filter { it.url.endsWith("/v2/groups/group_openid_demo/messages") }
            assertEquals(2, transport.tokenRequestCount())
            assertEquals(2, messageRequests.size)
            assertEquals("QQBot access-token-demo", messageRequests[0].headers["Authorization"])
            assertEquals("QQBot access-token-demo-2", messageRequests[1].headers["Authorization"])
        } finally {
            stopAdapter(adapter)
        }
    }

    @Test
    fun `text send should build group and c2c message requests`() = runBlocking {
        val transport = FakeTransport()
        val adapter = createStartedAdapter(transport)
        val groupContact = PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.GROUP, "group_openid_demo")
        val privateContact = PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.PRIVATE, "user_openid_demo")

        try {
            transport.emitGatewayText(groupMessageFrame())
            transport.emitGatewayText(c2cMessageFrame())
            waitForReachable(adapter, groupContact)
            waitForReachable(adapter, privateContact)

            assertTrue(adapter.sendMessage(groupContact, listOf(OutgoingPart.text("群消息"))))
            assertTrue(adapter.sendMessage(privateContact, listOf(OutgoingPart.text("私聊消息"))))

            val groupRequest = transport.requests.first { it.url.endsWith("/v2/groups/group_openid_demo/messages") }
            val privateRequest = transport.requests.first { it.url.endsWith("/v2/users/user_openid_demo/messages") }

            assertEquals(0, groupRequest.body!!.jsonObject["msg_type"]!!.jsonPrimitive.content.toInt())
            assertEquals("群消息", groupRequest.body!!.jsonObject["content"]!!.jsonPrimitive.content)
            assertEquals(0, privateRequest.body!!.jsonObject["msg_type"]!!.jsonPrimitive.content.toInt())
            assertEquals("私聊消息", privateRequest.body!!.jsonObject["content"]!!.jsonPrimitive.content)
        } finally {
            stopAdapter(adapter)
        }
    }

    @Test
    fun `reply send should keep string msg id and increment msg seq per message id`() = runBlocking {
        val transport = FakeTransport()
        val adapter = createStartedAdapter(transport)
        val groupContact = PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.GROUP, "group_openid_demo")

        try {
            transport.emitGatewayText(groupMessageFrame())
            waitForReachable(adapter, groupContact)

            assertTrue(adapter.sendMessage(groupContact, listOf(OutgoingPart.reply("msg-group-1"), OutgoingPart.text("第一次"))))
            assertTrue(adapter.sendMessage(groupContact, listOf(OutgoingPart.reply("msg-group-1"), OutgoingPart.text("第二次"))))

            val sendRequests = transport.requests.filter { it.url.endsWith("/v2/groups/group_openid_demo/messages") }
            assertEquals("msg-group-1", sendRequests[0].body!!.jsonObject["msg_id"]!!.jsonPrimitive.content)
            assertEquals(1, sendRequests[0].body!!.jsonObject["msg_seq"]!!.jsonPrimitive.content.toInt())
            assertEquals("msg-group-1", sendRequests[1].body!!.jsonObject["msg_id"]!!.jsonPrimitive.content)
            assertEquals(2, sendRequests[1].body!!.jsonObject["msg_seq"]!!.jsonPrimitive.content.toInt())
        } finally {
            stopAdapter(adapter)
        }
    }

    @Test
    fun `rate limiter should guard bot and group qpm before openapi send`() = runBlocking {
        val transport = FakeTransport()
        val adapter = createStartedAdapter(
            transport = transport,
            botQpmLimit = 1,
            groupQpmLimit = 1,
        )
        val groupContact = PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.GROUP, "group_openid_demo")

        try {
            transport.emitGatewayText(groupMessageFrame())
            waitForReachable(adapter, groupContact)

            assertTrue(adapter.sendMessage(groupContact, listOf(OutgoingPart.text("第一条"))))
            assertFalse(adapter.sendMessage(groupContact, listOf(OutgoingPart.text("第二条"))))

            val sendRequests = transport.requests.filter { it.url.endsWith("/v2/groups/group_openid_demo/messages") }
            assertEquals(1, sendRequests.size)
        } finally {
            stopAdapter(adapter)
        }
    }

    @Test
    fun `image send should upload media before sending rich media message`() = runBlocking {
        val transport = FakeTransport()
        val adapter = createStartedAdapter(transport)
        val groupContact = PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.GROUP, "group_openid_demo")

        try {
            transport.emitGatewayText(groupMessageFrame())
            waitForReachable(adapter, groupContact)

            assertTrue(
                adapter.sendMessage(
                    groupContact,
                    listOf(
                        OutgoingPart.text("带图消息"),
                        OutgoingPart.image("https://example.com/demo.png"),
                    ),
                ),
            )

            val uploadRequest = transport.requests.first { it.url.endsWith("/v2/groups/group_openid_demo/files") }
            val sendRequest = transport.requests.last { it.url.endsWith("/v2/groups/group_openid_demo/messages") }

            assertEquals(1, uploadRequest.body!!.jsonObject["file_type"]!!.jsonPrimitive.content.toInt())
            assertEquals("https://example.com/demo.png", uploadRequest.body!!.jsonObject["url"]!!.jsonPrimitive.content)
            assertEquals(7, sendRequest.body!!.jsonObject["msg_type"]!!.jsonPrimitive.content.toInt())
            assertEquals("带图消息", sendRequest.body!!.jsonObject["content"]!!.jsonPrimitive.content)
            assertEquals("file-info-demo", sendRequest.body!!.jsonObject["media"]!!.jsonObject["file_info"]!!.jsonPrimitive.content)
        } finally {
            stopAdapter(adapter)
        }
    }

    // 验证群聊图片-only 富媒体会补齐官方必需的 content 字段。
    @Test
    fun `group rich media without text should include official content placeholder`() = runBlocking {
        val transport = FakeTransport()
        val adapter = createStartedAdapter(transport)
        val groupContact = PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.GROUP, "group_openid_demo")

        try {
            transport.emitGatewayText(groupMessageFrame())
            waitForReachable(adapter, groupContact)

            assertTrue(
                adapter.sendMessage(
                    groupContact,
                    listOf(OutgoingPart.image("https://example.com/only-image.png")),
                ),
            )

            val sendRequest = transport.requests.last { it.url.endsWith("/v2/groups/group_openid_demo/messages") }

            assertEquals(7, sendRequest.body!!.jsonObject["msg_type"]!!.jsonPrimitive.content.toInt())
            assertEquals(" ", sendRequest.body!!.jsonObject["content"]!!.jsonPrimitive.content)
            assertEquals("file-info-demo", sendRequest.body!!.jsonObject["media"]!!.jsonObject["file_info"]!!.jsonPrimitive.content)
        } finally {
            stopAdapter(adapter)
        }
    }

    // 验证 OneBot 风格图片来源在 QQ 官方 adapter 内转换为 file_data 上传。
    @Test
    fun `onebot style file and base64 image sources should upload as file data`() = runBlocking {
        val transport = FakeTransport()
        val adapter = createStartedAdapter(transport)
        val groupContact = PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.GROUP, "group_openid_demo")
        val tempImage = Files.createTempFile("qq-official-onebot-style", ".png")
        val localBytes = byteArrayOf(9, 8, 7, 6)
        val base64Payload = Base64.getEncoder().encodeToString(byteArrayOf(5, 4, 3, 2))
        Files.write(tempImage, localBytes)

        try {
            transport.emitGatewayText(groupMessageFrame())
            waitForReachable(adapter, groupContact)

            assertTrue(
                adapter.sendMessage(
                    groupContact,
                    listOf(
                        OutgoingPart.image(tempImage.toUri().toString()),
                        OutgoingPart.image("base64://$base64Payload"),
                    ),
                ),
            )

            val uploadRequests = transport.requests.filter { it.url.endsWith("/v2/groups/group_openid_demo/files") }

            assertEquals(2, uploadRequests.size)
            assertEquals(Base64.getEncoder().encodeToString(localBytes), uploadRequests[0].body!!.jsonObject["file_data"]!!.jsonPrimitive.content)
            assertEquals(base64Payload, uploadRequests[1].body!!.jsonObject["file_data"]!!.jsonPrimitive.content)
        } finally {
            Files.deleteIfExists(tempImage)
            stopAdapter(adapter)
        }
    }

    @Test
    fun `capability query should expose direct send image reply and atall support`() = runBlocking {
        val transport = FakeTransport()
        val adapter = createStartedAdapter(transport)
        val groupContact = PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.GROUP, "group_openid_demo")
        val tempImage = Files.createTempFile("qq-official-capability", ".png")
        Files.write(tempImage, byteArrayOf(1, 2, 3))

        try {
            transport.emitGatewayText(groupMessageFrame())
            waitForReachable(adapter, groupContact)

            assertTrue(adapter.canSendMessage(groupContact))
            assertTrue(adapter.canSendImages(groupContact, listOf(ImageSource.RemoteUrl("https://example.com/demo.png"))))
            assertTrue(adapter.canSendImages(groupContact, listOf(ImageSource.LocalFile(tempImage.toString()))))
            assertTrue(adapter.canSendImages(groupContact, listOf(ImageSource.Binary(byteArrayOf(4, 5, 6), "demo.png"))))
            assertTrue(adapter.canReply(groupContact))
            assertFalse(adapter.canAtAll(groupContact))
        } finally {
            Files.deleteIfExists(tempImage)
            stopAdapter(adapter)
        }
    }

    @Test
    fun `unsupported mention all should fail explicitly without silent fallback`() = runBlocking {
        val transport = FakeTransport()
        val adapter = createStartedAdapter(transport)
        val groupContact = PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.GROUP, "group_openid_demo")

        try {
            transport.emitGatewayText(groupMessageFrame())
            waitForReachable(adapter, groupContact)

            assertFalse(adapter.sendMessage(groupContact, listOf(OutgoingPart.atAll(), OutgoingPart.text("公告"))))
            assertFalse(transport.requests.any { it.url.endsWith("/v2/groups/group_openid_demo/messages") })
        } finally {
            stopAdapter(adapter)
        }
    }

    @Test
    fun `local and binary images should upload file data while reply keeps string msg id`() = runBlocking {
        val transport = FakeTransport()
        val adapter = createStartedAdapter(transport)
        val groupContact = PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.GROUP, "group_openid_demo")
        val tempImage = Files.createTempFile("qq-official-local", ".png")
        val localBytes = byteArrayOf(1, 2, 3, 4)
        val binaryBytes = byteArrayOf(5, 6, 7, 8)
        Files.write(tempImage, localBytes)

        try {
            transport.emitGatewayText(groupMessageFrame())
            waitForReachable(adapter, groupContact)

            assertTrue(
                adapter.sendMessage(
                    groupContact,
                    listOf(
                        OutgoingPart.reply("msg-group-1"),
                        OutgoingPart.text("带本地图"),
                        OutgoingPart.Image(ImageSource.LocalFile(tempImage.toString())),
                        OutgoingPart.Image(ImageSource.Binary(binaryBytes, "binary.png")),
                    ),
                ),
            )

            val uploadRequests = transport.requests.filter { it.url.endsWith("/v2/groups/group_openid_demo/files") }
            val sendRequests = transport.requests.filter { it.url.endsWith("/v2/groups/group_openid_demo/messages") }

            assertEquals(2, uploadRequests.size)
            assertEquals(Base64.getEncoder().encodeToString(localBytes), uploadRequests[0].body!!.jsonObject["file_data"]!!.jsonPrimitive.content)
            assertEquals(Base64.getEncoder().encodeToString(binaryBytes), uploadRequests[1].body!!.jsonObject["file_data"]!!.jsonPrimitive.content)
            assertEquals(7, sendRequests[0].body!!.jsonObject["msg_type"]!!.jsonPrimitive.content.toInt())
            assertEquals("带本地图", sendRequests[0].body!!.jsonObject["content"]!!.jsonPrimitive.content)
            assertEquals("msg-group-1", sendRequests[0].body!!.jsonObject["msg_id"]!!.jsonPrimitive.content)
            assertEquals(1, sendRequests[0].body!!.jsonObject["msg_seq"]!!.jsonPrimitive.content.toInt())
            assertEquals(7, sendRequests[1].body!!.jsonObject["msg_type"]!!.jsonPrimitive.content.toInt())
        } finally {
            Files.deleteIfExists(tempImage)
            stopAdapter(adapter)
        }
    }

    @Test
    fun `missing local image should fail explicitly when no text fallback exists`() = runBlocking {
        val transport = FakeTransport()
        val adapter = createStartedAdapter(transport)
        val groupContact = PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.GROUP, "group_openid_demo")

        try {
            transport.emitGatewayText(groupMessageFrame())
            waitForReachable(adapter, groupContact)

            assertFalse(
                adapter.sendMessage(
                    groupContact,
                    listOf(OutgoingPart.Image(ImageSource.LocalFile("temp/demo.png"))),
                ),
            )
            assertFalse(transport.requests.any { it.url.endsWith("/v2/groups/group_openid_demo/messages") })
        } finally {
            stopAdapter(adapter)
        }
    }

    @Test
    fun `guard capability should support readable local image and reject atall explicitly`() = runBlocking {
        val transport = FakeTransport()
        val adapter = createStartedAdapter(transport)
        val groupContact = PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.GROUP, "group_openid_demo")
        val tempImage = Files.createTempFile("qq-official-guard", ".png")
        Files.write(tempImage, byteArrayOf(1, 2, 3))

        try {
            transport.emitGatewayText(groupMessageFrame())
            waitForReachable(adapter, groupContact)

            val imageGuard = adapter.guardCapability(
                CapabilityRequest(
                    capability = PlatformCapability.SEND_IMAGES,
                    contact = groupContact,
                    images = listOf(ImageSource.LocalFile(tempImage.toString())),
                ),
            )
            val atAllGuard = adapter.guardCapability(
                CapabilityRequest(
                    capability = PlatformCapability.AT_ALL,
                    contact = groupContact,
                ),
            )

            assertIs<CapabilityGuardResult.Supported>(imageGuard)
            val unsupportedAtAll = assertIs<CapabilityGuardResult.Unsupported>(atAllGuard)
            assertTrue(unsupportedAtAll.reason.contains("@全体"), "qq official at-all reason should be explicit")
        } finally {
            Files.deleteIfExists(tempImage)
            stopAdapter(adapter)
        }
    }

    @Test
    fun `qq official image upload should keep local and binary images on file data path`() {
        val source = read("src/main/kotlin/top/bilibili/connector/qqofficial/QQOfficialAdapter.kt")

        // QQ Official 本地图和二进制图必须走 file_data 上传，而不是退回纯文本。
        assertTrue(source.contains("encodeLocalFileAsBase64"))
        assertTrue(source.contains("is ImageSource.Binary -> QQOfficialMediaUploadSource.FileData"))
        assertTrue(source.contains("put(\"file_data\", source.fileData)"))
    }

    @Test
    fun `active message receive and reject events should toggle send capability`() = runBlocking {
        val transport = FakeTransport()
        val adapter = createStartedAdapter(transport)
        val groupContact = PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.GROUP, "group_openid_demo")
        val privateContact = PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.PRIVATE, "user_openid_demo")

        try {
            assertFalse(adapter.canSendMessage(groupContact))
            assertFalse(adapter.canSendMessage(privateContact))

            transport.emitGatewayText(
                manageGroupFrame(
                    eventType = "GROUP_MSG_RECEIVE",
                    groupOpenId = "group_openid_demo",
                ),
            )
            waitForReachable(adapter, groupContact)
            assertTrue(adapter.canSendMessage(groupContact))

            transport.emitGatewayText(
                manageGroupFrame(
                    eventType = "GROUP_MSG_REJECT",
                    groupOpenId = "group_openid_demo",
                ),
            )

            withTimeout(1_000) {
                while (adapter.canSendMessage(groupContact)) {
                    delay(10)
                }
            }
            assertFalse(adapter.canSendMessage(groupContact))

            transport.emitGatewayText(manageC2CFrame(eventType = "C2C_MSG_RECEIVE", openId = "user_openid_demo"))
            waitForReachable(adapter, privateContact)
            assertTrue(adapter.canSendMessage(privateContact))

            transport.emitGatewayText(manageC2CFrame(eventType = "C2C_MSG_REJECT", openId = "user_openid_demo"))
            withTimeout(1_000) {
                while (adapter.canSendMessage(privateContact)) {
                    delay(10)
                }
            }
            assertFalse(adapter.canSendMessage(privateContact))
        } finally {
            stopAdapter(adapter)
        }
    }

    @Test
    fun `group member and subscribe status events should commit gateway sequence`() = runBlocking {
        val transport = FakeTransport().apply {
            heartbeatIntervalMillis = 20
            ackHeartbeatDuringSend = true
        }
        val adapter = createStartedAdapter(transport)

        try {
            transport.emitGatewayText(groupMemberFrame(eventType = "GROUP_MEMBER_ADD", seq = 24))
            transport.emitGatewayText(groupMemberFrame(eventType = "GROUP_MEMBER_REMOVE", seq = 25))
            transport.emitGatewayText(subscribeStatusFrame(seq = 26))
            waitForHeartbeatSeq(transport.lastGatewaySession, 26)
            transport.lastGatewaySession.closeWithCode(4009, "resume after managed events")
            waitForGatewayOpenCount(transport, 2)
            val resumePayload = waitForSentOp(transport.lastGatewaySession, 6).jsonObject["d"]!!.jsonObject

            assertEquals(26, resumePayload["seq"]!!.jsonPrimitive.content.toInt())
        } finally {
            stopAdapter(adapter)
        }
    }

    @Test
    fun `reachable contacts should expire after configured ttl`() = runBlocking {
        var now = 1_000L
        val transport = FakeTransport()
        val adapter = createStartedAdapter(
            transport = transport,
            currentTimeMillis = { now },
            reachableContactTtlMillis = 1_000L,
        )
        val groupContact = PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.GROUP, "group_openid_demo")

        try {
            transport.emitGatewayText(manageGroupFrame(eventType = "GROUP_MSG_RECEIVE", groupOpenId = "group_openid_demo"))
            waitForReachable(adapter, groupContact)

            now += 1_001L

            assertFalse(adapter.canSendMessage(groupContact))
        } finally {
            stopAdapter(adapter)
        }
    }

    @Test
    fun `reachable contacts should evict oldest entries when capacity is exceeded`() = runBlocking {
        val transport = FakeTransport()
        val adapter = createStartedAdapter(
            transport = transport,
            reachableContactsMaxSize = 2,
        )
        val first = PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.GROUP, "group_openid_1")
        val second = PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.GROUP, "group_openid_2")
        val third = PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.GROUP, "group_openid_3")

        try {
            transport.emitGatewayText(manageGroupFrame(eventType = "GROUP_ADD_ROBOT", groupOpenId = first.id))
            waitForReachable(adapter, first)
            transport.emitGatewayText(manageGroupFrame(eventType = "GROUP_ADD_ROBOT", groupOpenId = second.id))
            waitForReachable(adapter, second)
            transport.emitGatewayText(manageGroupFrame(eventType = "GROUP_ADD_ROBOT", groupOpenId = third.id))
            waitForReachable(adapter, third)

            assertFalse(adapter.canSendMessage(first))
            assertTrue(adapter.canSendMessage(second))
            assertTrue(adapter.canSendMessage(third))
        } finally {
            stopAdapter(adapter)
        }
    }

    @Test
    fun `qq official adapter should expose inbound overflow through neutral runtime status`() {
        val source = read("src/main/kotlin/top/bilibili/connector/qqofficial/QQOfficialAdapter.kt")

        assertTrue(source.contains("inboundPressureActive"))
        assertTrue(source.contains("inboundDroppedEvents"))
        assertTrue(source.contains("recordInboundEvent"))
        assertTrue(source.contains("inboundDroppedEvents.incrementAndGet()"))
    }

    // 回归保护 QQ 官方关键日志覆盖点，避免后续改动只保留故障日志而丢失任务状态。
    @Test
    fun `qq official adapter should keep Chinese logs for platform and task states`() {
        val source = read("src/main/kotlin/top/bilibili/connector/qqofficial/QQOfficialAdapter.kt")

        listOf(
            "QQ 官方适配器启动参数",
            "QQ 官方任务状态",
            "QQ 官方网关收帧任务已启动",
            "QQ 官方网关关闭监听任务已启动",
            "QQ 官方心跳任务已启动",
            "QQ 官方网关重连任务已启动",
            "QQ 官方准备发送消息",
            "QQ 官方访问令牌已刷新",
            "QQ 官方开放接口鉴权失败，已刷新访问令牌后重试",
            "QQ 官方联系人已标记为可达",
            "QQ 官方发送限额不足",
            "QQ 官方入站事件已投递",
            "QQ 官方群成员事件已处理",
            "QQ 官方订阅消息授权状态事件已记录",
        ).forEach { expectedLog ->
            assertTrue(source.contains(expectedLog), "missing QQ Official Chinese log: $expectedLog")
        }
        listOf(
            "QQ Official does not support @全体",
            "qpm guard",
            "READY: self",
            "Close: code",
            "path={}, reason={}",
            "app_id 或 app_secret",
        ).forEach { legacyLog ->
            assertFalse(source.contains(legacyLog), "legacy QQ Official log should stay replaced: $legacyLog")
        }
    }

    @Test
    fun `close code 4009 should reconnect with resume payload`() = runBlocking {
        val transport = FakeTransport()
        val adapter = createStartedAdapter(transport)
        val groupContact = PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.GROUP, "group_openid_demo")

        try {
            transport.emitGatewayText(groupMessageFrame())
            waitForReachable(adapter, groupContact)
            transport.lastGatewaySession.closeWithCode(4009, "session timeout")
            waitForGatewayOpenCount(transport, 2)
            val secondSession = transport.lastGatewaySession
            val resumePayload = waitForSentOp(secondSession, 6).jsonObject["d"]!!.jsonObject

            assertEquals("session-demo", resumePayload["session_id"]!!.jsonPrimitive.content)
            assertEquals(2, resumePayload["seq"]!!.jsonPrimitive.content.toInt())
        } finally {
            stopAdapter(adapter)
        }
    }

    @Test
    fun `close code 4006 should clear resume state and identify again`() = runBlocking {
        val transport = FakeTransport()
        val adapter = createStartedAdapter(transport)

        try {
            transport.enqueueGatewayBootstrap(listOf(helloFrame(), readyFrame(sessionId = "session-after-identify")))
            transport.lastGatewaySession.closeWithCode(4006, "invalid session")
            waitForGatewayOpenCount(transport, 2)
            val secondSession = transport.lastGatewaySession

            assertTrue(secondSession.sentTexts.any { it.jsonObject["op"]?.jsonPrimitive?.content == "2" })
            assertFalse(secondSession.sentTexts.any { it.jsonObject["op"]?.jsonPrimitive?.content == "6" })
        } finally {
            stopAdapter(adapter)
        }
    }

    @Test
    fun `fatal close code should stop reconnect and report unavailable`() = runBlocking {
        val transport = FakeTransport()
        val adapter = createStartedAdapter(transport)

        try {
            transport.lastGatewaySession.closeWithCode(4013, "invalid intents")
            withTimeout(1_000) {
                while (adapter.runtimeStatus().connected) {
                    delay(10)
                }
            }
            delay(100)

            assertFalse(adapter.runtimeStatus().connected)
            assertEquals(1, transport.gatewaySessions.size)
        } finally {
            stopAdapter(adapter)
        }
    }

    @Test
    fun `heartbeat ack timeout should close session and reconnect`() = runBlocking {
        var now = 0L
        val transport = FakeTransport().apply {
            heartbeatIntervalMillis = 20
        }
        val adapter = createStartedAdapter(transport, currentTimeMillis = { now })

        try {
            val firstSession = transport.lastGatewaySession
            waitForSentOp(firstSession, 1)

            now = 100L
            waitForGatewayOpenCount(transport, 2)

            assertFalse(firstSession.closeSignal.isActive)
        } finally {
            stopAdapter(adapter)
        }
    }

    // READY 前不应发送心跳，官方要求鉴权成功后才开始周期心跳。
    @Test
    fun `heartbeat should wait until gateway ready before first send`() = runBlocking {
        val now = 0L
        val transport = FakeTransport().apply {
            heartbeatIntervalMillis = 20
            enqueueGatewayBootstrap(listOf(helloFrame(heartbeatIntervalMillis)))
        }
        val adapter = QQOfficialAdapter(
            config = QQOfficialConfig(
                appId = "demo-app",
                appSecret = "demo-secret",
            ),
            transport = transport,
            currentTimeMillis = { now },
        )
        var firstSession: FakeGatewaySession? = null
        val startJob = launch(Dispatchers.IO) {
            adapter.start()
        }

        try {
            waitForGatewayOpenCount(transport, 1)
            val session = transport.lastGatewaySession
            firstSession = session
            waitForSentOp(session, 2)

            // 只收到 Hello 时仍处于待鉴权状态，此时不能提前发送 op=1。
            delay(80)
            assertFalse(session.sentTexts.any { it.jsonObject["op"]?.jsonPrimitive?.content == "1" })

            session.emit(readyFrame())
            withTimeout(1_000) {
                startJob.join()
            }
            waitForSentOp(session, 1)
        } finally {
            if (startJob.isActive) {
                firstSession?.emit(readyFrame())
                withTimeout(1_000) {
                    startJob.join()
                }
            }
            stopAdapter(adapter)
        }
    }

    @Test
    fun `heartbeat ack during send should not trigger timeout reconnect`() = runBlocking {
        var now = 0L
        val transport = FakeTransport().apply {
            heartbeatIntervalMillis = 20
            ackHeartbeatDuringSend = true
        }
        val adapter = createStartedAdapter(transport, currentTimeMillis = { now })

        try {
            val firstSession = transport.lastGatewaySession
            waitForSentOp(firstSession, 1)

            // 将业务时钟推进到超时窗口之后，验证快速 ACK 已经清掉 in-flight 状态。
            now = 100L
            delay(80)

            assertTrue(firstSession.closeSignal.isActive)
            assertEquals(1, transport.gatewaySessions.size)
        } finally {
            stopAdapter(adapter)
        }
    }

    @Test
    fun `dispatch seq should commit only after normalized inbound delivery`() = runBlocking {
        val transport = FakeTransport()
        val adapter = createStartedAdapter(transport)
        val groupContact = PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.GROUP, "group_openid_demo")

        try {
            transport.emitGatewayText(malformedGroupMessageFrame(seq = 77))
            transport.lastGatewaySession.closeWithCode(4009, "resume after malformed")
            waitForGatewayOpenCount(transport, 2)
            val resumeAfterMalformed = waitForSentOp(transport.lastGatewaySession, 6).jsonObject["d"]!!.jsonObject
            assertEquals(1, resumeAfterMalformed["seq"]!!.jsonPrimitive.content.toInt())

            transport.emitGatewayText(groupMessageFrame(seq = 8))
            waitForReachable(adapter, groupContact)
            transport.lastGatewaySession.closeWithCode(4009, "resume after delivered")
            waitForGatewayOpenCount(transport, 3)
            val resumeAfterDelivered = waitForSentOp(transport.lastGatewaySession, 6).jsonObject["d"]!!.jsonObject
            assertEquals(8, resumeAfterDelivered["seq"]!!.jsonPrimitive.content.toInt())
        } finally {
            stopAdapter(adapter)
        }
    }

    @Test
    fun `qq official reconnect should use shared bounded backoff without recursive retry scheduling`() {
        val source = read("src/main/kotlin/top/bilibili/connector/qqofficial/QQOfficialAdapter.kt")
        val policySource = read("src/main/kotlin/top/bilibili/connector/ConnectionBackoffPolicy.kt")

        assertTrue(policySource.contains("class ConnectionBackoffPolicy"))
        assertTrue(source.contains("ConnectionBackoffPolicy"))
        assertFalse(source.contains("delay(3_000)"))
        assertFalse(source.contains("scheduleReconnect()"))
        assertTrue(source.contains("runReconnectLoop"))
    }

    @Test
    fun `qq official gateway sessions should bind to transport scope instead of creating new root scopes`() {
        val source = read("src/main/kotlin/top/bilibili/connector/qqofficial/QQOfficialTransport.kt")

        assertTrue(
            source.contains("private val transportScope = CoroutineScope(Dispatchers.IO + SupervisorJob())"),
            "QQ Official transport should hold a single transport scope",
        )
        assertTrue(
            source.contains("SupervisorJob(transportScope.coroutineContext[Job])"),
            "QQ Official transport should create per-session jobs under the transport scope",
        )
        assertFalse(
            source.contains("val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())"),
            "QQ Official openGateway should no longer create a fresh root CoroutineScope per session",
        )
    }

    // 启动测试适配器时，预置 Hello/Ready 帧，确保启动路径能完成首轮网关握手。
    private fun createStartedAdapter(
        transport: FakeTransport,
        currentTimeMillis: () -> Long = { System.currentTimeMillis() },
        reachableContactTtlMillis: Long = QQOfficialAdapter.DEFAULT_REACHABLE_CONTACT_TTL_MILLIS,
        reachableContactsMaxSize: Int = QQOfficialAdapter.DEFAULT_REACHABLE_CONTACTS_MAX_SIZE,
        botQpmLimit: Int = QQOfficialAdapter.DEFAULT_BOT_QPM_LIMIT,
        groupQpmLimit: Int = QQOfficialAdapter.DEFAULT_GROUP_QPM_LIMIT,
        groupPassiveReplyWindowMillis: Long = QQOfficialAdapter.DEFAULT_GROUP_PASSIVE_REPLY_WINDOW_MILLIS,
        privatePassiveReplyWindowMillis: Long = QQOfficialAdapter.DEFAULT_PRIVATE_PASSIVE_REPLY_WINDOW_MILLIS,
    ): QQOfficialAdapter {
        val adapter = QQOfficialAdapter(
            config = QQOfficialConfig(
                appId = "demo-app",
                appSecret = "demo-secret",
            ),
            transport = transport,
            currentTimeMillis = currentTimeMillis,
            reachableContactTtlMillis = reachableContactTtlMillis,
            reachableContactsMaxSize = reachableContactsMaxSize,
            botQpmLimit = botQpmLimit,
            groupQpmLimit = groupQpmLimit,
            groupPassiveReplyWindowMillis = groupPassiveReplyWindowMillis,
            privatePassiveReplyWindowMillis = privatePassiveReplyWindowMillis,
        )
        adapter.start()
        return adapter
    }

    // 等待联系人被适配器标记为当前运行时可达，避免测试与异步事件处理竞争。
    private suspend fun waitForReachable(adapter: QQOfficialAdapter, contact: PlatformContact) {
        withTimeout(1_000) {
            while (!adapter.isContactReachable(contact)) {
                delay(10)
            }
        }
    }

    // 等待 fake transport 建立到指定代际，避免 close/reconnect 测试与后台协程竞争。
    private suspend fun waitForGatewayOpenCount(transport: FakeTransport, expectedCount: Int) {
        withTimeout(5_000) {
            while (transport.gatewaySessions.size < expectedCount) {
                delay(10)
            }
        }
    }

    // 等待指定 op 的网关出站帧，避免心跳与 identify/resume 的发送顺序影响断言。
    private suspend fun waitForSentOp(session: FakeGatewaySession, op: Int): JsonElement {
        return withTimeout(2_000) {
            while (true) {
                session.sentTexts.firstOrNull { it.jsonObject["op"]?.jsonPrimitive?.content == op.toString() }?.let {
                    return@withTimeout it
                }
                delay(10)
            }
            error("unreachable")
        }
    }

    // 等待心跳携带指定 seq，证明异步收帧协程已经提交到该网关序号。
    private suspend fun waitForHeartbeatSeq(session: FakeGatewaySession, seq: Int) {
        withTimeout(2_000) {
            while (
                session.sentTexts.none {
                    it.jsonObject["op"]?.jsonPrimitive?.content == "1" &&
                        it.jsonObject["d"]?.toString() == seq.toString()
                }
            ) {
                delay(10)
            }
        }
    }

    // 构造群聊消息事件，覆盖 group_openid/member_openid 与普通/AT 事件的归一化路径。
    private fun groupMessageFrame(
        seq: Int = 2,
        eventType: String = "GROUP_MESSAGE_CREATE",
        eventId: String = "evt-group-1",
        messageId: String = "msg-group-1",
        content: String = "/bili list",
        attachmentUrl: String? = null,
    ): String {
        return buildJsonObject {
            put("op", 0)
            put("s", seq)
            put("t", eventType)
            put("id", eventId)
            put("d", buildJsonObject {
                put("id", messageId)
                put("content", content)
                put("group_openid", "group_openid_demo")
                put("author", buildJsonObject {
                    put("member_openid", "member_openid_demo")
                })
                attachmentUrl?.let { mediaUrl ->
                    // QQ 官方附件 URL 模拟平台媒体资源地址，不应被适配层当作用户正文。
                    put("attachments", buildJsonArray {
                        add(buildJsonObject {
                            put("url", mediaUrl)
                        })
                    })
                }
            })
        }.toString()
    }

    // 构造缺少 group_openid 的群消息，覆盖归一化失败时不得提交 seq 的路径。
    private fun malformedGroupMessageFrame(seq: Int): String {
        return buildJsonObject {
            put("op", 0)
            put("s", seq)
            put("t", "GROUP_MESSAGE_CREATE")
            put("id", "evt-group-malformed")
            put("d", buildJsonObject {
                put("id", "msg-group-malformed")
                put("content", "/bili malformed")
                put("author", buildJsonObject {
                    put("member_openid", "member_openid_demo")
                })
            })
        }.toString()
    }

    // 构造 C2C 消息事件，覆盖 user_openid 的运行时归一化路径。
    private fun c2cMessageFrame(): String {
        return buildJsonObject {
            put("op", 0)
            put("s", 3)
            put("t", "C2C_MESSAGE_CREATE")
            put("id", "evt-c2c-1")
            put("d", buildJsonObject {
                put("id", "msg-c2c-1")
                put("content", "/login")
                put("author", buildJsonObject {
                    put("user_openid", "user_openid_demo")
                })
            })
        }.toString()
    }

    // 构造群管理事件，覆盖机器人被加入/移出群时的可达性切换路径。
    private fun manageGroupFrame(eventType: String, groupOpenId: String): String {
        return buildJsonObject {
            put("op", 0)
            put("s", 4)
            put("t", eventType)
            put("id", "evt-manage-$eventType")
            put("d", buildJsonObject {
                put("group_openid", groupOpenId)
                put("op_member_openid", "member_openid_demo")
                put("timestamp", 1_700_000_000)
            })
        }.toString()
    }

    // 构造群成员事件，覆盖 GROUP_MEMBER_ADD / GROUP_MEMBER_REMOVE 的 seq 提交流程。
    private fun groupMemberFrame(eventType: String, seq: Int): String {
        return buildJsonObject {
            put("op", 0)
            put("s", seq)
            put("t", eventType)
            put("id", "evt-member-$eventType")
            put("d", buildJsonObject {
                put("group_openid", "group_openid_demo")
                put("member_openid", "member_openid_demo")
                put("timestamp", 1_700_000_001)
            })
        }.toString()
    }

    // 构造订阅消息授权状态事件，覆盖非消息事件不得进入业务 eventFlow 的路径。
    private fun subscribeStatusFrame(seq: Int): String {
        return buildJsonObject {
            put("op", 0)
            put("s", seq)
            put("t", "SUBSCRIBE_MESSAGE_STATUS")
            put("id", "evt-subscribe-status")
            put("d", buildJsonObject {
                put("openid", "user_openid_demo")
                put("group_openid", "group_openid_demo")
                put("subscribe_id", "subscribe-demo")
                put("status", "accept")
                put("timestamp", 1_700_000_002)
            })
        }.toString()
    }

    // 构造 C2C 管理事件，覆盖主动私聊开关的 receive/reject 路径。
    private fun manageC2CFrame(eventType: String, openId: String): String {
        return buildJsonObject {
            put("op", 0)
            put("s", 5)
            put("t", eventType)
            put("id", "evt-manage-$eventType")
            put("d", buildJsonObject {
                put("openid", openId)
            })
        }.toString()
    }

    // 构造 Hello 帧，允许心跳 ACK 测试压缩 interval。
    private fun helloFrame(heartbeatIntervalMillis: Int = 30_000): String {
        return buildJsonObject {
            put("op", 10)
            put("d", buildJsonObject {
                put("heartbeat_interval", heartbeatIntervalMillis)
            })
        }.toString()
    }

    // 构造心跳 ACK 帧，覆盖 ACK 与 sendText 返回顺序不同步的竞态。
    private fun heartbeatAckFrame(): String {
        return buildJsonObject {
            put("op", 11)
        }.toString()
    }

    // 构造 READY 帧，覆盖 identify 后的首轮会话建立。
    private fun readyFrame(sessionId: String = "session-demo", seq: Int = 1): String {
        return buildJsonObject {
            put("op", 0)
            put("s", seq)
            put("t", "READY")
            put("id", "evt-ready-1")
            put("d", buildJsonObject {
                put("version", 1)
                put("session_id", sessionId)
                put("user", buildJsonObject {
                    put("id", "bot_openid_demo")
                    put("username", "hoshimi-cat-bot")
                    put("bot", true)
                })
                put("shard", buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive(0))
                    add(kotlinx.serialization.json.JsonPrimitive(1))
                })
            })
        }.toString()
    }

    // 构造 RESUMED 帧，覆盖 reconnect 后的恢复会话完成信号。
    private fun resumedFrame(seq: Int = 1): String {
        return buildJsonObject {
            put("op", 0)
            put("s", seq)
            put("t", "RESUMED")
            put("id", "evt-resumed-1")
            put("d", buildJsonObject {})
        }.toString()
    }

    private inner class FakeTransport : QQOfficialTransport {
        val requests = mutableListOf<RecordedRequest>()
        val gatewaySessions = mutableListOf<FakeGatewaySession>()
        var heartbeatIntervalMillis: Int = 30_000
        var ackHeartbeatDuringSend: Boolean = false
        var tokenExpiresInSeconds: String = "7200"
        var failNextMessageWith401: Boolean = false
        lateinit var lastGatewaySession: FakeGatewaySession
            private set
        private var tokenRequestCounter: Int = 0
        private var gatewayOpenCounter: Int = 0
        private val queuedGatewayBootstraps = mutableListOf<List<String>>()

        override suspend fun getJson(url: String, headers: Map<String, String>): JsonObject {
            requests += RecordedRequest("GET", url, null, headers)
            return buildJsonObject {
                put("url", "wss://gateway.example.qq.com")
                put("shards", 1)
                put("session_start_limit", buildJsonObject {
                    put("total", 1000)
                    put("remaining", 999)
                    put("reset_after", 1)
                    put("max_concurrency", 1)
                })
            }
        }

        override suspend fun postJson(url: String, body: JsonElement, headers: Map<String, String>): JsonObject {
            requests += RecordedRequest("POST", url, body, headers)
            return when {
                url.endsWith("/app/getAppAccessToken") -> buildJsonObject {
                    tokenRequestCounter++
                    put("access_token", if (tokenRequestCounter == 1) "access-token-demo" else "access-token-demo-$tokenRequestCounter")
                    put("expires_in", tokenExpiresInSeconds)
                }
                url.endsWith("/files") -> buildJsonObject {
                    put("file_uuid", "file-uuid-demo")
                    put("file_info", "file-info-demo")
                    put("ttl", 60)
                }
                url.endsWith("/messages") && failNextMessageWith401 -> {
                    failNextMessageWith401 = false
                    throw QQOfficialHttpException(401, """{"message":"unauthorized"}""")
                }
                else -> buildJsonObject {
                    put("id", "message-demo")
                }
            }
        }

        override suspend fun openGateway(url: String, headers: Map<String, String>): QQOfficialGatewaySession {
            requests += RecordedRequest("WS", url, null, headers)
            val bootstrapFrames = if (queuedGatewayBootstraps.isNotEmpty()) {
                queuedGatewayBootstraps.removeAt(0)
            } else if (gatewayOpenCounter == 0) {
                listOf(helloFrame(heartbeatIntervalMillis), readyFrame())
            } else {
                listOf(helloFrame(heartbeatIntervalMillis), resumedFrame())
            }
            gatewayOpenCounter++
            return FakeGatewaySession(
                bootstrapFrames = bootstrapFrames,
                ackHeartbeatDuringSend = ackHeartbeatDuringSend,
            ).also { session ->
                lastGatewaySession = session
                gatewaySessions += session
            }
        }

        /**
         * QQ Official fake transport 只覆盖协议语义测试，这里返回空快照避免引入额外底层资源依赖。
         */
        override fun runtimeObservability(): PlatformObservabilitySnapshot {
            return PlatformObservabilitySnapshot.empty("fake transport")
        }

        override fun close() = Unit

        // 允许测试在启动后继续推送额外的网关事件。
        suspend fun emitGatewayText(text: String) {
            lastGatewaySession.emit(text)
        }

        // 统计 token 请求次数，避免测试依赖请求列表的筛选细节。
        fun tokenRequestCount(): Int {
            return requests.count { it.url.endsWith("/app/getAppAccessToken") }
        }

        // 指定下一次网关打开时注入的 bootstrap 帧，供 identify/resume 分流测试使用。
        fun enqueueGatewayBootstrap(frames: List<String>) {
            queuedGatewayBootstraps += frames
        }
    }

    private data class RecordedRequest(
        val method: String,
        val url: String,
        val body: JsonElement?,
        val headers: Map<String, String>,
    )

    private inner class FakeGatewaySession(
        bootstrapFrames: List<String>,
        private val ackHeartbeatDuringSend: Boolean,
    ) : QQOfficialGatewaySession {
        private val incomingFlow = MutableSharedFlow<String>(replay = 16, extraBufferCapacity = 16)
        private val closed = CompletableDeferred<QQOfficialGatewayClose>()
        val sentTexts = mutableListOf<JsonElement>()

        init {
            bootstrapFrames.forEach { frame ->
                incomingFlow.tryEmit(frame)
            }
        }

        override val incoming: Flow<String> = incomingFlow
        override val closeSignal = closed

        override suspend fun sendText(text: String) {
            val payload = json.parseToJsonElement(text)
            sentTexts += payload
            // 在 sendText 返回前注入 ACK，复现真实网关快速响应时的状态顺序。
            if (ackHeartbeatDuringSend && payload.jsonObject["op"]?.jsonPrimitive?.content == "1") {
                incomingFlow.emit(heartbeatAckFrame())
            }
        }

        override suspend fun close(reason: String) {
            if (!closed.isCompleted) {
                closed.complete(QQOfficialGatewayClose(code = 1000, reason = reason))
            }
        }

        // 允许测试在运行中向适配器注入新的网关消息。
        suspend fun emit(text: String) {
            incomingFlow.emit(text)
        }

        // 允许测试直接注入 QQ 官方 close code，覆盖 adapter 分流逻辑。
        fun closeWithCode(code: Int, reason: String) {
            if (!closed.isCompleted) {
                closed.complete(QQOfficialGatewayClose(code = code, reason = reason))
            }
        }
    }
}
