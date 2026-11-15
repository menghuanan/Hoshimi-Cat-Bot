package top.bilibili.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import top.bilibili.BiliConfig
import top.bilibili.BiliDataWrapper
import top.bilibili.config.BotConfig
import top.bilibili.config.PlatformConfig
import top.bilibili.config.QQOfficialConfig
import top.bilibili.connector.PlatformChatType
import top.bilibili.connector.PlatformContact
import top.bilibili.connector.PlatformInboundMessage
import top.bilibili.connector.PlatformType
import top.bilibili.core.RuntimeConfigSnapshot
import top.bilibili.webui.model.WebUiBotConfigWriteRequestDto
import top.bilibili.webui.model.WebUiConfigBatchSaveRequestDto
import top.bilibili.webui.model.WebUiConfigHotReloadJobDto
import top.bilibili.webui.model.WebUiConfigHotReloadPhase
import top.bilibili.webui.service.WebUiConfigFacade
import top.bilibili.webui.service.WebUiConfigHotReloadApplyService

class QQAdminBindingServiceTest {
    /** 合法私聊成功落盘后立即生效，同一个代码不能被另一个用户重放。 */
    @Test
    fun `private code binds once and preserves unrelated config`() {
        val fixture = Fixture()
        val session = fixture.service.begin()
        assertEquals(session, fixture.service.begin())
        assertTrue(fixture.service.consume(message(session.code!!)))
        assertEquals("user-openid", fixture.config.admin)
        assertEquals(7, fixture.config.firstRunFlag)
        assertEquals("qq_official:private:user-openid", fixture.config.resolvedAdminSubject(null))
        assertEquals(QQAdminBindingService.Phase.SUCCEEDED, fixture.service.read(session.id)?.phase)
        assertNull(fixture.service.read(session.id)?.code)
        assertFalse(fixture.service.consume(message(session.code, "another-user")))
        assertFailsWith<IllegalStateException> { fixture.service.begin() }
    }

    /** 错误代码、群消息、其它平台、机器人回显、错误应用和空用户均不能领取管理员。 */
    @Test
    fun `rejects messages outside exact official private binding context`() {
        val fixture = Fixture()
        val event = message(fixture.service.begin().code!!)
        val rejected = listOf(
            event.copy(messageText = event.messageText + " extra"),
            event.copy(chatType = PlatformChatType.GROUP),
            event.copy(platform = PlatformType.ONEBOT11),
            event.copy(fromSelf = true),
            event.copy(metadata = mapOf("app_id" to "another-app")),
            event.copy(senderContact = event.senderContact.copy(id = "")),
        )
        rejected.forEach { assertFalse(fixture.service.consume(it)) }
        assertNull(fixture.config.admin)
        assertEquals(0, fixture.writes)
    }

    /** 过期、人工编辑、平台切换和停机均应撤销旧绑定能力。 */
    @Test
    fun `expires and invalidates stale contexts`() {
        val fixture = Fixture()
        val first = fixture.service.begin()
        fixture.now = first.expiresAtEpochMillis
        assertFalse(fixture.service.matches(message(first.code!!)))
        assertEquals(QQAdminBindingService.Phase.EXPIRED, fixture.service.read(first.id)?.phase)
        val second = fixture.service.begin()
        assertNotEquals(first.code, second.code)
        fixture.config = fixture.config.copy(admin = "manual-admin")
        assertFalse(fixture.service.consume(message(second.code!!)))
        fixture.config = fixture.config.copy(admin = "", platform = PlatformConfig())
        fixture.service.refreshIfStarted()
        assertNull(fixture.service.read(second.id))
        assertFailsWith<IllegalStateException> { fixture.service.begin() }
        fixture.config = officialConfig()
        fixture.service.refreshIfStarted()
        val third = fixture.service.begin()
        fixture.service.stop()
        assertFalse(fixture.service.consume(message(third.code!!)))
        assertFailsWith<IllegalStateException> { fixture.service.begin() }
    }

    /** 写盘失败不得污染运行态或返回成功，再次创建会话必须更换代码。 */
    @Test
    fun `failed persistence does not grant permission`() {
        val fixture = Fixture(persist = false)
        val session = fixture.service.begin()
        assertTrue(fixture.service.consume(message(session.code!!)))
        assertNull(fixture.config.admin)
        assertEquals(QQAdminBindingService.Phase.FAILED, fixture.service.read(session.id)?.phase)
        assertNotEquals(session.code, fixture.service.begin().code)
    }

    /** 两个发送者并发提交同一码时，只有首个成功落盘者获得管理员。 */
    @Test
    fun `concurrent claims have a single winner`() {
        val fixture = Fixture()
        val code = fixture.service.begin().code!!
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(1)
        try {
            val futures = listOf("first", "second").map { user ->
                executor.submit<Boolean> { ready.await(); fixture.service.consume(message(code, user)) }
            }
            ready.countDown()
            assertEquals(1, futures.count { it.get() })
            assertEquals(1, fixture.writes)
        } finally {
            executor.shutdownNow()
        }
    }

