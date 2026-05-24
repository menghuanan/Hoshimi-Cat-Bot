package top.bilibili

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.runBlocking
import top.bilibili.service.TemplateRuntimeCoordinator
import top.bilibili.service.TemplateSelectionService
import top.bilibili.service.TemplateService
import top.bilibili.service.AtAllService
import top.bilibili.data.LiveMessage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BiliConfigManagerNamespaceMigrationTest {
    private val configFile = Path.of("config", "BiliConfig.yml")

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
        BiliData.atAllCooldownUntil.clear()
        BiliData.group.clear()
        BiliData.bangumi.clear()
        BiliData.linkParseBlacklist.clear()
        deleteConfigBackups()
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

    @Test
    fun `load data should preserve atall cooldown state across wrapper reload`() = runBlocking {
        val subject = "onebot11:group:10001"
        val runtimeSubject = "group:10001"
        val uid = 123456L
        val now = 1_800_000_000_000L
        BiliData.dataVersion = 4
        BiliData.atAll[subject] = mutableMapOf(uid to mutableSetOf(AtAllType.LIVE))
        AtAllService.recordAtAllSuccess(runtimeSubject, uid, liveMessage(uid), now)

        val serialized = Yaml(
            configuration = Yaml.default.configuration.copy(
                strictMode = false,
            ),
        ).encodeToString(BiliDataWrapper.from(BiliData))

        BiliData.atAll.clear()
        BiliData.atAllCooldownUntil.clear()

        val changed = loadFromYamlViaReflection(serialized)

        assertFalse(changed, "当前 schema 重载不应再触发迁移")
        assertTrue(serialized.contains("atAllCooldownUntil:"), "冷却表应进入当前持久化结构")
        assertFalse(AtAllService.shouldAtAll(runtimeSubject, uid, liveMessage(uid), now + 30 * 60 * 1000L), "重载后两小时窗口内仍应阻断同类型通知")
        assertTrue(AtAllService.shouldAtAll(runtimeSubject, uid, liveMessage(uid), now + 2 * 60 * 60 * 1000L + 1L), "冷却过期后应重新放行")
    }

    /**
     * WebUI 保存 BiliConfig 后会立即刷新配置快照，运行态必须同步到新配置，否则页面会继续读到旧值。
     */
    @Test
    fun `saveConfig should update runtime config after successful persistence`() {
        val originalFileBytes = if (Files.exists(configFile)) Files.readAllBytes(configFile) else null
        val originalConfigDirExists = Files.exists(configFile.parent)
        val originalRuntimeConfig = currentRuntimeConfigOrNull()
        val oldConfig = BiliConfig(adminContact = "onebot11:private:10001")
        val newConfig = BiliConfig(adminContact = "onebot11:private:10002")
        try {
            // saveConfig 的生产路径依赖 init 先建目录，用例只验证成功落盘后的运行态同步。
            Files.createDirectories(configFile.parent)
            setRuntimeConfig(oldConfig)

            val saved = BiliConfigManager.saveConfig(newConfig)

            assertTrue(saved)
            assertEquals("onebot11:private:10002", BiliConfigManager.config.adminContact)
        } finally {
            if (originalRuntimeConfig != null) {
                setRuntimeConfig(originalRuntimeConfig)
            }
            if (originalFileBytes != null) {
                Files.createDirectories(configFile.parent)
                Files.write(configFile, originalFileBytes)
            } else {
                Files.deleteIfExists(configFile)
            }
            deleteConfigBackups()
            if (!originalConfigDirExists) {
                runCatching { Files.deleteIfExists(configFile.parent) }
            }
        }
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

    /**
     * 反射读取运行态配置用于测试恢复，兼容尚未初始化 BiliConfigManager 的测试进程。
     */
    private fun currentRuntimeConfigOrNull(): BiliConfig? {
        return runCatching { BiliConfigManager.config }.getOrNull()
    }

    /**
     * 反射写入运行态配置只用于隔离 saveConfig 回归测试，不绕过生产保存入口。
     */
    private fun setRuntimeConfig(config: BiliConfig) {
        val field = BiliConfigManager::class.java.getDeclaredField("config")
        field.isAccessible = true
        field.set(BiliConfigManager, config)
    }

    /**
     * 备份轮转只验证行为，不允许把 .bak 文件留在仓库配置目录里污染后续用例。
     */
    private fun deleteConfigBackups() {
        repeat(3) { index ->
            Files.deleteIfExists(Path.of("config", "BiliConfig.yml.bak.${index + 1}"))
        }
    }

    /**
     * 构造一个最小直播消息，供冷却持久化用例验证当前 schema 的重载行为。
     */
    private fun liveMessage(uid: Long): LiveMessage {
        return LiveMessage(
            rid = 1000L,
            mid = uid,
            name = "测试主播",
            time = "2026-05-20 18:00:00",
            timestamp = 1778992800,
            title = "测试直播",
            cover = "https://example.com/cover.jpg",
            area = "测试分区",
            link = "https://live.bilibili.com/1000",
            drawPath = null,
            contact = null,
        )
    }
}
