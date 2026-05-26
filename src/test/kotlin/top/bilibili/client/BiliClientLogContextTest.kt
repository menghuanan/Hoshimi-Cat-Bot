package top.bilibili.client

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import top.bilibili.BiliConfig
import top.bilibili.BiliConfigManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BiliClientLogContextTest {
    @Test
    fun `retry log should include caller and api context`() {
        val trace = ApiRequestTrace(
            source = "DynamicCheckTasker.poll",
            api = "NEW_DYNAMIC",
            url = "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/all"
        )

        val message = buildRetryLogMessage(
            trace = trace,
            retryNumber = 1,
            maxAttempts = 2,
            clientIndex = 0,
            proxyEnabled = false,
            throwable = HttpRequestTimeoutException(
                "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/all",
                10_000,
                cause = null
            )
        )

        assertTrue(message.contains("任务=动态轮询"))
        assertTrue(message.contains("接口=动态列表"))
        assertTrue(message.contains("异常=请求超时"))
        assertTrue(message.contains("原因=10秒内未收到响应"))
        assertFalse(message.contains("url="))
        assertFalse(message.contains("clientIndex="))
        assertFalse(message.contains("proxyEnabled="))
    }

    /**
     * 首次可重试失败仍要驱动底层重试，但不应提前写出 API 失败日志。
     */
    @Test
    fun `use http client should hide first retry failure log`() {
        val logger = LoggerFactory.getLogger(BiliClient::class.java) as Logger
        val previousLevel = logger.level
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        // BiliClient 构造时读取运行期配置，测试中用默认配置填充即可覆盖日志门控行为。
        val configField = BiliConfigManager::class.java.getDeclaredField("config")
        configField.isAccessible = true
        configField.set(BiliConfigManager, BiliConfig())
        val client = BiliClient(ownerTag = "test-retry-log-gate")
        val trace = ApiRequestTrace(
            source = "DynamicCheckTasker.poll",
            api = "NEW_DYNAMIC",
            url = "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/all"
        )
        var attempts = 0

        logger.addAppender(appender)
        logger.level = Level.WARN

        try {
            assertFailsWith<HttpRequestTimeoutException> {
                runBlocking {
                    client.useHttpClient(trace) {
                        // 测试只验证 BiliClient 的失败日志门控，HTTP 客户端实例本身不参与网络请求。
                        attempts++
                        throw HttpRequestTimeoutException(trace.url, 10_000, cause = null)
                    }
                }
            }

            assertEquals(2, attempts)
            assertFalse(
                appender.list.any { event ->
                    event.level == Level.WARN && event.formattedMessage.contains("API请求失败")
                }
            )
            assertTrue(
                appender.list.any { event ->
                    event.level == Level.ERROR && event.formattedMessage.contains("API请求重试耗尽")
                }
            )
        } finally {
            client.close()
            logger.detachAppender(appender)
            logger.level = previousLevel
        }
    }

    @Test
    fun `retry exhausted log should include caller and api context`() {
        val trace = ApiRequestTrace(
            source = "LiveCheckTasker.followed-live-list",
            api = "LIVE_LIST",
            url = "https://api.live.bilibili.com/xlive/web-ucenter/v1/xfetter/GetWebList"
        )

        val message = buildRetryExhaustedLogMessage(
            trace = trace,
            attemptsUsed = 2,
            maxAttempts = 2,
            clientIndex = 1,
            proxyEnabled = true,
            throwable = HttpRequestTimeoutException(
                "https://api.live.bilibili.com/xlive/web-ucenter/v1/xfetter/GetWebList",
                10_000,
                cause = null
            )
        )

        assertTrue(message.contains("任务=直播轮询(关注列表)"))
        assertTrue(message.contains("接口=关注直播列表"))
        assertTrue(message.contains("重试=2/2"))
        assertTrue(message.contains("异常=请求超时"))
        assertTrue(message.contains("原因=10秒内未收到响应"))
        assertFalse(message.contains("url="))
        assertFalse(message.contains("clientIndex="))
        assertFalse(message.contains("proxyEnabled="))
    }
}
