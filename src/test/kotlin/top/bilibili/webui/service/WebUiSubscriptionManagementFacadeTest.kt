package top.bilibili.webui.service

import kotlinx.coroutines.runBlocking
import top.bilibili.AtAllType
import top.bilibili.Bangumi
import top.bilibili.BiliConfig
import top.bilibili.BiliData
import top.bilibili.DynamicFilter
import top.bilibili.DynamicFilterType
import top.bilibili.FilterMode
import top.bilibili.Group
import top.bilibili.RegularFilter
import top.bilibili.SubData
import top.bilibili.TemplatePolicy
import top.bilibili.TypeFilter
import top.bilibili.webui.model.WebUiSubscriptionCreateRequestDto
import top.bilibili.webui.model.WebUiSubscriptionFilterSaveRequestDto
import top.bilibili.webui.model.WebUiSubscriptionTemplateSaveRequestDto
import top.bilibili.webui.model.WebUiSubscriptionTargetSaveRequestDto
import top.bilibili.webui.model.WebUiSubscriptionUidSaveRequestDto
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    private val originalSubscriptionCardUpdatedAt = BiliData.subscriptionCardUpdatedAt.toMutableMap()
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
        BiliData.subscriptionCardUpdatedAt = originalSubscriptionCardUpdatedAt.toMutableMap()
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
            currentTimeMillisProvider = { 1779360000000L },
        )

        val missing = facade.createSubscription(WebUiSubscriptionCreateRequestDto(type = "dynamic", uid = "123"))
        val created = facade.createSubscription(
            WebUiSubscriptionCreateRequestDto(type = "dynamic", uid = "123", targetGroup = "10001"),
        )

        assertFalse(missing.success)
        assertEquals(listOf(123L to "onebot11:group:10001"), calls)
        assertTrue(created.success)
        assertEquals(1779360000000L, BiliData.subscriptionCardUpdatedAt["dynamic:123"])
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
            currentTimeMillisProvider = { 1779360001000L },
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
        assertEquals(1779360001000L, BiliData.subscriptionCardUpdatedAt["bangumi:ss12345"])
    }

    /**
     * ep 入口会在业务服务中解析到 season id，WebUI 卡片更新时间必须落到最终展示的番剧卡片 ID。
     */
    @Test
    fun `create bangumi subscription by episode should record resolved season card update time`() = runBlocking {
        val facade = WebUiSubscriptionManagementFacade(
            followPgcAction = { _, subject ->
                BiliData.bangumi[456L] = Bangumi(
                    title = "Bangumi A",
                    seasonId = 456L,
                    mediaId = 789L,
                    type = "bangumi",
                    contacts = mutableSetOf(subject),
                )
                "追番成功 [Bangumi A]"
            },
            saveDataAction = { true },
            currentTimeMillisProvider = { 1779360003000L },
        )

        val created = facade.createSubscription(
            WebUiSubscriptionCreateRequestDto(type = "bangumi", bangumiId = "ep12345", targetGroup = "10001"),
        )

        assertTrue(created.success)
        assertEquals(1779360003000L, BiliData.subscriptionCardUpdatedAt["bangumi:456"])
    }

    /**
     * 删除番剧卡片复用现有番剧取消订阅链路，只移除本地推送绑定，不扩展追番取消行为。
     */
    @Test
    fun `delete bangumi subscription should delegate to existing pgc delete flow`() = runBlocking {
        BiliData.apply {
            bangumi = mutableMapOf(
                456L to Bangumi(
                    title = "Bangumi A",
                    seasonId = 456L,
                    mediaId = 789L,
                    type = "bangumi",
                    color = "#334455",
                    contacts = mutableSetOf("onebot11:group:10001", "onebot11:group:10002"),
                ),
            )
            subscriptionCardUpdatedAt = mutableMapOf("bangumi:456" to 1234L)
        }
        val deletedBindings = mutableListOf<Pair<String, String>>()
        val facade = WebUiSubscriptionManagementFacade(
            deletePgcAction = { id, subject ->
                deletedBindings += id to subject
                BiliData.bangumi.getValue(456L).contacts.remove(subject)
                if (BiliData.bangumi.getValue(456L).contacts.isEmpty()) {
                    BiliData.bangumi.remove(456L)
                }
                "删除成功"
            },
            saveDataAction = { true },
        )

        val result = facade.deleteSubscription("bangumi:456")

        assertTrue(result.success)
        assertEquals(
            listOf("ss456" to "onebot11:group:10001", "ss456" to "onebot11:group:10002"),
            deletedBindings,
        )
        assertFalse(BiliData.bangumi.containsKey(456L))
        assertFalse(BiliData.subscriptionCardUpdatedAt.containsKey("bangumi:456"))
    }

    /**
     * 新增分组卡片时记录管理更新时间，避免尚无推送内容的卡片显示暂无更新。
     */
    @Test
    fun `create group subscription should record card management update time`() = runBlocking {
        val facade = WebUiSubscriptionManagementFacade(
            saveDataAction = { true },
            currentTimeMillisProvider = { 1779360002000L },
        )

        val created = facade.createSubscription(
            WebUiSubscriptionCreateRequestDto(type = "group", groupName = "team-a", targetGroup = "10001"),
        )

        assertTrue(created.success)
        assertEquals(1779360002000L, BiliData.subscriptionCardUpdatedAt["group:team-a"])
    }

    /**
     * 删除动态卡片必须委托完整退订动作，并由 WebUI 兜底清理过滤器、模板、@全体和主题色配置。
     */
    @Test
    fun `delete dynamic subscription should unsubscribe uid and remove attached payloads`() = runBlocking {
        BiliData.apply {
            dynamic = mutableMapOf(
                123L to SubData(
                    name = "Alice",
                    contacts = mutableSetOf("onebot11:group:10001"),
                    sourceRefs = mutableSetOf("direct:onebot11:group:10001"),
                ),
            )
            filter = mutableMapOf("onebot11:group:10001" to mutableMapOf(123L to DynamicFilter()))
            dynamicTemplatePolicyByScope = mutableMapOf(
                "onebot11:group:10001" to mutableMapOf(123L to TemplatePolicy(templates = mutableListOf("DyMsg"))),
            )
            liveTemplatePolicyByScope = mutableMapOf()
            liveCloseTemplatePolicyByScope = mutableMapOf(
                "onebot11:group:10001" to mutableMapOf(123L to TemplatePolicy(templates = mutableListOf("CloseMsg"))),
            )
            dynamicColorByUid = mutableMapOf("onebot11:group:10001" to mutableMapOf(123L to "#112233"))
            atAll = mutableMapOf("onebot11:group:10001" to mutableMapOf(123L to mutableSetOf(AtAllType.LIVE)))
            atAllCooldownUntil = mutableMapOf("onebot11:group:10001.123.LIVE" to 999L)
            subscriptionCardUpdatedAt = mutableMapOf("dynamic:123" to 1234L)
        }
        val removedUids = mutableListOf<Long>()
        val facade = WebUiSubscriptionManagementFacade(
            removeDynamicAction = { uid ->
                removedUids += uid
                BiliData.dynamic.remove(uid)
                "取消订阅 Alice 成功"
            },
            saveDataAction = { true },
        )

        val result = facade.deleteSubscription("dynamic:123")

        assertTrue(result.success)
        assertEquals(listOf(123L), removedUids)
        assertFalse(BiliData.dynamic.containsKey(123L))
        assertTrue(BiliData.filter.isEmpty())
        assertTrue(BiliData.dynamicTemplatePolicyByScope.isEmpty())
        assertTrue(BiliData.liveTemplatePolicyByScope.isEmpty())
        assertTrue(BiliData.liveCloseTemplatePolicyByScope.isEmpty())
        assertTrue(BiliData.dynamicColorByUid.isEmpty())
        assertTrue(BiliData.atAll.isEmpty())
        assertTrue(BiliData.atAllCooldownUntil.isEmpty())
        assertFalse(BiliData.subscriptionCardUpdatedAt.containsKey("dynamic:123"))
    }

    /**
     * 删除分组卡片需要对绑定 UID 执行完整退订，并清理分组本身及 groupRef 附属配置。
     */
    @Test
    fun `delete group subscription should unsubscribe group bound uids and remove payloads`() = runBlocking {
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
        val removedUids = mutableListOf<Long>()
        val facade = WebUiSubscriptionManagementFacade(
            removeDynamicAction = { uid ->
                removedUids += uid
                BiliData.dynamic.remove(uid)
                "取消订阅 Alice 成功"
            },
            saveDataAction = { true },
        )

        val result = facade.deleteSubscription("group:team-a")

        assertTrue(result.success)
        assertEquals(listOf(123L), removedUids)
        assertFalse(BiliData.group.containsKey("team-a"))
        assertFalse(BiliData.dynamic.containsKey(123L))
        assertTrue(BiliData.filter.isEmpty())
        assertTrue(BiliData.dynamicTemplatePolicyByScope.isEmpty())
        assertTrue(BiliData.liveTemplatePolicyByScope.isEmpty())
        assertTrue(BiliData.liveCloseTemplatePolicyByScope.isEmpty())
        assertTrue(BiliData.dynamicColorByUid.isEmpty())
        assertTrue(BiliData.atAll.isEmpty())
        assertTrue(BiliData.atAllCooldownUntil.isEmpty())
    }

    /**
     * 过滤器编辑页需要把同一个 UID 下不同群的类型和正则规则拆成单条记录，便于前端只删除选中的那一条。
     */
    @Test
    fun `filter editor should list save update and delete a single filter row`() = runBlocking {
        BiliData.apply {
            dynamic = mutableMapOf(
                123L to SubData(
                    name = "Alice",
                    contacts = mutableSetOf("onebot11:group:10001"),
                    sourceRefs = mutableSetOf("direct:onebot11:group:10001"),
                ),
            )
            filter = mutableMapOf(
                "onebot11:group:10001" to mutableMapOf(
                    123L to DynamicFilter(
                        typeSelect = TypeFilter(FilterMode.BLACK_LIST, mutableListOf(DynamicFilterType.FORWARD)),
                        regularSelect = RegularFilter(FilterMode.WHITE_LIST, mutableListOf("^hello")),
                    ),
                ),
            )
        }
        val removedUids = mutableListOf<Long>()
        val facade = WebUiSubscriptionManagementFacade(
            removeDynamicAction = { uid ->
                removedUids += uid
                "取消订阅 Alice 成功"
            },
            saveDataAction = { true },
        )

        val initial = facade.listSubscriptionFilters("dynamic:123")
        val regexKey = initial.filters.first { it.kind == "regex" }.key
        val updated = facade.saveSubscriptionFilter(
            "dynamic:123",
            WebUiSubscriptionFilterSaveRequestDto(
                key = regexKey,
                kind = "regex",
                mode = "black",
                content = "新内容.*",
                targetGroups = listOf("onebot11:group:10001"),
            ),
        )
        val deleted = facade.deleteSubscriptionFilter("dynamic:123", regexKey)

        assertEquals(listOf("t0", "r0"), initial.filters.map { it.prefix })
        assertEquals("标签过滤", initial.filters.first().label)
        assertTrue(updated.success)
        assertTrue(deleted.success)
        assertTrue(removedUids.isEmpty())
        assertEquals(listOf(DynamicFilterType.FORWARD), BiliData.filter.getValue("onebot11:group:10001").getValue(123L).typeSelect.list)
        assertTrue(BiliData.filter.getValue("onebot11:group:10001").getValue(123L).regularSelect.list.isEmpty())
    }

    /**
     * 分组卡片的过滤器数量按 UID 绑定全量统计，编辑页也要列出同一批底层过滤器避免卡片和弹窗数量不一致。
     */
    @Test
    fun `group filter editor should include every uid filter counted by the card`() = runBlocking {
        BiliData.apply {
            dynamic = mutableMapOf(
                3108865L to SubData(
                    name = "小莫寝不足",
                    contacts = mutableSetOf(
                        "onebot11:group:763174993",
                        "onebot11:group:826295005",
                        "onebot11:group:425939196",
                        "onebot11:group:586042989",
                    ),
                    sourceRefs = mutableSetOf(
                        "direct:onebot11:group:763174993",
                        "direct:onebot11:group:826295005",
                        "direct:onebot11:group:425939196",
                        "direct:onebot11:group:586042989",
                        "groupRef:CatHouse",
                    ),
                ),
            )
            group = mutableMapOf(
                "CatHouse" to Group(
                    name = "CatHouse",
                    creator = 1L,
                    contacts = mutableSetOf(
                        "onebot11:group:763174993",
                        "onebot11:group:826295005",
                        "onebot11:group:425939196",
                    ),
                ),
            )
            filter = mutableMapOf(
                "onebot11:group:763174993" to mutableMapOf(
                    3108865L to DynamicFilter(
                        typeSelect = TypeFilter(FilterMode.BLACK_LIST, mutableListOf(DynamicFilterType.FORWARD)),
                    ),
                ),
                "onebot11:group:826295005" to mutableMapOf(
                    3108865L to DynamicFilter(
                        typeSelect = TypeFilter(FilterMode.BLACK_LIST, mutableListOf(DynamicFilterType.FORWARD)),
                    ),
                ),
                "onebot11:group:586042989" to mutableMapOf(
                    3108865L to DynamicFilter(
                        typeSelect = TypeFilter(FilterMode.BLACK_LIST, mutableListOf(DynamicFilterType.FORWARD)),
                    ),
                ),
            )
        }
        val facade = WebUiSubscriptionManagementFacade(saveDataAction = { true })

        val filters = facade.listSubscriptionFilters("group:CatHouse")

        assertEquals(3, filters.filters.size)
        assertEquals(
            listOf("onebot11:group:586042989", "onebot11:group:763174993", "onebot11:group:826295005"),
            filters.filters.map { it.scope },
        )
    }

    /**
     * 模板编辑页同时维护模板正文和 UID 策略，随机开关必须写入底层 TemplatePolicy。
     */
    @Test
    fun `template editor should upsert template content bind policy and toggle random mode`() = runBlocking {
        val runtimeConfig = BiliConfig()
        BiliData.apply {
            dynamic = mutableMapOf(
                123L to SubData(
                    name = "Alice",
                    contacts = mutableSetOf("onebot11:group:10001"),
                    sourceRefs = mutableSetOf("direct:onebot11:group:10001"),
                ),
            )
            dynamicTemplatePolicyByScope = mutableMapOf(
                "onebot11:group:10001" to mutableMapOf(123L to TemplatePolicy(templates = mutableListOf("OneMsg"))),
            )
        }
        var savedConfig = false
        var savedData = false
        val removedUids = mutableListOf<Long>()
        val facade = WebUiSubscriptionManagementFacade(
            configProvider = { runtimeConfig },
            saveConfigAction = {
                savedConfig = true
                true
            },
            saveDataAction = {
                savedData = true
                true
            },
            removeDynamicAction = { uid ->
                removedUids += uid
                "取消订阅 Alice 成功"
            },
        )

        val initial = facade.listSubscriptionTemplates("dynamic:123")
        val saved = facade.saveSubscriptionTemplate(
            "dynamic:123",
            WebUiSubscriptionTemplateSaveRequestDto(
                key = "",
                type = "dynamic",
                name = "WebDy",
                content = "{name}\n{link}",
                targetGroups = listOf("onebot11:group:10001"),
            ),
        )
        val random = facade.setSubscriptionTemplateRandom("dynamic:123", true)

        assertEquals(listOf("OneMsg"), initial.templates.map { it.name })
        assertTrue(saved.success)
        assertTrue(random.success)
        assertEquals("{name}\n{link}", runtimeConfig.templateConfig.dynamicPush["WebDy"])
        assertEquals(listOf("OneMsg", "WebDy"), BiliData.dynamicTemplatePolicyByScope.getValue("onebot11:group:10001").getValue(123L).templates)
        assertTrue(BiliData.dynamicTemplatePolicyByScope.getValue("onebot11:group:10001").getValue(123L).randomEnabled)
        assertTrue(savedConfig)
        assertTrue(savedData)
        assertTrue(removedUids.isEmpty())
    }

    /**
     * 动态订阅新增过滤器时必须按前端选择的目标群聊限定写入范围，不能默认扩散到全部推送群。
     */
    @Test
    fun `dynamic filter editor should save only selected target groups`() = runBlocking {
        BiliData.apply {
            dynamic = mutableMapOf(
                123L to SubData(
                    name = "Alice",
                    contacts = mutableSetOf("onebot11:group:10001", "onebot11:group:10002"),
                    sourceRefs = mutableSetOf("direct:onebot11:group:10001", "direct:onebot11:group:10002"),
                ),
            )
            filter = mutableMapOf()
        }
        val facade = WebUiSubscriptionManagementFacade(saveDataAction = { true })

        val missingTarget = facade.saveSubscriptionFilter(
            "dynamic:123",
            WebUiSubscriptionFilterSaveRequestDto(
                key = "",
                kind = "regex",
                mode = "black",
                content = "广告",
                targetGroups = emptyList(),
            ),
        )
        val saved = facade.saveSubscriptionFilter(
            "dynamic:123",
            WebUiSubscriptionFilterSaveRequestDto(
                key = "",
                kind = "regex",
                mode = "black",
                content = "广告",
                targetGroups = listOf("onebot11:group:10001"),
            ),
        )

        assertFalse(missingTarget.success)
        assertTrue(saved.success)
        assertEquals(listOf("广告"), BiliData.filter.getValue("onebot11:group:10001").getValue(123L).regularSelect.list)
        assertFalse(BiliData.filter.containsKey("onebot11:group:10002"))
    }

    /**
     * 动态订阅新增模板策略时只绑定到选中的群聊，模板正文仍写入全局模板配置。
     */
    @Test
    fun `dynamic template editor should save only selected target groups`() = runBlocking {
        val runtimeConfig = BiliConfig()
        BiliData.apply {
            dynamic = mutableMapOf(
                123L to SubData(
                    name = "Alice",
                    contacts = mutableSetOf("onebot11:group:10001", "onebot11:group:10002"),
                    sourceRefs = mutableSetOf("direct:onebot11:group:10001", "direct:onebot11:group:10002"),
                ),
            )
            dynamicTemplatePolicyByScope = mutableMapOf()
        }
        val facade = WebUiSubscriptionManagementFacade(
            configProvider = { runtimeConfig },
            saveConfigAction = { true },
            saveDataAction = { true },
        )

        val missingContent = facade.saveSubscriptionTemplate(
            "dynamic:123",
            WebUiSubscriptionTemplateSaveRequestDto(
                key = "",
                type = "dynamic",
                name = "WebDy",
                content = "",
                targetGroups = listOf("onebot11:group:10001"),
            ),
        )
        val missingTarget = facade.saveSubscriptionTemplate(
            "dynamic:123",
            WebUiSubscriptionTemplateSaveRequestDto(
                key = "",
                type = "dynamic",
                name = "WebDy",
                content = "{name}",
                targetGroups = emptyList(),
            ),
        )
        val saved = facade.saveSubscriptionTemplate(
            "dynamic:123",
            WebUiSubscriptionTemplateSaveRequestDto(
                key = "",
                type = "dynamic",
                name = "WebDy",
                content = "{name}",
                targetGroups = listOf("onebot11:group:10001"),
            ),
        )

        assertFalse(missingContent.success)
        assertFalse(missingTarget.success)
        assertTrue(saved.success)
        assertEquals("{name}", runtimeConfig.templateConfig.dynamicPush["WebDy"])
        assertEquals(listOf("WebDy"), BiliData.dynamicTemplatePolicyByScope.getValue("onebot11:group:10001").getValue(123L).templates)
        assertFalse(BiliData.dynamicTemplatePolicyByScope.containsKey("onebot11:group:10002"))
    }

    /**
     * @全体和主题色编辑都按当前订阅的推送群展开，删除时只移除所选类型或所选 UID 颜色。
     */
    @Test
    fun `atall and theme editors should mutate only selected subscription payload`() = runBlocking {
        BiliData.apply {
            dynamic = mutableMapOf(
                123L to SubData(
                    name = "Alice",
                    contacts = mutableSetOf("onebot11:group:10001", "onebot11:group:10002"),
                    sourceRefs = mutableSetOf("direct:onebot11:group:10001", "direct:onebot11:group:10002"),
                ),
                456L to SubData(
                    name = "Bob",
                    contacts = mutableSetOf("onebot11:group:10001"),
                    sourceRefs = mutableSetOf("direct:onebot11:group:10001"),
                ),
            )
            atAll = mutableMapOf(
                "onebot11:group:10001" to mutableMapOf(
                    123L to mutableSetOf(AtAllType.LIVE),
                    456L to mutableSetOf(AtAllType.DYNAMIC),
                ),
            )
            dynamicColorByUid = mutableMapOf(
                "onebot11:group:10001" to mutableMapOf(123L to "#112233", 456L to "#445566"),
            )
        }
        val removedUids = mutableListOf<Long>()
        val facade = WebUiSubscriptionManagementFacade(
            removeDynamicAction = { uid ->
                removedUids += uid
                "取消订阅 Alice 成功"
            },
            saveDataAction = { true },
        )

        val atAllRows = facade.listSubscriptionAtAll("dynamic:123")
        val savedAtAll = facade.saveSubscriptionAtAll("dynamic:123", "全部动态", listOf("onebot11:group:10001"))
        val deletedAtAll = facade.deleteSubscriptionAtAll("dynamic:123", atAllRows.items.first().key)
        val theme = facade.saveSubscriptionTheme("dynamic:123", "#ABCDEF", listOf("onebot11:group:10001"))

        assertEquals("直播 10001", atAllRows.items.first().summary)
        assertTrue(savedAtAll.success)
        assertTrue(deletedAtAll.success)
        assertTrue(theme.success)
        assertFalse(BiliData.atAll.getValue("onebot11:group:10001").getValue(123L).contains(AtAllType.LIVE))
        assertTrue(BiliData.atAll.getValue("onebot11:group:10001").getValue(456L).contains(AtAllType.DYNAMIC))
        assertEquals("#ABCDEF", BiliData.dynamicColorByUid.getValue("onebot11:group:10001").getValue(123L))
        assertEquals("#445566", BiliData.dynamicColorByUid.getValue("onebot11:group:10001").getValue(456L))
        assertTrue(removedUids.isEmpty())
    }

    /**
     * 空主题色表示恢复默认色：动态只清除选中群聊的当前 UID 覆盖，番剧清空自身颜色字段。
     */
    @Test
    fun `theme editor should clear saved color overrides when blank color is submitted`() = runBlocking {
        BiliData.apply {
            dynamic = mutableMapOf(
                123L to SubData(
                    name = "Alice",
                    contacts = mutableSetOf("onebot11:group:10001"),
                    sourceRefs = mutableSetOf("direct:onebot11:group:10001"),
                ),
                456L to SubData(
                    name = "Bob",
                    contacts = mutableSetOf("onebot11:group:10001"),
                    sourceRefs = mutableSetOf("direct:onebot11:group:10001"),
                ),
            )
            bangumi = mutableMapOf(
                789L to Bangumi(
                    title = "Bangumi A",
                    seasonId = 789L,
                    mediaId = 1000L,
                    type = "bangumi",
                    color = "#334455",
                    contacts = mutableSetOf("onebot11:group:10001"),
                ),
            )
            dynamicColorByUid = mutableMapOf(
                "onebot11:group:10001" to mutableMapOf(123L to "#112233", 456L to "#445566"),
            )
        }
        val facade = WebUiSubscriptionManagementFacade(saveDataAction = { true })

        val clearedDynamic = facade.saveSubscriptionTheme("dynamic:123", " ", listOf("onebot11:group:10001"))
        val clearedBangumi = facade.saveSubscriptionTheme("bangumi:789", "")

        assertTrue(clearedDynamic.success)
        assertTrue(clearedBangumi.success)
        assertFalse(BiliData.dynamicColorByUid.getValue("onebot11:group:10001").containsKey(123L))
        assertEquals("#445566", BiliData.dynamicColorByUid.getValue("onebot11:group:10001").getValue(456L))
        assertNull(BiliData.bangumi.getValue(789L).color)
    }

    /**
     * 单 UP 主题色按所选群聊写入；空颜色空选择是无写入 no-op，空颜色有选择则只恢复所选群聊默认。
     */
    @Test
    fun `dynamic theme editor should apply colors only to selected target groups`() = runBlocking {
        BiliData.apply {
            dynamic = mutableMapOf(
                123L to SubData(
                    name = "Alice",
                    contacts = mutableSetOf("onebot11:group:10001", "onebot11:group:10002"),
                    sourceRefs = mutableSetOf("direct:onebot11:group:10001", "direct:onebot11:group:10002"),
                ),
                456L to SubData(
                    name = "Bob",
                    contacts = mutableSetOf("onebot11:group:10001"),
                    sourceRefs = mutableSetOf("direct:onebot11:group:10001"),
                ),
            )
            dynamicColorByUid = mutableMapOf(
                "onebot11:group:10001" to mutableMapOf(123L to "#112233", 456L to "#445566"),
                "onebot11:group:10002" to mutableMapOf(123L to "#556677"),
            )
        }
        var saveCalls = 0
        val facade = WebUiSubscriptionManagementFacade(saveDataAction = {
            saveCalls += 1
            true
        })

        val missingTarget = facade.saveSubscriptionTheme("dynamic:123", "#ABCDEF", emptyList())
        val noopBlank = facade.saveSubscriptionTheme("dynamic:123", " ", emptyList())
        val savedSelected = facade.saveSubscriptionTheme("dynamic:123", "#ABCDEF", listOf("onebot11:group:10002"))
        val clearedSelected = facade.saveSubscriptionTheme("dynamic:123", "", listOf("onebot11:group:10002"))

        assertFalse(missingTarget.success)
        assertTrue(noopBlank.success)
        assertTrue(savedSelected.success)
        assertTrue(clearedSelected.success)
        assertEquals(2, saveCalls)
        assertEquals("#112233", BiliData.dynamicColorByUid.getValue("onebot11:group:10001").getValue(123L))
        assertEquals("#445566", BiliData.dynamicColorByUid.getValue("onebot11:group:10001").getValue(456L))
        assertFalse(BiliData.dynamicColorByUid.containsKey("onebot11:group:10002"))
    }

    /**
     * 分组主题色保持分组级批量语义，不要求像单 UP 一样选择目标群聊。
     */
    @Test
    fun `group theme editor should keep applying colors to every group target`() = runBlocking {
        BiliData.apply {
            dynamic = mutableMapOf(
                123L to SubData(
                    name = "Alice",
                    contacts = mutableSetOf("onebot11:group:10001", "onebot11:group:10002"),
                    sourceRefs = mutableSetOf("groupRef:team-a"),
                ),
                456L to SubData(
                    name = "Bob",
                    contacts = mutableSetOf("onebot11:group:10001", "onebot11:group:10002"),
                    sourceRefs = mutableSetOf("groupRef:team-a"),
                ),
            )
            group = mutableMapOf(
                "team-a" to Group(
                    name = "team-a",
                    creator = 1L,
                    contacts = mutableSetOf("onebot11:group:10001", "onebot11:group:10002"),
                ),
            )
        }
        val facade = WebUiSubscriptionManagementFacade(saveDataAction = { true })

        val saved = facade.saveSubscriptionTheme("group:team-a", "#ABCDEF")

        assertTrue(saved.success)
        assertEquals("#ABCDEF", BiliData.dynamicColorByUid.getValue("onebot11:group:10001").getValue(123L))
        assertEquals("#ABCDEF", BiliData.dynamicColorByUid.getValue("onebot11:group:10001").getValue(456L))
        assertEquals("#ABCDEF", BiliData.dynamicColorByUid.getValue("onebot11:group:10002").getValue(123L))
        assertEquals("#ABCDEF", BiliData.dynamicColorByUid.getValue("onebot11:group:10002").getValue(456L))

        val cleared = facade.saveSubscriptionTheme("group:team-a", "")

        assertTrue(cleared.success)
        assertTrue(BiliData.dynamicColorByUid.isEmpty())
    }

    /**
     * @全体编辑页的目标群聊是多选必填；保存已有类型时要用新选择替换旧群聊分布。
     */
    @Test
    fun `atall editor should require target groups and replace same type groups`() = runBlocking {
        BiliData.apply {
            dynamic = mutableMapOf(
                123L to SubData(
                    name = "Alice",
                    contacts = mutableSetOf("onebot11:group:10001", "onebot11:group:10002"),
                    sourceRefs = mutableSetOf("direct:onebot11:group:10001", "direct:onebot11:group:10002"),
                ),
            )
            atAll = mutableMapOf(
                "onebot11:group:10001" to mutableMapOf(123L to mutableSetOf(AtAllType.LIVE)),
                "onebot11:group:10002" to mutableMapOf(123L to mutableSetOf(AtAllType.LIVE)),
            )
        }
        val facade = WebUiSubscriptionManagementFacade(saveDataAction = { true })

        val missingTarget = facade.saveSubscriptionAtAll("dynamic:123", "直播", emptyList())
        val edited = facade.saveSubscriptionAtAll("dynamic:123", "直播", listOf("onebot11:group:10001"))

        assertFalse(missingTarget.success)
        assertTrue(edited.success)
        assertTrue(BiliData.atAll.getValue("onebot11:group:10001").getValue(123L).contains(AtAllType.LIVE))
        assertFalse(BiliData.atAll.containsKey("onebot11:group:10002"))
    }

    /**
     * 推送群聊编辑器要求输入正整数，并在删除分组群聊时同步影响分组内所有 UID 的实际推送目标和该群附属配置。
     */
    @Test
    fun `target editor should validate positive group ids and clean group target payloads`() = runBlocking {
        BiliData.apply {
            dynamic = mutableMapOf(
                123L to SubData(
                    name = "Alice",
                    contacts = mutableSetOf("onebot11:group:10001", "onebot11:group:10002"),
                    sourceRefs = mutableSetOf("groupRef:team-a"),
                ),
                456L to SubData(
                    name = "Bob",
                    contacts = mutableSetOf("onebot11:group:10001", "onebot11:group:10002"),
                    sourceRefs = mutableSetOf("groupRef:team-a"),
                ),
            )
            group = mutableMapOf(
                "team-a" to Group(
                    name = "team-a",
                    creator = 1L,
                    contacts = mutableSetOf("onebot11:group:10001", "onebot11:group:10002"),
                ),
            )
            filter = mutableMapOf(
                "onebot11:group:10002" to mutableMapOf(123L to DynamicFilter(), 456L to DynamicFilter()),
            )
            dynamicColorByUid = mutableMapOf(
                "onebot11:group:10002" to mutableMapOf(123L to "#112233", 456L to "#445566"),
            )
            atAll = mutableMapOf(
                "onebot11:group:10002" to mutableMapOf(123L to mutableSetOf(AtAllType.LIVE), 456L to mutableSetOf(AtAllType.DYNAMIC)),
            )
        }
        val facade = WebUiSubscriptionManagementFacade(saveDataAction = { true })

        val invalid = facade.saveSubscriptionTarget(
            "group:team-a",
            WebUiSubscriptionTargetSaveRequestDto(targetGroup = "0"),
        )
        val deleted = facade.deleteSubscriptionTarget("group:team-a", "onebot11:group:10002")

        assertFalse(invalid.success)
        assertTrue(deleted.success)
        assertEquals(setOf("onebot11:group:10001"), BiliData.group.getValue("team-a").contacts)
        assertEquals(setOf("onebot11:group:10001"), BiliData.dynamic.getValue(123L).contacts)
        assertEquals(setOf("onebot11:group:10001"), BiliData.dynamic.getValue(456L).contacts)
        assertFalse(BiliData.filter.containsKey("onebot11:group:10002"))
        assertFalse(BiliData.dynamicColorByUid.containsKey("onebot11:group:10002"))
        assertFalse(BiliData.atAll.containsKey("onebot11:group:10002"))
    }

    /**
     * 分组 UID 删除必须走 WebUI 的 UID 级取消关注链路，确保后端执行真实退订而不是只移除 groupRef。
     */
    @Test
    fun `group uid editor should call backend unsubscribe when deleting uid`() = runBlocking {
        BiliData.apply {
            dynamic = mutableMapOf(
                123L to SubData(
                    name = "Alice",
                    contacts = mutableSetOf("onebot11:group:10001"),
                    sourceRefs = mutableSetOf("groupRef:team-a"),
                ),
            )
            group = mutableMapOf(
                "team-a" to Group(
                    name = "team-a",
                    creator = 1L,
                    contacts = mutableSetOf("onebot11:group:10001"),
                ),
            )
        }
        val removedUids = mutableListOf<Long>()
        val facade = WebUiSubscriptionManagementFacade(
            removeDynamicAction = { uid ->
                removedUids += uid
                "取消订阅 Alice 成功"
            },
            saveDataAction = { true },
        )

        val listed = facade.listSubscriptionUids("group:team-a")
        val invalid = facade.saveSubscriptionUid("group:team-a", WebUiSubscriptionUidSaveRequestDto(uid = "-1"))
        val deleted = facade.deleteSubscriptionUid("group:team-a", "123")

        assertEquals(listOf("123"), listed.items.map { it.key })
        assertFalse(invalid.success)
        assertTrue(deleted.success)
        assertEquals(listOf(123L), removedUids)
        assertFalse(BiliData.dynamic.containsKey(123L))
    }
}
