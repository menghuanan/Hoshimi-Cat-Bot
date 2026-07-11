package top.bilibili.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

class ObservableChannelTest {
    /**
     * 快照只统计仍留在缓冲区的元素，成功消费后必须立即回到零。
     */
    @Test
    fun `snapshot should track buffered elements across send and receive`() = runBlocking {
        val channel = ObservableChannel<String>(2)

        channel.send("first")
        assertEquals(1, channel.snapshot("test").size)

        assertEquals("first", channel.receiveCatching().getOrThrow())
        assertEquals(0, channel.snapshot("test").size)
    }

    /**
     * 无缓冲交接允许接收方先恢复，最终快照仍不得残留虚假的排队元素。
     */
    @Test
    fun `rendezvous handoff should not leave a phantom queued element`() = runBlocking {
        val channel = ObservableChannel<String>(0)
        val received = async { channel.receiveCatching().getOrThrow() }

        channel.send("handoff")

        assertEquals("handoff", received.await())
        assertEquals(0, channel.snapshot("test").size)
    }
}
