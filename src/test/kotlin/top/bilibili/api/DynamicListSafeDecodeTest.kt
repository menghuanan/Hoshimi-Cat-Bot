package top.bilibili.api

import top.bilibili.utils.json
import kotlin.test.Test
import kotlin.test.assertEquals

class DynamicListSafeDecodeTest {
    /**
     * 动态 feed 中的单条坏数据只能丢弃当前 item，不能阻断同页其它动态解析。
     */
    @Test
    fun `dynamic list safe decoder should skip invalid item and keep valid item`() {
        val payload = json.parseToJsonElement(
            """
                {
                  "has_more": true,
                  "offset": "next-offset",
                  "update_baseline": "baseline-id",
                  "update_num": "2",
                  "items": [
                    {
                      "type": "DYNAMIC_TYPE_DRAW",
                      "basic": null,
                      "id_str": "bad-item",
                      "modules": {}
                    },
                    {
                      "type": "DYNAMIC_TYPE_DRAW",
                      "basic": {
                        "comment_id_str": "good-item",
                        "comment_type": 11,
                        "rid_str": "good-item"
                      },
                      "id_str": "good-item",
                      "modules": {
                        "module_author": {
                          "mid": 10086,
                          "name": "author-good",
                          "face": "https://example.invalid/good.jpg"
                        },
                        "module_dynamic": {}
                      }
                    }
                  ]
                }
            """.trimIndent()
        )

        val decoded = payload.decodeDynamicListSkippingInvalidItems("unit-test")

        assertEquals(true, decoded.hasMore)
        assertEquals("next-offset", decoded.offset)
        assertEquals("baseline-id", decoded.updateBaseline)
        assertEquals(listOf("good-item"), decoded.items.map { it.did })
    }
}
