package top.bilibili.webui.service

import kotlinx.coroutines.runBlocking
import top.bilibili.AtAllType
import top.bilibili.BiliData
import top.bilibili.DynamicFilter
import top.bilibili.Group
import top.bilibili.SubData
import top.bilibili.TemplatePolicy
import top.bilibili.webui.model.WebUiSubscriptionCreateRequestDto
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebUiSubscriptionManagementFacadeTest {
    private val originalDynamic = BiliData.dynamic.toMutableMap()
    private val originalFilter = BiliData.filter.toMutableMap()
    private val originalDynamicTemplatePolicies = BiliData.dynamicTemplatePolicyByScope.toMutableMap()
    private val originalLiveTemplatePolicies = BiliData.liveTemplatePolicyByScope.toMutableMap()
    private val originalLiveCloseTemplatePolicies = BiliData.liveCloseTemplatePolicyByScope.toMutableMap()
    private val originalDynamicColorByUid = BiliData.dynamicColorByUid.toMutableMap()
    private val originalAtAll = BiliData.atAll.toMutableMap()
    private val originalAtAllCooldownUntil = BiliData.atAllCooldownUntil.toMutableMap()
    private val originalGroup = BiliData.group.toMutableMap()
    private val originalBangumi = BiliData.bangumi.toMutableMap()

    @AfterTest
    fun restoreBiliDataState() {
        BiliData.dynamic = originalDynamic.toMutableMap()
        BiliData.filter = originalFilter.toMutableMap()
        BiliData.dynamicTemplatePolicyByScope = originalDynamicTemplatePolicies.toMutableMap()
        BiliData.liveTemplatePolicyByScope = originalLiveTemplatePolicies.toMutableMap()
        BiliData.liveCloseTemplatePolicyByScope = originalLiveCloseTemplatePolicies.toMutableMap()
        BiliData.dynamicColorByUid = originalDynamicColorByUid.toMutableMap()
        BiliData.atAll = originalAtAll.toMutableMap()
        BiliData.atAllCooldownUntil = originalAtAllCooldownUntil.toMutableMap()
        BiliData.group = originalGroup.toMutableMap()
        BiliData.bangumi = originalBangumi.toMutableMap()
    }

    /**
     * 添加普通订阅必须同时具备 UID 和群号，并且只有底层关注链路返回成功时才向 WebUI 报告成功。
     */
    @Test
    fun `create dynamic subscription should require uid group and successful follow feedback`() = runBlocking {
        val calls = mutableListOf<Pair<Long, String>>()
        val facade = WebUiSubscriptionManagementFacade(
            addDynamicAction = { uid, subject ->
                calls += uid to subject
                "为 $subject 订阅 Alice 成功!"
            },
            saveDataAction = { true },
        )

        val missing = facade.createSubscription(WebUiSubscriptionCreateRequestDto(type = "dynamic", uid = "123"))
        val created = facade.createSubscription(
            WebUiSubscriptionCreateRequestDto(type = "dynamic", uid = "123", targetGroup = "10001"),
        )

        assertFalse(missing.success)
        assertEquals(listOf(123L to "onebot11:group:10001"), calls)
        assertTrue(created.success)
    }

    /**
     * 番剧订阅只接受 ep 或 ss 前缀，并要求番剧号和群组号同时填写后才调用追番链路。
     */
    @Test
    fun `create bangumi subscription should validate id prefix and target group`() = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()
        val facade = WebUiSubscriptionManagementFacade(
            followPgcAction = { id, subject ->
                calls += id to subject
                "追番成功 [Test]"
            },
            saveDataAction = { true },
        )

        val invalid = facade.createSubscription(
            WebUiSubscriptionCreateRequestDto(type = "bangumi", bangumiId = "md12345", targetGroup = "10001"),
        )
        val created = facade.createSubscription(
            WebUiSubscriptionCreateRequestDto(type = "bangumi", bangumiId = "ss12345", targetGroup = "10001"),
        )

        assertFalse(invalid.success)
        assertEquals(listOf("ss12345" to "onebot11:group:10001"), calls)
        assertTrue(created.success)
    }

    /**
     * 删除分组卡片需要清理分组本身及其绑定 UID 的过滤器、模板、@全体和主题色配置。
     */
    @Test
    fun `delete group subscription should remove group bound uid payloads`() = runBlocking {
        BiliData.apply {
            dynamic = mutableMapOf(
                123L to SubData(
                    name = "Alice",
                    contacts = mutableSetOf("onebot11:group:10001"),
                    sourceRefs = mutableSetOf("groupRef:team-a"),
                ),
            )
            filter = mutableMapOf("onebot11:group:10001" to mutableMapOf(123L to DynamicFilter()))
            dynamicTemplatePolicyByScope = mutableMapOf(
                "groupRef:team-a" to mutableMapOf(123L to TemplatePolicy(templates = mutableListOf("DyMsg"))),
            )
            liveTemplatePolicyByScope = mutableMapOf(
                "groupRef:team-a" to mutableMapOf(123L to TemplatePolicy(templates = mutableListOf("LiveMsg"))),
            )
            liveCloseTemplatePolicyByScope = mutableMapOf()
            dynamicColorByUid = mutableMapOf("groupRef:team-a" to mutableMapOf(123L to "#112233"))
            atAll = mutableMapOf("groupRef:team-a" to mutableMapOf(123L to mutableSetOf(AtAllType.LIVE)))
            atAllCooldownUntil = mutableMapOf("groupRef:team-a.123.LIVE" to 999L)
            group = mutableMapOf(
                "team-a" to Group(
                    name = "team-a",
                    creator = 1L,
                    contacts = mutableSetOf("onebot11:group:10001"),
                ),
            )
        }
        val facade = WebUiSubscriptionManagementFacade(saveDataAction = { true })

        val result = facade.deleteSubscription("group:team-a")

        assertTrue(result.success)
        assertFalse(BiliData.group.containsKey("team-a"))
        assertFalse(BiliData.dynamic.containsKey(123L))
        assertTrue(BiliData.filter.isEmpty())
        assertTrue(BiliData.dynamicTemplatePolicyByScope.isEmpty())
        assertTrue(BiliData.liveTemplatePolicyByScope.isEmpty())
        assertTrue(BiliData.dynamicColorByUid.isEmpty())
        assertTrue(BiliData.atAll.isEmpty())
        assertTrue(BiliData.atAllCooldownUntil.isEmpty())
    }
}
