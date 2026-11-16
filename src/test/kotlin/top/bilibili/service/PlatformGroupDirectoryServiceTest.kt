package top.bilibili.service

import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import top.bilibili.config.PlatformGroupDirectoryStore
import top.bilibili.connector.*

class PlatformGroupDirectoryServiceTest {
    /** 使用临时目录和可控时钟测试真实 UTF-8 持久化，不依赖机器人账号。 */
    private class Fixture {
        val file = Files.createTempDirectory("qq-directory-test").resolve("groups.json").toFile()
        var time = 100_000L
        var context: PlatformGroupContext? = PlatformGroupContext("app-a", "generation-a", true)
        val requests = mutableListOf<Pair<Boolean, Boolean>>()
        var lookup: suspend (String, Boolean, Boolean) -> PlatformGroupProfile = { _, _, _ ->
            PlatformGroupProfile("测试群", true, GroupLookupStatus.AVAILABLE, GroupLookupStatus.AVAILABLE)
        }
        /** 新实例共享同一磁盘文件，模拟重启但不共享内存状态。 */
        fun service() = PlatformGroupDirectoryService({ context }, { true }, { _, id, name, state ->
            requests += name to state
            lookup(id, name, state)
        }, PlatformGroupDirectoryStore(file), { time }).also { it.initialize() }
        /** 普通消息只发现身份，不凭空授予主动推送权限。 */
        fun event(joined: Boolean = true, proactive: Boolean? = null) = PlatformGroupObservation("opaque-id", joined, proactive, time)
    }

    /** 名称与备注可跨重启恢复，同一 OpenID 在另一 AppID 下不可见也不可提交。 */
    @Test fun `restores metadata and isolates application namespace`() = runBlocking {
        val f = Fixture(); val service = f.service()
        service.observe("app-a", f.event())
        service.tick()
        service.rename("generation-a", "opaque-id", "本地备注")
        val restored = f.service()
        assertEquals("本地备注", restored.snapshot().groups.single().alias)
        assertEquals("测试群", restored.snapshot().groups.single().name)
        f.context = PlatformGroupContext("app-b", "generation-b", true)
        assertTrue(restored.snapshot().groups.isEmpty())
        assertFailsWith<IllegalArgumentException> { restored.validateSelection(listOf("qq_official:group:opaque-id"), "generation-b") }
        assertFailsWith<IllegalArgumentException> { restored.validateSelection(emptyList(), "generation-a") }
    }

    /** 关闭通知保留群身份，退群禁选，重新入群恢复选择但不恢复旧授权。 */
    @Test fun `notification rejection does not delete membership`() {
        val f = Fixture(); val service = f.service()
        service.observe("app-a", f.event(proactive = false))
        service.validateSelection(listOf("qq_official:group:opaque-id"), "generation-a")
        assertFalse(service.snapshot().groups.single().acceptsProactive!!)
        service.observe("app-a", f.event(joined = false, proactive = false))
        assertFailsWith<IllegalArgumentException> { service.validateSelection(listOf("qq_official:group:opaque-id"), "generation-a") }
        service.observe("app-a", f.event())
        service.validateSelection(listOf("qq_official:group:opaque-id"), "generation-a")
        assertNull(service.snapshot().groups.single().acceptsProactive)
    }

    /** 单个接口无白名单后停止重复调用，另一接口仍可刷新，手动刷新允许重新探测权限。 */
    @Test fun `permission fallback is independent and refresh is rate limited`() = runBlocking {
        val f = Fixture(); val service = f.service()
        f.lookup = { _, _, _ -> PlatformGroupProfile(acceptsProactive = false, nameStatus = GroupLookupStatus.FORBIDDEN, stateStatus = GroupLookupStatus.AVAILABLE) }
        service.observe("app-a", f.event()); service.tick()
        service.observe("app-a", f.event().copy(id = "second-id")); service.tick()
        assertEquals(1, f.requests.size)
        f.time += 3000; service.tick()
        assertEquals(false to true, f.requests.last())
        assertEquals(GroupLookupStatus.FORBIDDEN, service.snapshot().nameStatus)
        service.requestRefresh("generation-a")
        assertFailsWith<IllegalArgumentException> { service.requestRefresh("generation-a") }
        f.time += 3000; service.tick()
        assertEquals(true to true, f.requests.last())
    }

    /** 连接切换中的迟到响应不得写回目录，等待中也不持有事件内存锁。 */
    @Test fun `discards response after generation switch`() = runBlocking {
        val f = Fixture(); val service = f.service()
        val started = CompletableDeferred<Unit>(); val result = CompletableDeferred<PlatformGroupProfile>()
        f.lookup = { _, _, _ -> started.complete(Unit); result.await() }
        service.observe("app-a", f.event())
        val refresh = async { service.tick() }; started.await()
        f.context = PlatformGroupContext("app-b", "generation-b", true)
        result.complete(PlatformGroupProfile("过期名称", true, GroupLookupStatus.AVAILABLE, GroupLookupStatus.AVAILABLE))
        refresh.await()
        f.context = PlatformGroupContext("app-a", "generation-c", true)
        assertEquals("", service.snapshot().groups.single().name)
    }

    /** 查询途中关闭通知，即使时间戳相同也不能被查询开始前的权限快照覆盖。 */
    @Test fun `new notification event wins over in flight query`() = runBlocking {
        val f = Fixture(); val service = f.service()
        val started = CompletableDeferred<Unit>(); val result = CompletableDeferred<PlatformGroupProfile>()
        f.lookup = { _, _, _ -> started.complete(Unit); result.await() }
        service.observe("app-a", f.event())
        val refresh = async { service.tick() }; started.await()
        service.observe("app-a", f.event(proactive = false))
        result.complete(PlatformGroupProfile("测试群", true, GroupLookupStatus.AVAILABLE, GroupLookupStatus.AVAILABLE))
        refresh.await()
        assertFalse(service.snapshot().groups.single().acceptsProactive!!)
    }

    /** 损坏文件必须保留供修复，不被后台刷新或备注写入覆盖。 */
    @Test fun `corrupt store remains untouched`() {
        val f = Fixture(); f.file.writeText("{broken", Charsets.UTF_8)
        val service = f.service(); service.observe("app-a", f.event()); service.flush()
        assertEquals("{broken", f.file.readText(Charsets.UTF_8))
        assertNotNull(service.snapshot().storageError)
        assertFailsWith<IllegalStateException> { service.rename("generation-a", "opaque-id", "备注") }
    }

    /** 文件写失败保留内存记录和原备注，恢复可写后周期任务能重新提交。 */
    @Test fun `failed write keeps dirty state for retry`() {
        val f = Fixture(); val service = f.service()
        service.observe("app-a", f.event())
        assertTrue(f.file.mkdir())
        f.file.resolve("blocker").writeText("keep", Charsets.UTF_8)
        assertFalse(service.flush())
        assertFails { service.rename("generation-a", "opaque-id", "不能保存的备注") }
        assertEquals("", service.snapshot().groups.single().alias)
        assertTrue(f.file.resolve("blocker").delete()); assertTrue(f.file.delete())
        assertTrue(service.flush()); assertNull(service.snapshot().storageError)
        assertEquals("opaque-id", f.service().snapshot().groups.single().id)
    }
}
