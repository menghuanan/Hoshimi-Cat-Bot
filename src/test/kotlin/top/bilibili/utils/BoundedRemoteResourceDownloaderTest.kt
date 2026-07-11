package top.bilibili.utils

import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BoundedRemoteResourceDownloaderTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    /** 测试传输把公共测试域映射到本地 server，策略解析仍返回真实公网分类地址。 */
    @BeforeTest
    fun setup() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .dns(object : Dns {
                /** 测试传输始终连接本地 server，策略 DNS 由 downloadForTest 独立提供。 */
                override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getLoopbackAddress())
            })
            .followRedirects(false)
            .build()
    }

    /** 每个用例关闭本地 server 与连接池，避免线程泄漏影响全量测试。 */
    @AfterTest
    fun cleanup() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        server.shutdown()
    }

    /** chunked 响应没有 Content-Length，仍必须按实际流式字节完整读取。 */
    @Test
    fun `chunked response should be bounded by actual bytes`() = runBlocking {
        val payload = "chunked-body".toByteArray()
        server.enqueue(MockResponse().setChunkedBody(Buffer().write(payload), 3))

        val downloaded = download("public.test")

        assertContentEquals(payload, downloaded)
    }

    /** 伪造较小 Content-Length 不能绕过实际读取硬上限。 */
    @Test
    fun `body larger than declared length should be rejected by actual bytes`() = runBlocking {
        val oversized = ByteArray((RemoteResourcePolicy.MAX_RESPONSE_BYTES + 1L).toInt())
        server.enqueue(
            MockResponse()
                .setHeader("Content-Length", "1")
                .setBody(Buffer().write(oversized)),
        )

        assertFailsWith<RemoteResourceRejectedException> { download("public.test") }
    }

    /** 精确 25 MiB 可接受，超过一个字节必须拒绝。 */
    @Test
    fun `twenty five mib boundary should be exact`() = runBlocking {
        val boundary = ByteArray(RemoteResourcePolicy.MAX_RESPONSE_BYTES.toInt())
        server.enqueue(MockResponse().setBody(Buffer().write(boundary)))
        server.enqueue(MockResponse().setBody(Buffer().write(boundary).writeByte(0)))

        assertEquals(boundary.size, download("public.test").size)
        assertFailsWith<RemoteResourceRejectedException> { download("public.test") }
    }

    /** 公共首跳重定向到私网主机时，第二跳发出请求前必须拒绝。 */
    @Test
    fun `redirect from public host to private host should be rejected before second request`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://private.test:${server.port}/secret"))

        assertFailsWith<RemoteResourceRejectedException> { download("public.test") }
        assertEquals(1, server.requestCount)
    }

    /** 组合受控传输 URL 与策略 DNS，生产代码仍使用系统 DNS 双重校验。 */
    private suspend fun download(host: String): ByteArray {
        val url = server.url("/resource").newBuilder().host(host).build().toString()
        return BoundedRemoteResourceDownloader.downloadForTest(url, client) { policyHost ->
            if (policyHost == "private.test") listOf(InetAddress.getByName("127.0.0.1"))
            else listOf(InetAddress.getByName("93.184.216.34"))
        }
    }
}
