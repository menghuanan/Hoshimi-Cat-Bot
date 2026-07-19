package top.bilibili.service

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class ResolveLinkServiceOrderingTest {
    @Test
    fun `matchingAllRegular should preserve message appearance order across link types`() = runBlocking {
        val content = buildString {
            append("先发直播 https://live.bilibili.com/1000 ")
            append("再发视频 https://www.bilibili.com/video/BV1xx411c7mD ")
            append("最后发专栏 https://www.bilibili.com/read/cv123")
        }

        val results = matchingAllRegular(content)

        assertEquals(
            listOf(LinkType.Live, LinkType.VideoLink, LinkType.Article),
            results.map { it.type }
        )
        assertEquals(listOf("1000", "BV1xx411c7mD", "123"), results.map { it.id })
    }

    @Test
    fun `matchingAllRegular should keep deduplicating repeated resolved targets`() = runBlocking {
        val content = buildString {
            append("https://www.bilibili.com/video/BV1xx411c7mD ")
            append("重复一次 https://www.bilibili.com/video/BV1xx411c7mD ")
            append("再来一次 BV1xx411c7mD")
        }

        val results = matchingAllRegular(content)

        assertEquals(1, results.size)
        assertEquals(LinkType.VideoLink, results.single().type)
        assertEquals("BV1xx411c7mD", results.single().id)
    }

    // 附件 CDN 的不透明参数即使包含 ss/md 字样，也不能被截取成番剧候选。
    @Test
    fun `matchingAllRegular should ignore pgc fragments embedded in attachment tokens`() = runBlocking {
        val content = buildString {
            append("[附件] 文件名:BV14FNd6EEZc-res.mp4 ")
            append("URL:https://multimedia.nt.qq.com.cn/download?")
            append("rkey=CAQSqAEznP4727YVI9WSf1ciG_G6L1ZXNR2SZss9AhfZvABCss1234XYZ ")
            append("extra=md12345678901")
        }

        val results = matchingAllRegular(content)

        assertEquals(listOf(LinkType.VideoLink), results.map { it.type })
        assertEquals(listOf("BV14FNd6EEZc"), results.map { it.id })
    }
}