    /** 热重载失败回滚与绑定必须串行；门禁在保存期间仍无锁返回，避免平台停机互等。 */
    @Test
    fun `binding waits for hot reload rollback and keeps the committed admin`() {
        var config = officialConfig()
        var diskConfig = config
        var writes = 0
        val binding = QQAdminBindingService(
            configProvider = { config },
            legacyAdminProvider = { null },
            saveConfig = { candidate ->
                writes++
                diskConfig = candidate
                config = candidate
                true
            },
        ).also { it.startIfNeeded() }
        val event = message(binding.begin().code!!)
        val applying = CountDownLatch(1)
        val releaseApply = CountDownLatch(1)
        val claiming = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(3)
        val token = WebUiConfigFacade(botConfigProvider = { config }).readBotConfig().snapshotToken
        val reload = WebUiConfigHotReloadApplyService(
            captureRuntimeSnapshot = { RuntimeConfigSnapshot(BiliConfig(), BiliDataWrapper(), config) },
            persistBotConfig = { candidate -> diskConfig = candidate; true },
            restoreRuntime = { snapshot -> config = snapshot.botConfig },
            applyRuntime = {
                applying.countDown()
                check(releaseApply.await(5, TimeUnit.SECONDS))
                error("simulated candidate failure")
            },
        )
        try {
            val pendingReload = executor.submit<WebUiConfigHotReloadJobDto> {
                runBlocking {
                    reload.apply("binding-race", WebUiConfigBatchSaveRequestDto(
                        botConfig = WebUiBotConfigWriteRequestDto(
                            snapshotToken = token, platformType = "qq_official", adapter = "qq_official",
                            oneBot11Host = "127.0.0.1", oneBot11Port = 3001, qqOfficialAppId = "app",
                            qqOfficialBotToken = "updated-token",
                        ),
                    ))
                }
            }
            assertTrue(applying.await(5, TimeUnit.SECONDS))
            val claim = executor.submit<Boolean> {
                claiming.countDown()
                binding.consume(event)
            }
            assertTrue(claiming.await(1, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException> { claim.get(100, TimeUnit.MILLISECONDS) }
            assertTrue(executor.submit<Boolean> { binding.matches(event) }.get(1, TimeUnit.SECONDS))
            releaseApply.countDown()
            assertEquals(WebUiConfigHotReloadPhase.FAILED, pendingReload.get(5, TimeUnit.SECONDS).phase)
            assertTrue(claim.get(5, TimeUnit.SECONDS))
            assertEquals("user-openid", config.admin)
            assertEquals("user-openid", diskConfig.admin)
            assertEquals("", diskConfig.platform.qqOfficial.botToken)
            assertEquals(1, writes)
        } finally {
            releaseApply.countDown()
            executor.shutdownNow()
        }
    }

    /** 旧官方配置继续生效；显式清空新字段和旧 OneBot11 账号不阻止首次绑定。 */
    @Test
    fun `resolves legacy admin without crossing platform namespaces`() {
        val config = officialConfig()
        assertEquals("qq_official:private:legacy", config.resolvedAdminSubject("qq_official:private:legacy"))
        assertNull(config.resolvedAdminSubject("onebot11:private:123"))
        assertNull(config.copy(admin = " ").resolvedAdminSubject("qq_official:private:legacy"))
        assertNull(config.copy(admin = "qq_official:group:group").resolvedAdminSubject(null))
        assertEquals("onebot11:private:123", BotConfig(admin = "official").resolvedAdminSubject("onebot11:private:123"))
        val service = QQAdminBindingService({ config }, { "qq_official:private:legacy" }, { true })
        service.startIfNeeded()
        assertFailsWith<IllegalStateException> { service.begin() }
    }

    /** 测试只注入内存配置和可控时钟，绝不读取真实账号或写生产配置。 */
    private class Fixture(private val persist: Boolean = true) {
        var config = officialConfig()
        var now = 1_000L
        var writes = 0
        val service = QQAdminBindingService(
            configProvider = { config },
            legacyAdminProvider = { null },
            saveConfig = { candidate ->
                writes++
                if (persist) config = candidate
                persist
            },
            nowMillis = { now },
        ).also { it.startIfNeeded() }
    }

    companion object {
        /** 最小官方平台 fixture，不依赖任何真实应用凭据。 */
        private fun officialConfig() = BotConfig(
            platform = PlatformConfig(type = PlatformType.QQ_OFFICIAL, qqOfficial = QQOfficialConfig("app", "secret")),
            firstRunFlag = 7,
        )

        /** 模拟 connector 归一后的私聊事件，OpenID 只来自消息作者。 */
        private fun message(code: String, user: String = "user-openid"): PlatformInboundMessage {
            val sender = PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.PRIVATE, user)
            return PlatformInboundMessage(
                platform = PlatformType.QQ_OFFICIAL, chatType = PlatformChatType.PRIVATE,
                chatContact = sender, senderContact = sender, selfContact = sender.copy(id = "bot"),
                messageText = code, searchTexts = listOf(code), hasMention = false, fromSelf = false,
                rawPayload = null, metadata = mapOf("app_id" to "app"),
            )
        }
    }
}
