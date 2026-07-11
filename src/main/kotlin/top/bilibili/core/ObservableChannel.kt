package top.bilibili.core

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelIterator
import kotlinx.coroutines.channels.ChannelResult

/**
 * 为有界业务 Channel 补充无副作用的填充度快照，同时保留标准 Channel 的背压与关闭语义。
 */
class ObservableChannel<E>(
    private val capacity: Int,
    private val delegate: Channel<E> = Channel(capacity),
) : Channel<E> by delegate {
    private val enqueuedTotal = AtomicLong(0)
    private val dequeuedTotal = AtomicLong(0)

    /** 成功入队后更新观测计数；发送失败或取消不会污染快照。 */
    override suspend fun send(element: E) {
        delegate.send(element)
        enqueuedTotal.incrementAndGet()
    }

    /** 非阻塞发送仅在成功时计入队列占用。 */
    override fun trySend(element: E): ChannelResult<Unit> {
        return delegate.trySend(element).also { result ->
            if (result.isSuccess) enqueuedTotal.incrementAndGet()
        }
    }

    /** 成功领取元素后扣减占用，关闭且无元素时保持计数不变。 */
    override suspend fun receiveCatching(): ChannelResult<E> {
        return delegate.receiveCatching().also(::recordReceive)
    }

    /** 非阻塞领取成功后同步扣减占用。 */
    override fun tryReceive(): ChannelResult<E> {
        return delegate.tryReceive().also(::recordReceive)
    }

    /** for 循环通过包装迭代器领取元素，确保消费路径同样更新快照。 */
    override fun iterator(): ChannelIterator<E> {
        val iterator = delegate.iterator()
        return object : ChannelIterator<E> {
            override suspend fun hasNext(): Boolean = iterator.hasNext()

            override fun next(): E {
                return iterator.next().also { decrementQueuedCount() }
            }
        }
    }

    /** 返回当前队列的只读填充度，供 ProcessGuardian 采样。 */
    fun snapshot(name: String): LocalQueueSnapshot {
        val size = (enqueuedTotal.get() - dequeuedTotal.get()).coerceIn(0, capacity.toLong()).toInt()
        return LocalQueueSnapshot(name, size, capacity)
    }

    /** 成功领取只增加累计出队数，避免发送与接收续体的执行顺序造成永久计数偏差。 */
    private fun recordReceive(result: ChannelResult<E>) {
        if (result.isSuccess) decrementQueuedCount()
    }

    /** 累计出队数允许短暂领先入队数，快照读取时会把瞬时负差归零。 */
    private fun decrementQueuedCount() {
        dequeuedTotal.incrementAndGet()
    }
}

/**
 * 本地有界队列的轻量观测值，不包含消息内容，避免监控接口泄露业务数据。
 */
data class LocalQueueSnapshot(
    val name: String,
    val size: Int,
    val capacity: Int,
) {
    /** 以 0..1 比例表达填充度，零容量交接队列不会被误报为满载。 */
    val fillRatio: Double
        get() = if (capacity == 0) 0.0 else size.toDouble() / capacity
}
