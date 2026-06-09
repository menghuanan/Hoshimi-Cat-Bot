package top.bilibili.connector

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertNotSame
import top.bilibili.config.BotConfig
import top.bilibili.config.NapCatConfig
import top.bilibili.config.PlatformConfig

class PlatformConnectorManagerTest {
    @Test
    fun `start should only create one adapter generation while manager is running`() {
        val createdAdapters = mutableListOf<RecordingAdapter>()
        val manager = PlatformConnectorManager(
            config = BotConfig(),
            adapterFactory = {
                RecordingAdapter().also(createdAdapters::add)
            },
        )

        manager.start()
        manager.start()

        assertEquals(1, createdAdapters.size)
        assertEquals(1, createdAdapters.single().startCount)
    }

    @Test
    fun `stop followed by start should replace stopped adapter generation`() = runBlocking {
        val createdAdapters = mutableListOf<RecordingAdapter>()
        val manager = PlatformConnectorManager(
            config = BotConfig(),
            adapterFactory = {
                RecordingAdapter().also(createdAdapters::add)
            },
        )

        manager.start()
        val firstGeneration = createdAdapters.single()
        manager.stop()
        manager.start()
        val secondGeneration = createdAdapters.last()

        assertEquals(2, createdAdapters.size)
        assertNotSame(firstGeneration, secondGeneration)
        assertEquals(1, firstGeneration.stopCount)
        assertEquals(1, secondGeneration.startCount)
        assertEquals(0, secondGeneration.stopCount)
    }

    @Test
    fun `start failure should discard half initialized adapter generation`() {
        var creationCount = 0
        val createdAdapters = mutableListOf<RecordingAdapter>()
        val manager = PlatformConnectorManager(
            config = BotConfig(),
            adapterFactory = {
                creationCount++
                RecordingAdapter(failOnStart = creationCount == 1).also(createdAdapters::add)
            },
        )

        assertFailsWith<IllegalStateException> {
            manager.start()
        }

        assertFalse(manager.isInitialized())
        manager.start()

        assertEquals(2, createdAdapters.size)
        assertEquals(1, createdAdapters[0].startCount)
        assertEquals(1, createdAdapters[1].startCount)
    }

    @Test
    fun `reload should keep old adapter when candidate start fails`() = runBlocking {
        val oldAdapter = RecordingAdapter(sendResult = true)
        val newAdapter = RecordingAdapter(failOnStart = true)
        val manager = PlatformConnectorManager(
            config = BotConfig(),
            adapterFactory = { oldAdapter },
        )
        manager.initialize()
        manager.start()

        val result = manager.prepareReload(BotConfig(), adapterFactory = { newAdapter })

        assertFalse(result.success)
        assertNull(result.prepared)
        assertEquals(1, oldAdapter.startCount)
        assertEquals(0, oldAdapter.stopCount)
        assertTrue(manager.sendMessage(PlatformContact(PlatformType.ONEBOT11, PlatformChatType.GROUP, "1"), emptyList()))
    }

    @Test
    fun `reload should keep old adapter when candidate never becomes connected`() = runBlocking {
        val oldAdapter = RecordingAdapter(sendResult = true)
        val newAdapter = RecordingAdapter(connectedAfterStart = false)
        val manager = PlatformConnectorManager(
            config = BotConfig(),
            adapterFactory = { oldAdapter },
        )
        manager.initialize()
        manager.start()

        // start() 返回并不代表后台 WebSocket 已连通；prepare 必须等候 runtimeStatus 进入 connected。
        val result = manager.prepareReload(
            BotConfig(platform = testPlatformConfig(connectTimeoutMillis = 20L)),
            adapterFactory = { newAdapter },
        )

        assertFalse(result.success)
        assertNull(result.prepared)
        assertEquals(1, newAdapter.stopCount)
        assertEquals(0, oldAdapter.stopCount)
        assertTrue(manager.sendMessage(PlatformContact(PlatformType.ONEBOT11, PlatformChatType.GROUP, "1"), emptyList()))
    }

