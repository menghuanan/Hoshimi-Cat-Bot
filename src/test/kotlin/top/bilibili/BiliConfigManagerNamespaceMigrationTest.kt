package top.bilibili

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.encodeToString
import top.bilibili.service.TemplateRuntimeCoordinator
import top.bilibili.service.TemplateSelectionService
import top.bilibili.service.TemplateService
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BiliConfigManagerNamespaceMigrationTest {
    @AfterTest
    fun cleanup() {
        BiliData.dataVersion = 0
        BiliData.dynamic.clear()
        BiliData.filter.clear()
        BiliData.dynamicPushTemplate.clear()
        BiliData.livePushTemplate.clear()
        BiliData.liveCloseTemplate.clear()
        BiliData.dynamicPushTemplateByUid.clear()
        BiliData.livePushTemplateByUid.clear()
        BiliData.liveCloseTemplateByUid.clear()
        BiliData.dynamicTemplatePolicyByScope.clear()
        BiliData.liveTemplatePolicyByScope.clear()
        BiliData.liveCloseTemplatePolicyByScope.clear()
        BiliData.dynamicColorByUid.clear()
        BiliData.atAll.clear()
        BiliData.group.clear()
        BiliData.bangumi.clear()
        BiliData.linkParseBlacklist.clear()
    }

    @Test
    fun `migrateDataIfNeeded 应将旧联系人目标重写为 onebot11 命名空间`() {
        val legacySubject = "group:10001"
        val migratedSubject = "onebot11:group:10001"
        val customSubject = "custom:manual"
        val uid = 123456L

        BiliData.dataVersion = 2
        BiliData.dynamic[uid] = SubData(
            name = "测试UP",
            contacts = mutableSetOf(legacySubject, customSubject),
            sourceRefs = mutableSetOf("direct:$legacySubject", "groupRef:ops"),
        )
        BiliData.filter[legacySubject] = mutableMapOf(uid to DynamicFilter())
        BiliData.filter[customSubject] = mutableMapOf(uid to DynamicFilter())
        BiliData.dynamicPushTemplate["OneMsg"] = mutableSetOf(legacySubject, customSubject)
        BiliData.livePushTemplate["DrawOnly"] = mutableSetOf(legacySubject)
        BiliData.liveCloseTemplate["SimpleMsg"] = mutableSetOf(legacySubject)
        BiliData.dynamicPushTemplateByUid[legacySubject] = mutableMapOf(uid to "OneMsg")
        BiliData.livePushTemplateByUid[legacySubject] = mutableMapOf(uid to "DrawOnly")
        BiliData.liveCloseTemplateByUid[legacySubject] = mutableMapOf(uid to "SimpleMsg")
        BiliData.dynamicColorByUid[legacySubject] = mutableMapOf(uid to "#d3edfa")
        BiliData.atAll[legacySubject] = mutableMapOf(uid to mutableSetOf(AtAllType.LIVE))
        BiliData.group["ops"] = Group(name = "ops", creator = 1L, contacts = mutableSetOf(legacySubject, customSubject))
        BiliData.bangumi[404L] = Bangumi(
            title = "测试番剧",
            seasonId = 404L,
            mediaId = 505L,
            type = "番剧",
            contacts = mutableSetOf(legacySubject, customSubject),
        )

        val changed = migrateViaReflection()

        assertTrue(changed, "存在旧联系人目标时，迁移应报告已变更")
        assertTrue(BiliData.dataVersion >= 4, "命名空间迁移后数据版本应递增")
        assertEquals(setOf(migratedSubject, customSubject), BiliData.dynamic[uid]?.contacts?.toSet())
        assertEquals(setOf("direct:$migratedSubject", "groupRef:ops"), BiliData.dynamic[uid]?.sourceRefs?.toSet())
        assertTrue(BiliData.filter.containsKey(migratedSubject))
        assertFalse(BiliData.filter.containsKey(legacySubject))
        assertTrue(customSubject in BiliData.filter.keys, "自定义键应被保留")
        assertTrue(BiliData.dynamicPushTemplate.isEmpty())
        assertTrue(BiliData.dynamicPushTemplateByUid.isEmpty())
        assertEquals("#d3edfa", BiliData.dynamicColorByUid[migratedSubject]?.get(uid))
        assertTrue(BiliData.atAll.containsKey(migratedSubject))
        assertEquals(setOf(migratedSubject, customSubject), BiliData.group["ops"]?.contacts?.toSet())
        assertEquals(setOf(migratedSubject, customSubject), BiliData.bangumi[404L]?.contacts?.toSet())
    }

    /**
     * 旧订阅文件只保留 contacts 时，迁移应根据现有分组关系重建 groupRef 绑定。
     * 这样升级后的模板和配置查询才能继续命中原来的作用域，而不是要求用户手动重绑。
     */
    @Test
    fun `migrate should restore legacy groupRef bindings from expanded contacts`() {
        val groupSubject = "onebot11:group:10001"
        val directSubject = "onebot11:private:20001"
        val uid = 123456L

        BiliData.dataVersion = 1
        BiliData.dynamic[uid] = SubData(
            name = "测试UP",
            contacts = mutableSetOf(groupSubject, directSubject),
        )
        BiliData.group["ops"] = Group(
            name = "ops",
            creator = 1L,
            contacts = mutableSetOf(groupSubject),
        )

        val changed = migrateViaReflection()
        val result = TemplateService.addTemplate("d", "OneMsg", groupSubject, uid, "ops")

        assertTrue(changed, "旧 contacts 迁移应重建来源引用")
        assertEquals(setOf("groupRef:ops", "direct:$directSubject"), BiliData.dynamic[uid]?.sourceRefs?.toSet())
        assertTrue(result.contains("成功"))
        assertEquals(listOf("OneMsg"), BiliData.dynamicTemplatePolicyByScope["groupRef:ops"]?.get(uid)?.templates?.toList())
    }

    @Test
    fun `migrate should convert legacy template bindings into template policy by scope`() {
        val subject = "onebot11:group:10001"
        val uid = 123456L

        BiliData.dataVersion = 3
        BiliData.dynamicPushTemplate["OneMsg"] = mutableSetOf(subject)
        BiliData.dynamicPushTemplateByUid[subject] = mutableMapOf(uid to "TwoMsg")
        BiliData.dynamic[uid] = SubData(
            name = "测试UP",
            contacts = mutableSetOf(subject),
            sourceRefs = mutableSetOf("direct:$subject"),
        )

        migrateViaReflection()

        val contactScope = "contact:$subject"
        assertEquals("TwoMsg", BiliData.dynamicTemplatePolicyByScope[contactScope]?.get(uid)?.templates?.firstOrNull())
        assertFalse(BiliData.dynamicTemplatePolicyByScope[contactScope]?.get(uid)?.randomEnabled == true)
        assertTrue(BiliData.dynamicPushTemplate.isEmpty())
        assertTrue(BiliData.dynamicPushTemplateByUid.isEmpty())
        assertEquals(4, BiliData.dataVersion)
    }

    @Test
    fun `load data should upgrade v3 legacy template fields into v4 policy only schema`() {
        val subject = "onebot11:group:10001"
        val uid = 123456L
        val yamlText = """
            dataVersion: 3
            dynamic:
              $uid:
                name: 测试UP
                contacts:
                  - $subject
                sourceRefs:
                  - direct:$subject
            dynamicPushTemplate:
              OneMsg:
                - $subject
            dynamicPushTemplateByUid:
              $subject:
                $uid: TwoMsg
        """.trimIndent()

        val changed = loadFromYamlViaReflection(yamlText)
        val serialized = Yaml(
            configuration = Yaml.default.configuration.copy(
                strictMode = false,
            ),
        ).encodeToString(BiliDataWrapper.from(BiliData))

        assertTrue(changed)
        assertEquals(4, BiliData.dataVersion)
        assertEquals("TwoMsg", BiliData.dynamicTemplatePolicyByScope["contact:$subject"]?.get(uid)?.templates?.firstOrNull())
        assertTrue(BiliData.dynamicPushTemplate.isEmpty())
        assertTrue(BiliData.dynamicPushTemplateByUid.isEmpty())
        assertFalse(serialized.contains("dynamicPushTemplate:"))
        assertFalse(serialized.contains("dynamicPushTemplateByUid:"))
    }

    @Test
    fun `load data should clear runtime caches when applying current wrapper schema`() {
        BiliData.dynamicTemplatePolicyByScope["contact:onebot11:group:10001"] = mutableMapOf(
            123456L to TemplatePolicy(
                templates = mutableListOf("OneMsg"),
                randomEnabled = false,
            ),
        )
        TemplateSelectionService.selectTemplate(
            type = "dynamic",
            uid = 123456L,
            directScope = "contact:onebot11:group:10001",
            groupScopes = emptyList(),
            messageIdentity = "dynamic:reload-current-wrapper",
        )

        val changed = loadFromYamlViaReflection("dataVersion: 4")

        assertFalse(changed)
        assertTrue(TemplateRuntimeCoordinator.snapshotLastTemplateState().isEmpty())
        assertTrue(TemplateRuntimeCoordinator.snapshotBatchTemplateState().isEmpty())
    }

    private fun migrateViaReflection(): Boolean {
        val method = BiliConfigManager::class.java.getDeclaredMethod("migrateDataIfNeeded", BiliData::class.java)
        method.isAccessible = true
        return method.invoke(BiliConfigManager, BiliData) as Boolean
    }

    private fun loadFromYamlViaReflection(content: String): Boolean {
        val method = BiliConfigManager::class.java.getDeclaredMethod("loadDataFromContent", String::class.java, BiliData::class.java)
        method.isAccessible = true
        return method.invoke(BiliConfigManager, content, BiliData) as Boolean
    }
}
