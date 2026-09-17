package top.bilibili.data

import kotlinx.serialization.decodeFromString
import top.bilibili.tasker.filterDynamicPollingItems
import top.bilibili.utils.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DynamicListDecodeTest {
    /**
     * 动态作者关注状态允许接口在布尔值、数字和 null 之间切换，解码结果统一为可选数字状态码。
     */
    @Test
    fun `dynamic author following should accept boolean numeric null and missing values`() {
        val values = listOf(
            "true" to 1,
            "false" to 0,
            "1" to 1,
            "0" to 0,
            "null" to null,
        )

        values.forEach { (rawValue, expected) ->
            val decoded = json.decodeFromString<ModuleAuthor>(moduleAuthorJson(rawValue))
            assertEquals(expected, decoded.following, "following=$rawValue")
        }

        val missing = json.decodeFromString<ModuleAuthor>(moduleAuthorJsonWithoutFollowing())
        assertNull(missing.following)

        listOf("2", "-1", "1.5", "\"1\"", "\"true\"", "\"unexpected\"", "{\"unexpected\":true}", "[]").forEach { rawValue ->
            val decoded = json.decodeFromString<ModuleAuthor>(moduleAuthorJson(rawValue))
            assertNull(decoded.following, "following=$rawValue")
        }
    }

    /**
     * 直播推荐动态应先完成兼容解码，再由动态轮询规则排除而不进入推送链路。
     */
    @Test
    fun `live recommendation dynamic decodes and remains excluded from dynamic polling`() {
        val decoded = json.decodeFromString<DynamicItem>(liveRecommendationItemJson())

        assertEquals(DynamicType.DYNAMIC_TYPE_LIVE_RCMD, decoded.type)
        assertEquals(1, decoded.modules.moduleAuthor.following)
        assertEquals(emptyList(), filterDynamicPollingItems(listOf(decoded)))
    }

    /**
     * B 站动态接口会把关注状态返回为 0/1 数字状态码，解码必须保留该值而不是按布尔解析失败。
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
                      "following": 0
                    },
                    "module_dynamic": {}
                  }
                }
              ]
            }
        """.trimIndent()

        val decoded = json.decodeFromString<DynamicList>(payload)

        assertEquals(1, decoded.items[0].modules.moduleAuthor.following as Any?)
        assertEquals(0, decoded.items[1].modules.moduleAuthor.following as Any?)
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

    /** 构造最小作者模块样本，复用同一字段组合验证各类关注状态。 */
    private fun moduleAuthorJson(following: String): String =
        """
        {
          "mid": 552488366,
          "name": "author",
          "face": "https://example.invalid/face.jpg",
          "following": $following
        }
        """.trimIndent()

    /** 构造缺失关注状态字段的作者模块，验证默认 null 兼容行为。 */
    private fun moduleAuthorJsonWithoutFollowing(): String =
        """
        {
          "mid": 552488366,
          "name": "author",
          "face": "https://example.invalid/face.jpg"
        }
        """.trimIndent()

    /** 构造结构完整的直播推荐动态，验证字段兼容后仍经过动态类型过滤。 */
    private fun liveRecommendationItemJson(): String =
        """
        {
          "type": "DYNAMIC_TYPE_LIVE_RCMD",
          "basic": {
            "comment_id_str": "1248990748986048521",
            "comment_type": 17,
            "rid_str": "1248990748986048521"
          },
          "id_str": "1248990748986048521",
          "modules": {
            "module_author": {
              "mid": 552488366,
              "name": "author",
              "face": "https://example.invalid/face.jpg",
              "pub_ts": "1700000000",
              "following": true
            },
            "module_dynamic": {
              "major": {
                "type": "MAJOR_TYPE_LIVE_RCMD",
                "live_rcmd": {
                  "content": "{\"type\":1,\"live_play_info\":{\"uid\":552488366,\"room_id\":10086,\"live_id\":\"live-id\",\"live_status\":1,\"title\":\"title\",\"cover\":\"https://example.invalid/live.jpg\",\"parent_area_name\":\"area\",\"parent_area_id\":1,\"area_name\":\"sub-area\",\"area_id\":2,\"link\":\"https://live.example.invalid/10086\",\"room_type\":0,\"live_screen_type\":0,\"live_start_time\":1700000000,\"play_type\":0,\"online\":1,\"room_paid_type\":0,\"watched_show\":{\"num\":1,\"text_small\":\"1\",\"text_large\":\"1\",\"icon\":\"\",\"icon_location\":\"\",\"icon_web\":\"\",\"switch\":false}}}",
                  "reserve_type": 0
                }
              }
            }
          }
        }
        """.trimIndent()
}
