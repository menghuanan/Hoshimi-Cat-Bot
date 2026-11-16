package top.bilibili.connector

import kotlinx.serialization.Serializable
import java.util.UUID

/** 群目录使用中立事件；群成员身份和主动推送权限彼此独立，不复用短期发送缓存。 */
data class PlatformGroupObservation(
    val id: String,
    val joined: Boolean = true,
    val acceptsProactive: Boolean? = null,
    val observedAt: Long,
)

/** 当前已提交连接的目录身份；generation 防止热切换后的旧页面和迟到网络结果写入新代际。 */
data class PlatformGroupContext(
    val accountId: String,
    val generation: String,
    val connected: Boolean,
)

/** 查询状态只表达平台能力和故障，不把 HTTP 异常或 vendor JSON 暴露给业务层。 */
enum class GroupLookupStatus { AVAILABLE, FORBIDDEN, UNAVAILABLE, UNSUPPORTED }

/** 两个官方接口分别记录权限，群资料失败不得抹掉另一个接口已成功返回的状态。 */
data class PlatformGroupProfile(
    val name: String? = null,
    val acceptsProactive: Boolean? = null,
    val nameStatus: GroupLookupStatus = GroupLookupStatus.UNSUPPORTED,
    val stateStatus: GroupLookupStatus = GroupLookupStatus.UNSUPPORTED,
)

/** 群目录持久记录按应用隔离；缓存存在不构成当前发送授权。 */
@Serializable
data class PlatformKnownGroup(
    val accountId: String,
    val id: String,
    val name: String = "",
    val alias: String = "",
    val joined: Boolean = true,
    val acceptsProactive: Boolean? = null,
    val observedAt: Long = 0,
    val refreshedAt: Long = 0,
) {
    /** 订阅层始终保存完整 subject，不把 OpenID 当数字群号解析。 */
    val subject: String get() = "qq_official:group:$id"
}

/** 候选连接仅缓冲目录事件，commit 后才交给业务；关闭后的事件绝不重新激活旧代际。 */
internal class PlatformGroupObservationGate(
    val accountId: String,
    private val sink: (String, PlatformGroupObservation) -> Unit,
) {
    val generation: String = UUID.randomUUID().toString()
    private val pending = linkedMapOf<String, PlatformGroupObservation>()
    private var active = false
    private var closed = false

    /** 网关回调仅更新有界内存；相同群合并时保留最后已知主动消息状态。 */
    @Synchronized
    fun observe(event: PlatformGroupObservation) {
        if (closed || accountId.isBlank() || !validPlatformGroupId(event.id)) return
        if (active) sink(accountId, event)
        else {
            if (pending.size >= 10_000 && event.id !in pending) pending.remove(pending.keys.first())
            val previous = pending[event.id]
            pending[event.id] = event.copy(acceptsProactive = event.acceptsProactive ?: if (previous?.joined == false && event.joined) null else previous?.acceptsProactive)
        }
    }

    /** manager 在选中本代际后激活，补发握手期间已收到的群事件。 */
    @Synchronized
    fun activate() {
        if (closed) return
        active = true
        pending.values.forEach { sink(accountId, it) }
        pending.clear()
    }

    /** 候选回滚和旧连接退场都清空缓冲并拒收迟到事件。 */
    @Synchronized
    fun close() { closed = true; active = false; pending.clear() }
}

/** OpenID 是不透明字符串；只拒绝空值、路径/subject 分隔符和控制字符。 */
fun validPlatformGroupId(id: String): Boolean =
    id.isNotBlank() && id.length <= 256 && id.none { it.isWhitespace() || it.isISOControl() || it in ":/\\?#" }