    @Test
    fun `commit reload should route sends through candidate adapter`() = runBlocking {
        val oldAdapter = RecordingAdapter(sendResult = false)
        val newAdapter = RecordingAdapter(sendResult = true)
        val manager = PlatformConnectorManager(
            config = BotConfig(),
            adapterFactory = { oldAdapter },
        )
        manager.initialize()
        manager.start()

        val prepared = manager.prepareReload(BotConfig(), adapterFactory = { newAdapter }).prepared
        val result = manager.commitReload(requireNotNull(prepared))

        assertTrue(result.success)
        assertEquals(1, oldAdapter.stopCount)
        assertTrue(manager.sendMessage(PlatformContact(PlatformType.ONEBOT11, PlatformChatType.GROUP, "1"), emptyList()))
    }

    @Test
    fun `event flow collector should receive candidate events after commit without resubscribing`() = runBlocking {
        val oldAdapter = RecordingAdapter()
        val newAdapter = RecordingAdapter()
        val manager = PlatformConnectorManager(
            config = BotConfig(),
            adapterFactory = { oldAdapter },
        )
        manager.initialize()
        manager.start()
        val managerFlow = manager.eventFlow
        val collected = async { managerFlow.first() }

        val prepared = requireNotNull(manager.prepareReload(BotConfig(), adapterFactory = { newAdapter }).prepared)
        val result = manager.commitReload(prepared)
        newAdapter.emitEvent(inboundMessage("candidate-event"))

        assertTrue(result.success)
        assertEquals("candidate-event", withTimeout(1_000) { collected.await().messageText })
    }

    /**
     * 记录 manager 对 adapter 生命周期调用次数，确保测试只验证代际切换语义。
     */
    private class RecordingAdapter(
        private val failOnStart: Boolean = false,
        private val sendResult: Boolean = false,
        private val connectedAfterStart: Boolean = true,
        private val readyForReload: Boolean = connectedAfterStart,
    ) : PlatformAdapter {
        var startCount: Int = 0
            private set
        var stopCount: Int = 0
            private set
        private var connected: Boolean = false

        private val mutableEvents = MutableSharedFlow<PlatformInboundMessage>()
        override val eventFlow = mutableEvents

        override fun start() {
            startCount++
            if (failOnStart) {
                throw IllegalStateException("start failure")
            }
            connected = connectedAfterStart
        }

        override suspend fun awaitReadyForReload(timeoutMillis: Long): Boolean {
            return readyForReload
        }

        override suspend fun stop() {
            stopCount++
            connected = false
        }

        suspend fun emitEvent(message: PlatformInboundMessage) {
            mutableEvents.emit(message)
        }

        override fun declaredCapabilities(): Set<PlatformCapability> = emptySet()

        override suspend fun sendMessage(contact: PlatformContact, message: List<OutgoingPart>): Boolean = sendResult

        override fun runtimeStatus(): PlatformRuntimeStatus = PlatformRuntimeStatus(
            connected = connected,
            reconnectAttempts = 0,
        )

        override suspend fun isContactReachable(contact: PlatformContact): Boolean = false

        override suspend fun canAtAll(contact: PlatformContact): Boolean = false
    }

    /**
     * 测试事件只填平台中立字段，避免 connector manager 测试依赖 vendor payload。
     */
    private fun inboundMessage(text: String): PlatformInboundMessage {
        val chat = PlatformContact(PlatformType.ONEBOT11, PlatformChatType.GROUP, "1")
        val sender = PlatformContact(PlatformType.ONEBOT11, PlatformChatType.PRIVATE, "2")
        val self = PlatformContact(PlatformType.ONEBOT11, PlatformChatType.PRIVATE, "3")
        return PlatformInboundMessage(
            platform = PlatformType.ONEBOT11,
            chatType = PlatformChatType.GROUP,
            chatContact = chat,
            senderContact = sender,
            selfContact = self,
            messageText = text,
            searchTexts = listOf(text),
            hasMention = false,
            fromSelf = false,
            rawPayload = null,
        )
    }

    /**
     * 测试配置只缩短候选连通等待时间，避免回归用例在断线候选上长时间阻塞。
     */
    private fun testPlatformConfig(connectTimeoutMillis: Long): PlatformConfig {
        return PlatformConfig(
            type = PlatformType.ONEBOT11,
            adapter = "onebot11",
            onebot11 = NapCatConfig(connectTimeout = connectTimeoutMillis),
        )
    }
}
