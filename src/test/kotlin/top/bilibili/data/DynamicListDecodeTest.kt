package top.bilibili.data

import kotlinx.serialization.decodeFromString
import top.bilibili.utils.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

    /**
     * 已撤销的预约附加卡片可能只保留空壳字段，解码层必须接受 null 描述和按钮。
     */
    @Test
    fun `dynamic list payload should decode revoked reserve additional card`() {
        val payload = """
            {
              "has_more": false,
              "offset": "1220452787232440338",
              "update_baseline": "1220456704240517120",
              "update_num": "1",
              "items": [
                {
                  "type": "DYNAMIC_TYPE_DRAW",
                  "basic": {
                    "comment_id_str": "400122292",
                    "comment_type": 11,
                    "rid_str": "400122292"
                  },
                  "id_str": "1220456678521044999",
                  "modules": {
                    "module_author": {
                      "mid": 12890453,
                      "name": "author-reserve",
                      "face": "https://example.invalid/face.jpg",
                      "pub_ts": 1782998114
                    },
                    "module_dynamic": {
                      "additional": {
                        "type": "ADDITIONAL_TYPE_RESERVE",
                        "reserve": {
                          "title": "",
                          "desc1": null,
                          "desc2": null,
                          "desc3": null,
                          "premiere": null,
                          "badge_text": "",
                          "jump_url": "",
                          "button": null,
                          "rid": 0,
                          "reserve_total": 0,
                          "state": -1,
                          "stype": 0,
                          "up_mid": "0"
                        }
                      }
                    }
                  }
                }
              ]
            }
        """.trimIndent()

        val decoded = json.decodeFromString<DynamicList>(payload)
        val reserve = decoded.items.single().modules.moduleDynamic.additional?.reserve

        assertEquals(-1, reserve?.state)
        assertNull(reserve?.desc1)
        assertNull(reserve?.desc2)
        assertNull(reserve?.desc3)
        assertNull(reserve?.button)
    }
}
