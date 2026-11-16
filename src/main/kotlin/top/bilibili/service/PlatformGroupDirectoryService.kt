package top.bilibili.service

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import top.bilibili.config.PlatformGroupDirectoryStore
import top.bilibili.connector.*

/** 群目录编排只持有有界元数据；网络经 manager，磁盘经 config Store，周期生命周期属于 Tasker。 */
class PlatformGroupDirectoryService(
    private val contextProvider: () -> PlatformGroupContext?,
    private val officialSelected: () -> Boolean,
    private val lookup: suspend (PlatformGroupContext, String, Boolean, Boolean) -> PlatformGroupProfile,
    private val store: PlatformGroupDirectoryStore = PlatformGroupDirectoryStore(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val logger = LoggerFactory.getLogger(PlatformGroupDirectoryService::class.java)
    private val lock = Any()
    private val diskLock = Any()
    private val refreshMutex = Mutex()
    private val groups = linkedMapOf<Pair<String, String>, PlatformKnownGroup>()
    private var initialized = false
    private var readFailure = false
    private var revision = 0L
    private var persistedRevision = 0L
    private var storageError: String? = null
    private var refreshGeneration = ""
    private val attempted = mutableMapOf<String, Long>()
    private var nameStatus = GroupLookupStatus.UNSUPPORTED
    private var stateStatus = GroupLookupStatus.UNSUPPORTED
    private var nextQueryAt = 0L
    private var lastManualRefreshAt: Long? = null

    /** 文件读取先于 connector 激活；坏文件降级为内存目录并阻止覆盖原件。 */
    fun initialize() = synchronized(lock) {
        if (initialized) return@synchronized
        try { store.load().forEach { groups[it.accountId to it.id] = it } }
        catch (_: Exception) {
            readFailure = true
            storageError = "群目录文件无法读取，原文件已保留；本次发现记录仅保存在内存，请检查 data/platform-groups.json"
            logger.warn(storageError)
        }
        initialized = true
    }

    /** connector 回调仅做内存合并；普通消息不重置已知通知开关，相同群活跃时间最多每分钟更新。 */
    fun observe(accountId: String, event: PlatformGroupObservation) = synchronized(lock) {
        if (!validPlatformGroupId(event.id) || accountId.isBlank()) return@synchronized
        val key = accountId to event.id
        val previous = groups[key]
        if (previous == null && groups.size >= 10_000) {
            storageError = "群目录已达到 10000 条容量上限，新增群暂未记录"
            return@synchronized
        }
        val base = previous ?: PlatformKnownGroup(accountId, event.id)
        // 重新入群只确认成员身份，旧退群周期的通知状态不应冒充新周期的已知状态。
        val proactive = event.acceptsProactive ?: if (!base.joined && event.joined) null else base.acceptsProactive
        val changed = base.joined != event.joined || base.acceptsProactive != proactive
        if (previous != null && !changed && event.observedAt - base.observedAt < 60_000) return@synchronized
        groups[key] = base.copy(joined = event.joined, acceptsProactive = proactive, observedAt = event.observedAt)
        revision++
    }

    /** 同一文件的写入串行且不持有网关内存锁；失败保留 dirty revision 供下次重试。 */
    fun flush(): Boolean = synchronized(diskLock) {
        val candidate = synchronized(lock) snapshot@{
            if (!initialized || revision == persistedRevision) return@snapshot null
            if (readFailure) return@snapshot null
            revision to groups.values.toList()
        } ?: return@synchronized true
        try {
            store.save(candidate.second)
            synchronized(lock) { persistedRevision = candidate.first; storageError = null }
            true
        } catch (_: Exception) {
            synchronized(lock) { storageError = "群目录保存失败，记录仍在内存，下次自动重试" }
            logger.warn("群目录保存失败，保留待写快照并稍后重试")
            false
        }
    }

    /** 管理面读取只返回当前 AppID 的不可变快照，不自动访问远程 API。 */
    fun snapshot(): GroupDirectorySnapshot {
        initialize()
        val context = contextProvider()
        return synchronized(lock) {
            GroupDirectorySnapshot(
                enabled = context != null || officialSelected(), context = context,
                groups = groups.values.filter { it.accountId == context?.accountId }.sortedWith(compareBy({ !it.joined }, { it.alias.ifBlank { it.name } }, { it.id })),
                nameStatus = if (refreshGeneration == context?.generation) nameStatus else GroupLookupStatus.UNSUPPORTED,
                stateStatus = if (refreshGeneration == context?.generation) stateStatus else GroupLookupStatus.UNSUPPORTED,
                storageError = storageError,
            )
        }
    }

    /** 每次仅刷新一个已知群；至少 2.1 秒间隔，两个接口各自低于 30 QPM，失败五分钟后再试。 */
    suspend fun tick() = refreshMutex.withLock {
        initialize()
        flush()
        val context = contextProvider()?.takeIf { it.connected } ?: return@withLock
        val request = synchronized(lock) {
            if (refreshGeneration != context.generation) {
                refreshGeneration = context.generation
                attempted.clear()
                nameStatus = GroupLookupStatus.UNSUPPORTED
                stateStatus = GroupLookupStatus.UNSUPPORTED
            }
            if (now() < nextQueryAt || (nameStatus == GroupLookupStatus.FORBIDDEN && stateStatus == GroupLookupStatus.FORBIDDEN)) return@synchronized null
            val candidate = groups.values.filter { it.accountId == context.accountId && it.joined }
                .filter { (attempted[it.id] ?: 0L) <= now() }
                .minByOrNull { attempted[it.id] ?: 0L } ?: return@synchronized null
            nextQueryAt = now() + 2_100
            Triple(candidate, nameStatus != GroupLookupStatus.FORBIDDEN, stateStatus != GroupLookupStatus.FORBIDDEN)
        } ?: return@withLock
        val result = lookup(context, request.first.id, request.second, request.third)
        if (contextProvider()?.generation != context.generation) return@withLock
        synchronized(lock) {
            if (request.second) nameStatus = result.nameStatus
            if (request.third) stateStatus = result.stateStatus
            val failed = result.nameStatus == GroupLookupStatus.UNAVAILABLE || result.stateStatus == GroupLookupStatus.UNAVAILABLE
            attempted[request.first.id] = now() + if (failed) 5 * 60_000 else 6 * 60 * 60_000
            val key = context.accountId to request.first.id
            val latest = groups[key] ?: return@synchronized
            if (!latest.joined) return@synchronized
            // 查询中若收到退群或通知事件，以新事件为准，不让旧响应恢复权限。
            val updated = latest.copy(
                name = result.name?.takeIf { it.isNotBlank() } ?: latest.name,
                acceptsProactive = if (latest === request.first) result.acceptsProactive ?: latest.acceptsProactive else latest.acceptsProactive,
                refreshedAt = if (result.nameStatus == GroupLookupStatus.AVAILABLE || result.stateStatus == GroupLookupStatus.AVAILABLE) now() else latest.refreshedAt,
            )
            if (latest != updated) { groups[key] = updated; revision++ }
        }
        flush()
    }

    /** 手动刷新只重排已知群，不承诺拉取全部群；限制一分钟一次，权限申请后可主动重试。 */
    fun requestRefresh(generation: String) {
        requireContext(generation)
        synchronized(lock) {
            require(lastManualRefreshAt?.let { now() - it >= 60_000 } != false) { "请等待一分钟后再刷新" }
            lastManualRefreshAt = now()
            attempted.clear()
            nameStatus = GroupLookupStatus.UNSUPPORTED
            stateStatus = GroupLookupStatus.UNSUPPORTED
        }
    }

    /** 备注独立于真实群名；原子保存成功后才向用户确认，失败恢复原备注。 */
    fun rename(generation: String, id: String, alias: String) = synchronized(diskLock) {
        val context = requireContext(generation)
        require(alias.trim().length <= 80 && alias.none { it.isISOControl() }) { "备注最多 80 字且不能包含控制字符" }
        val key = context.accountId to id
        val candidate = synchronized(lock) {
            check(!readFailure) { "群目录文件无法读取，暂不能保存备注" }
            val current = groups[key] ?: error("群聊不存在，请刷新列表")
            revision to groups.toMutableMap().also { it[key] = current.copy(alias = alias.trim()) }.values.toList()
        }
        // 磁盘写入期间允许网关继续收集事件，提交后仅合并备注；新观察保持 dirty 等待下一轮保存。
        store.save(candidate.second)
        synchronized(lock) {
            groups[key] = requireNotNull(groups[key]).copy(alias = alias.trim())
            revision++
            persistedRevision = candidate.first + 1
            storageError = null
        }
    }

    /** 创建订阅前与提交候选时都校验代际和成员记录，拒绝旧页面、私聊或任意手填 OpenID。 */
    fun validateSelection(subjects: List<String>, generation: String) {
        val context = requireContext(generation)
        require(subjects.size <= 100) { "目标群聊最多选择 100 个" }
        synchronized(lock) {
            require(subjects.all { subject -> groups.values.any { it.accountId == context.accountId && it.subject == subject && it.joined } }) { "目标群聊不存在或已退出，请刷新列表" }
        }
    }

    /** 连接短暂断开仍允许保存已知群订阅，平台或应用切换则必须重新选择。 */
    private fun requireContext(generation: String): PlatformGroupContext {
        val context = contextProvider()
        require(context != null && generation.isNotBlank() && context.generation == generation) { "平台已变更或尚未启动，请刷新群列表后重试" }
        return context
    }
}

/** 服务快照不携带配置、令牌或 transport，仅供 WebUI facade 映射显示字段。 */
data class GroupDirectorySnapshot(
    val enabled: Boolean,
    val context: PlatformGroupContext?,
    val groups: List<PlatformKnownGroup>,
    val nameStatus: GroupLookupStatus,
    val stateStatus: GroupLookupStatus,
    val storageError: String?,
)
