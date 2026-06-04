package top.bilibili.data

import kotlinx.serialization.decodeFromString
import top.bilibili.utils.json
import kotlin.test.Test
import kotlin.test.assertEquals

class DynamicListDecodeTest {
    /**
     * B 站动态接口会把关注状态返回为数字状态码，解码必须保留该值而不是按布尔解析失败。
     */
    @Test
    fun `dynamic list payload should decode numeric following state`() {
        val payload = """
            {
              "has_more": true,
              "offset": "1178471083936841728",
              "update_baseline": "1209615300547313664",
              "update_num": "0",
              "items": [
                {
                  "type": "DYNAMIC_TYPE_DRAW",
                  "basic": {
                    "comment_id_str": "1209170861682065411",
                    "comment_type": 17,
                    "rid_str": "1209170861682065411"
                  },
                  "id_str": "1209170861682065411",
                  "modules": {
                    "module_author": {
                      "mid": 67141,
                      "name": "author-one",
                      "face": "https://example.invalid/one.jpg",
                      "following": 1
                    },
                    "module_dynamic": {}
                  }
                },
                {
                  "type": "DYNAMIC_TYPE_DRAW",
                  "basic": {
                    "comment_id_str": "1209170861682065412",
                    "comment_type": 17,
                    "rid_str": "1209170861682065412"
                  },
                  "id_str": "1209170861682065412",
                  "modules": {
                    "module_author": {
                      "mid": 11280430,
                      "name": "author-two",
                      "face": "https://example.invalid/two.jpg",
                      "following": 2
                    },
                    "module_dynamic": {}
                  }
                }
              ]
            }
        """.trimIndent()

        val decoded = json.decodeFromString<DynamicList>(payload)

        assertEquals(1, decoded.items[0].modules.moduleAuthor.following as Any?)
        assertEquals(2, decoded.items[1].modules.moduleAuthor.following as Any?)
    }
}
