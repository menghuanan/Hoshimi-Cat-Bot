package top.bilibili.utils

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** 远程资源被网络边界策略拒绝或响应超过硬限制。 */
class RemoteResourceRejectedException(message: String) : Exception(message)

/**
 * 共享的有界远程资源下载入口，负责 DNS、逐跳重定向、超时、并发与响应体限制。
 */
object BoundedRemoteResourceDownloader : Closeable {
    private const val BUFFER_SIZE = 8 * 1024
    private val downloadPermits = Semaphore(2)
    private val validatingDns = object : Dns {
        /** 每次实际连接解析都校验全部地址，混合 DNS 结果同样拒绝。 */
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = Dns.SYSTEM.lookup(hostname)
            return try {
                RemoteResourcePolicy.validateResolvedAddresses(hostname, addresses)
                addresses
            } catch (error: IllegalArgumentException) {
                throw java.net.UnknownHostException(error.message).apply { initCause(error) }
            }
        }
    }
    private val client = OkHttpClient.Builder()
        .dns(validatingDns)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 下载 URL 内容；初始目标、每次 DNS 结果和每一跳重定向都会重新验证。
     */
    suspend fun download(url: String): ByteArray = downloadPermits.withPermit {
        withContext(Dispatchers.IO) {
            var current = RemoteResourcePolicy.validateUri(url)
            for (redirectCount in 0..RemoteResourcePolicy.MAX_REDIRECTS) {
                validateLiteralOrResolvedHost(current)
                val response = execute(current)
                response.use {
                    if (it.isRedirect) {
                        if (redirectCount >= RemoteResourcePolicy.MAX_REDIRECTS) {
                            throw RemoteResourceRejectedException("远程资源重定向次数超限")
                        }
                        val location = it.header("Location")
                            ?: throw RemoteResourceRejectedException("重定向响应缺少 Location")
                        current = RemoteResourcePolicy.validateUri(current.resolve(location).toString())
                    } else {
                        if (!it.isSuccessful) {
                            throw RemoteResourceRejectedException("远程资源响应失败: HTTP ${it.code}")
                        }
                        return@withContext readBounded(it)
                    }
                }
            }
            throw RemoteResourceRejectedException("远程资源重定向次数超限")
        }
    }

    /** 对 IP 字面量和域名执行请求前校验，连接时的 DNS 仍由 validatingDns 再校验。 */
    private fun validateLiteralOrResolvedHost(uri: URI) {
        val addresses = InetAddress.getAllByName(uri.host).toList()
        try {
            RemoteResourcePolicy.validateResolvedAddresses(uri.host, addresses)
        } catch (error: IllegalArgumentException) {
            throw RemoteResourceRejectedException(error.message ?: "远程主机不是公网地址")
        }
    }

    /** 创建单次请求，禁用客户端自动重定向后由上层逐跳处理。 */
    private fun execute(uri: URI): Response {
        val request = Request.Builder().url(uri.toString()).get().build()
        return client.newCall(request).execute()
    }

    /** 实际流式读取响应体，不能信任或仅依赖 Content-Length。 */
    private fun readBounded(response: Response): ByteArray {
        val body = response.body ?: throw RemoteResourceRejectedException("远程资源响应体为空")
        if (body.contentLength() > RemoteResourcePolicy.MAX_RESPONSE_BYTES) {
            throw RemoteResourceRejectedException("远程资源响应超过 25 MiB")
        }
        val output = ByteArrayOutputStream(minOf(body.contentLength().coerceAtLeast(0), 64 * 1024L).toInt())
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        body.byteStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > RemoteResourcePolicy.MAX_RESPONSE_BYTES) {
                    throw RemoteResourceRejectedException("远程资源实际读取超过 25 MiB")
                }
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }

    /** 关闭共享连接池和调度器，由现有 ImageCache 资源分区统一调用。 */
    override fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        client.cache?.close()
    }
}
