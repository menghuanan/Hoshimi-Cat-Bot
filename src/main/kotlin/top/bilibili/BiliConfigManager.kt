package top.bilibili

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.slf4j.LoggerFactory
import top.bilibili.core.deepCopyForRuntimeSnapshot
import top.bilibili.service.TemplateRuntimeCoordinator
import top.bilibili.utils.normalizeContactSubject
import java.io.File
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * 配置与持久化数据的统一加载、保存和迁移入口。
 */
object BiliConfigManager {
    private val logger = LoggerFactory.getLogger(BiliConfigManager::class.java)
    private const val CURRENT_DATA_VERSION = 4
    private const val MAX_CONFIG_BACKUPS = 3

    lateinit var config: BiliConfig
        private set

    lateinit var data: BiliData
        private set

    private val configDir = Paths.get("config").toFile()
    private val dataDir = Paths.get("data").toFile()

    private val configFile = File(configDir, "BiliConfig.yml")
    private val dataFile = File(configDir, "BiliData.yml")

    private val yaml = Yaml(
        configuration = Yaml.default.configuration.copy(
            strictMode = false,
        ),
    )

    /**
     * 仅用于探测数据文件版本号的最小结构。
     * 先读取版本再选择对应 wrapper，避免旧字段在新版持久化模型里被直接忽略掉。
     */
    @kotlinx.serialization.Serializable
    private data class DataVersionProbe(
        val dataVersion: Int = 0,
    )

    /**
     * 初始化配置目录、配置对象和持久化数据。
     */
    fun init() {
        configDir.mkdirs()
        dataDir.mkdirs()

        config = loadConfig()
        logger.info("配置加载完成")

        data = loadData()
        logger.info("数据加载完成")
    }

    /**
     * 读取 BiliData.yml 的最近修改时间，仅供 WebUI 旧卡片更新时间兜底展示。
     */
    fun dataFileLastModifiedEpochMillis(): Long {
        return dataFile.takeIf { it.exists() }?.lastModified()?.coerceAtLeast(0L) ?: 0L
    }

    /**
     * 从磁盘加载主配置；文件不存在时创建默认配置。
     */
    private fun loadConfig(): BiliConfig {
        return try {
            if (configFile.exists()) {
                val content = configFile.readText()
                yaml.decodeFromString<BiliConfig>(content)
            } else {
                logger.info("配置文件不存在，创建默认配置")
                val defaultConfig = BiliConfig()
                saveConfig(defaultConfig)
                defaultConfig
            }
        } catch (e: Exception) {
            logger.error("加载配置文件失败，使用默认配置", e)
            BiliConfig()
        }
    }

    /**
     * 从磁盘加载业务数据，并在必要时执行旧版本迁移。
     */
    private fun loadData(): BiliData {
        return try {
            val oldDataFile = File(dataDir, "BiliData.yml")
            if (!dataFile.exists() && oldDataFile.exists()) {
                // 先复制旧位置数据，是为了在升级路径时尽量保住已有订阅状态。
                logger.info("检测到旧数据文件，正在迁移到新位置")
                oldDataFile.copyTo(dataFile, overwrite = false)
            }

            if (!dataFile.exists()) {
                logger.info("数据文件不存在，创建默认数据")
                BiliData.dataVersion = CURRENT_DATA_VERSION
                saveData(BiliData)
                return BiliData
            }

            val content = dataFile.readText()
            if (content.isBlank() || content.trim() == "{}") {
                logger.warn("数据文件为空，使用默认数据")
                BiliData.dataVersion = CURRENT_DATA_VERSION
                return BiliData
            }

            val migrated = loadDataFromContent(content, BiliData)
            if (migrated) {
                logger.info("检测到旧版数据结构，已完成迁移并准备写回")
                saveData(BiliData)
            }

            logger.info(
                "数据加载完成：{} 个订阅，{} 个分组",
                BiliData.dynamic.size,
                BiliData.group.size,
            )
            BiliData
        } catch (e: Exception) {
            logger.error("加载数据文件失败，使用默认数据", e)
            BiliData
        }
    }

    /**
     * 按 dataVersion 选择合适的读取结构，并在回填后执行迁移。
     * v4 起只认新的 policy-only 持久化结构，旧版模板绑定字段仅在低版本读取阶段参与迁移。
     */
    private fun loadDataFromContent(content: String, targetData: BiliData): Boolean {
        val dataVersion = readDataVersion(content)
        if (dataVersion < CURRENT_DATA_VERSION) {
            val legacyWrapper = yaml.decodeFromString<LegacyBiliDataWrapperV3>(content)
            LegacyBiliDataWrapperV3.applyTo(legacyWrapper, targetData)
        } else {
            val loadedWrapper = yaml.decodeFromString<BiliDataWrapper>(content)
            BiliDataWrapper.applyTo(loadedWrapper, targetData)
        }
        return migrateDataIfNeeded(targetData)
    }

    /**
     * 读取数据版本号。
     * 旧文件缺省 version 时按 0 处理，让迁移链路兜底覆盖最老的结构。
     */
    private fun readDataVersion(content: String): Int {
        return runCatching {
            yaml.decodeFromString<DataVersionProbe>(content).dataVersion
        }.getOrDefault(0)
    }

    /**
     * 根据数据版本执行兼容迁移，并在内容变更时提升版本号。
     */
    private fun migrateDataIfNeeded(data: BiliData): Boolean {
        var changed = false

        changed = migrateLegacyContactSubjects(data) || changed
        changed = migrateTemplatePolicyScopes(data) || changed
        if (data.dataVersion < 4) {
            changed = migrateLegacyTemplatePolicies(data) || changed
            changed = clearLegacyTemplateBindings(data) || changed
        }
        // 旧文件只有 contacts 时，需要在加载阶段反推来源引用，避免升级后模板/配置查询丢失 groupRef 绑定。
        changed = restoreLegacySubscriptionSources(data) || changed

        if (changed || data.dataVersion < CURRENT_DATA_VERSION) {
            data.dataVersion = CURRENT_DATA_VERSION
        }

        return changed
    }

    /**
     * 在旧模板绑定迁移完成后清空遗留字段。
     * 这样 dataVersion 升级后的内存态和持久化态都只保留新的 policy-only 结构。
     */
    private fun clearLegacyTemplateBindings(data: BiliData): Boolean {
        val hadLegacyData = data.dynamicPushTemplate.isNotEmpty() ||
            data.livePushTemplate.isNotEmpty() ||
            data.liveCloseTemplate.isNotEmpty() ||
            data.dynamicPushTemplateByUid.isNotEmpty() ||
            data.livePushTemplateByUid.isNotEmpty() ||
            data.liveCloseTemplateByUid.isNotEmpty()

        data.dynamicPushTemplate = mutableMapOf()
        data.livePushTemplate = mutableMapOf()
        data.liveCloseTemplate = mutableMapOf()
        data.dynamicPushTemplateByUid = mutableMapOf()
        data.livePushTemplateByUid = mutableMapOf()
        data.liveCloseTemplateByUid = mutableMapOf()

        return hadLegacyData
    }

    /**
     * 将模板策略 scope 归一到 contact:<subject> 或 groupRef:<name>，修复旧 WebUI 直写裸联系人造成的发送链路失配。
     */
    private fun migrateTemplatePolicyScopes(data: BiliData): Boolean {
        var changed = false
        migrateTemplatePolicyScopeMap(data.dynamicTemplatePolicyByScope).also {
            data.dynamicTemplatePolicyByScope = it.value
            changed = it.changed || changed
        }
        migrateTemplatePolicyScopeMap(data.liveTemplatePolicyByScope).also {
            data.liveTemplatePolicyByScope = it.value
            changed = it.changed || changed
        }
        migrateTemplatePolicyScopeMap(data.liveCloseTemplatePolicyByScope).also {
            data.liveCloseTemplatePolicyByScope = it.value
            changed = it.changed || changed
        }
        if (changed) {
            // scope 迁移会替换策略表本体，必须同步清理模板选择缓存，避免运行态继续引用旧 scope。
            TemplateRuntimeCoordinator.replaceAllPolicies(
                dynamicPolicies = data.dynamicTemplatePolicyByScope,
                livePolicies = data.liveTemplatePolicyByScope,
                liveClosePolicies = data.liveCloseTemplatePolicyByScope,
            )
        }
        return changed
    }

    /**
     * 模板策略 map 的 key 是 scope，不是普通联系人 subject；联系人直绑必须补 contact: 前缀。
     */
    private fun migrateTemplatePolicyScopeMap(
        source: MutableMap<String, MutableMap<Long, TemplatePolicy>>,
    ): MigrationResult<MutableMap<String, MutableMap<Long, TemplatePolicy>>> {
        var changed = false
        val result = linkedMapOf<String, MutableMap<Long, TemplatePolicy>>()
        source.forEach { (scope, policiesByUid) ->
            val migratedScope = normalizeTemplatePolicyScope(scope)
            if (migratedScope != scope) {
                changed = true
            }
            val targetPolicies = result.getOrPut(migratedScope) { mutableMapOf() }
            mergeTemplatePoliciesByUid(targetPolicies, policiesByUid)
        }
        return MigrationResult(
            value = if (changed || result.size != source.size) result.toMutableMap() else source,
            changed = changed || result.size != source.size,
        )
    }

    /**
     * scope 归一化只接受 groupRef 和联系人两类；无法识别的历史自定义 key 保留原值避免误迁移。
     */
    private fun normalizeTemplatePolicyScope(scope: String): String {
        return when {
            scope.startsWith("groupRef:") -> scope
            scope.startsWith("contact:") -> {
                val subject = scope.removePrefix("contact:")
                "contact:${normalizeContactSubject(subject) ?: subject}"
            }
            else -> normalizeContactSubject(scope)?.let { subject -> "contact:$subject" } ?: scope
        }
    }

    /**
     * scope 迁移发生碰撞时合并模板列表，避免裸 key 与 contact:key 同时存在时覆盖任一侧策略。
     */
    private fun mergeTemplatePoliciesByUid(
        target: MutableMap<Long, TemplatePolicy>,
        incoming: MutableMap<Long, TemplatePolicy>,
    ) {
        incoming.forEach { (uid, incomingPolicy) ->
            val existing = target[uid]
            if (existing == null) {
                target[uid] = TemplatePolicy(
                    templates = incomingPolicy.templates.toMutableList(),
                    randomEnabled = incomingPolicy.randomEnabled,
                )
            } else {
                incomingPolicy.templates.forEach { templateName ->
                    if (templateName !in existing.templates) {
                        existing.templates += templateName
                    }
                }
                existing.randomEnabled = existing.randomEnabled || incomingPolicy.randomEnabled
            }
        }
    }

    /**
     * 为旧订阅记录补回来源引用。
     * 当历史文件只剩下展开后的 contacts 时，优先按当前分组成员关系恢复 groupRef，再为剩余联系人补 direct 来源。
     */
    private fun restoreLegacySubscriptionSources(data: BiliData): Boolean {
        var changed = false
        val normalizedGroupContacts = data.group.mapValues { (_, group) ->
            group.contacts.mapNotNullTo(linkedSetOf()) { contact -> normalizeContactSubject(contact) ?: contact }
        }

        data.dynamic.values.forEach { sub ->
            if (sub.sourceRefs.isNotEmpty() || sub.contacts.isEmpty()) {
                return@forEach
            }

            val inferredSourceRefs = linkedSetOf<String>()
            val coveredContacts = linkedSetOf<String>()
            normalizedGroupContacts.forEach { (groupName, contactsInGroup) ->
                if (contactsInGroup.isNotEmpty() && sub.contacts.containsAll(contactsInGroup)) {
                    inferredSourceRefs.add("groupRef:$groupName")
                    coveredContacts.addAll(contactsInGroup)
                }
            }

            sub.contacts.forEach { contact ->
                if (contact !in coveredContacts) {
                    inferredSourceRefs.add("direct:$contact")
                }
            }

            if (inferredSourceRefs.isNotEmpty()) {
                sub.sourceRefs.addAll(inferredSourceRefs)
                changed = true
            }
        }

        return changed
    }

    /**
     * 将旧模板绑定回填为按 scope 存储的新模板策略。
     * 旧会话级模板先回填联系人 scope，再由旧 UID 单模板绑定覆盖同一 scope 的默认结果。
     */
    private fun migrateLegacyTemplatePolicies(data: BiliData): Boolean {
        var changed = false
        changed = migrateLegacyTemplatePolicyMap(
            legacyTemplateBindings = data.dynamicPushTemplate,
            legacyTemplateBindingsByUid = data.dynamicPushTemplateByUid,
            targetPolicies = data.dynamicTemplatePolicyByScope,
            subscriptions = data.dynamic,
        ) || changed
        changed = migrateLegacyTemplatePolicyMap(
            legacyTemplateBindings = data.livePushTemplate,
            legacyTemplateBindingsByUid = data.livePushTemplateByUid,
            targetPolicies = data.liveTemplatePolicyByScope,
            subscriptions = data.dynamic,
        ) || changed
        changed = migrateLegacyTemplatePolicyMap(
            legacyTemplateBindings = data.liveCloseTemplate,
            legacyTemplateBindingsByUid = data.liveCloseTemplateByUid,
            targetPolicies = data.liveCloseTemplatePolicyByScope,
            subscriptions = data.dynamic,
        ) || changed
        return changed
    }

    /**
     * 将历史联系人 subject 迁移为当前统一格式。
     */
    private fun migrateLegacyContactSubjects(data: BiliData): Boolean {
        var changed = false

        data.dynamic.values.forEach { sub ->
            changed = migrateStringSet(sub.contacts) || changed
            changed = migrateSourceRefSet(sub.sourceRefs) || changed
        }

        migrateNestedMap(data.filter).also {
            data.filter = it.value
            changed = it.changed || changed
        }
        migrateTemplateBindings(data.dynamicPushTemplate).also {
            data.dynamicPushTemplate = it.value
            changed = it.changed || changed
        }
        migrateTemplateBindings(data.livePushTemplate).also {
            data.livePushTemplate = it.value
            changed = it.changed || changed
        }
        migrateTemplateBindings(data.liveCloseTemplate).also {
            data.liveCloseTemplate = it.value
            changed = it.changed || changed
        }
        migrateNestedMap(data.dynamicPushTemplateByUid).also {
            data.dynamicPushTemplateByUid = it.value
            changed = it.changed || changed
        }
        migrateNestedMap(data.livePushTemplateByUid).also {
            data.livePushTemplateByUid = it.value
            changed = it.changed || changed
        }
        migrateNestedMap(data.liveCloseTemplateByUid).also {
            data.liveCloseTemplateByUid = it.value
            changed = it.changed || changed
        }
        migrateNestedMap(data.dynamicColorByUid).also {
            data.dynamicColorByUid = it.value
            changed = it.changed || changed
        }
        migrateNestedMap(data.atAll).also {
            data.atAll = it.value
            changed = it.changed || changed
        }

        data.group.values.forEach { group ->
            changed = migrateStringSet(group.contacts) || changed
            changed = migrateStringSet(group.adminContacts) || changed
            if (group.creatorContact.isBlank() && group.creator > 0L) {
                // 旧数据只存数字 QQ 号时补全 subject，是为了后续统一按平台联系人处理权限。
                group.creatorContact = "onebot11:private:${group.creator}"
                changed = true
            } else {
                val normalizedCreator = normalizeContactSubject(group.creatorContact)
                if (normalizedCreator != null && normalizedCreator != group.creatorContact) {
                    group.creatorContact = normalizedCreator
                    changed = true
                }
            }
            if (group.adminContacts.isEmpty() && group.admin.isNotEmpty()) {
                group.admin.forEach { adminId ->
                    // 这里回填管理员联系人集合，是为了兼容旧版只存数字管理员 ID 的数据结构。
                    group.adminContacts.add("onebot11:private:$adminId")
                }
                changed = true
            }
        }
        data.bangumi.values.forEach { bangumi ->
            changed = migrateStringSet(bangumi.contacts) || changed
        }
        if (data.linkParseBlacklist.isNotEmpty()) {
            data.linkParseBlacklist.forEach { userId ->
                // 黑名单迁移到联系人格式后，后续才能跨平台复用同一套拦截逻辑。
                data.linkParseBlacklistContacts.add("onebot11:private:$userId")
            }
            data.linkParseBlacklist.clear()
            changed = true
        }
        changed = migrateStringSet(data.linkParseBlacklistContacts) || changed

        return changed
    }

    /**
     * 迁移纯字符串集合中的联系人 subject。
     */
    private fun migrateStringSet(values: MutableSet<String>): Boolean {
        val migrated = linkedSetOf<String>()
        var changed = false
        values.forEach { value ->
            val normalized = normalizeContactSubject(value) ?: value
            if (normalized != value) {
                changed = true
            }
            migrated.add(normalized)
        }
        if (migrated != values) {
            values.clear()
            values.addAll(migrated)
            changed = true
        }
        return changed
    }

    /**
     * 将旧模板绑定映射迁移为新的联系人 scope 策略。
     * 这里仅在目标策略缺失时回填，避免迁移覆盖已经存在的新结构配置。
     */
    private fun migrateLegacyTemplatePolicyMap(
        legacyTemplateBindings: MutableMap<String, MutableSet<String>>,
        legacyTemplateBindingsByUid: MutableMap<String, MutableMap<Long, String>>,
        targetPolicies: MutableMap<String, MutableMap<Long, TemplatePolicy>>,
        subscriptions: MutableMap<Long, SubData>,
    ): Boolean {
        var changed = false
        val existingPolicyKeys = targetPolicies.flatMap { (scope, policies) ->
            policies.keys.map { uid -> scope to uid }
        }.toSet()

        legacyTemplateBindings.forEach { (templateName, contacts) ->
            contacts.forEach { subject ->
                val scope = "contact:$subject"
                subscriptions.forEach { (uid, subData) ->
                    if (subject in subData.contacts) {
                        changed = upsertLegacyTemplatePolicy(
                            targetPolicies = targetPolicies,
                            scope = scope,
                            uid = uid,
                            templateName = templateName,
                            overwriteExisting = false,
                        ) || changed
                    }
                }
            }
        }

        legacyTemplateBindingsByUid.forEach { (subject, bindings) ->
            val scope = "contact:$subject"
            bindings.forEach { (uid, templateName) ->
                changed = upsertLegacyTemplatePolicy(
                    targetPolicies = targetPolicies,
                    scope = scope,
                    uid = uid,
                    templateName = templateName,
                    overwriteExisting = (scope to uid) !in existingPolicyKeys,
                ) || changed
            }
        }

        return changed
    }

    /**
     * 将单个旧模板名写入新策略。
     * 迁移出的策略始终是单模板且关闭随机，保持旧行为的可预测性。
     */
    private fun upsertLegacyTemplatePolicy(
        targetPolicies: MutableMap<String, MutableMap<Long, TemplatePolicy>>,
        scope: String,
        uid: Long,
        templateName: String,
        overwriteExisting: Boolean,
    ): Boolean {
        val scopePolicies = targetPolicies.getOrPut(scope) { mutableMapOf() }
        val existingPolicy = scopePolicies[uid]
        if (existingPolicy != null && !overwriteExisting) {
            return false
        }

        val nextPolicy = TemplatePolicy(
            templates = mutableListOf(templateName),
            randomEnabled = false,
        )
        if (existingPolicy == nextPolicy) {
            return false
        }

        scopePolicies[uid] = nextPolicy
        return true
    }

    /**
     * 迁移带 `direct:` 前缀的订阅来源引用集合。
     */
    private fun migrateSourceRefSet(values: MutableSet<String>): Boolean {
        val migrated = linkedSetOf<String>()
        var changed = false
        values.forEach { value ->
            val normalized = if (value.startsWith("direct:")) {
                val subject = value.removePrefix("direct:")
                val normalizedSubject = normalizeContactSubject(subject)
                if (normalizedSubject != null) {
                    "direct:$normalizedSubject"
                } else {
                    value
                }
            } else {
                value
            }
            if (normalized != value) {
                changed = true
            }
            migrated.add(normalized)
        }
        if (migrated != values) {
            values.clear()
            values.addAll(migrated)
            changed = true
        }
        return changed
    }

    /**
     * 迁移模板到联系人集合的绑定映射。
     */
    private fun migrateTemplateBindings(
        source: MutableMap<String, MutableSet<String>>,
    ): MigrationResult<MutableMap<String, MutableSet<String>>> {
        var changed = false
        val result = linkedMapOf<String, MutableSet<String>>()
        source.forEach { (template, contacts) ->
            val migrated = linkedSetOf<String>()
            contacts.forEach { contact ->
                val normalized = normalizeContactSubject(contact) ?: contact
                if (normalized != contact) {
                    changed = true
                }
                migrated.add(normalized)
            }
            result[template] = migrated
            if (migrated != contacts) {
                changed = true
            }
        }
        return MigrationResult(if (changed) result.toMutableMap() else source, changed)
    }

    /**
     * 迁移以联系人 subject 为键的嵌套映射。
     */
    private fun <T> migrateNestedMap(
        source: MutableMap<String, T>,
    ): MigrationResult<MutableMap<String, T>> {
        var changed = false
        val result = linkedMapOf<String, T>()
        source.forEach { (subject, value) ->
            val normalized = normalizeContactSubject(subject) ?: subject
            if (normalized != subject) {
                changed = true
            }
            val existing = result[normalized]
            result[normalized] = mergeNestedValues(existing, value)
        }
        return MigrationResult(
            value = if (changed || result.size != source.size) result.toMutableMap() else source,
            changed = changed || result.size != source.size,
        )
    }

    /**
     * 在归一化键冲突时合并旧值与新值，尽量避免迁移过程中覆盖原有配置。
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> mergeNestedValues(existing: T?, incoming: T): T {
        if (existing is MutableMap<*, *> && incoming is MutableMap<*, *>) {
            val merged = linkedMapOf<Any?, Any?>()
            // 新值后写入，是为了让显式归一化后的条目优先覆盖旧键映射结果。
            merged.putAll(existing as MutableMap<Any?, Any?>)
            merged.putAll(incoming as MutableMap<Any?, Any?>)
            return merged.toMutableMap() as T
        }
        return incoming
    }

    /**
     * 将当前配置写回配置文件，并把真实落盘结果返回给调用方。
     */
    fun saveConfig(configToSave: BiliConfig = config): Boolean {
        return try {
            writeConfigSnapshotToDisk(configToSave)
            // WebUI 保存后会立即从运行态读取快照，写盘成功后必须同步内存态。
            config = configToSave
            logger.debug("配置已保存")
            true
        } catch (e: Exception) {
            logger.error("保存配置文件失败", e)
            false
        }
    }

    /**
     * 只把候选主配置写入磁盘，不安装到运行态；热重载提交阶段才允许切换内存态。
     */
    fun persistConfigSnapshot(configSnapshot: BiliConfig): Boolean {
        return try {
            writeConfigSnapshotToDisk(configSnapshot)
            logger.debug("候选配置已持久化")
            true
        } catch (e: Exception) {
            logger.error("保存候选配置文件失败", e)
            false
        }
    }

    /**
     * 导出当前主配置和业务数据深度快照，供热重载失败时恢复旧运行态。
     */
    fun runtimeSnapshot(): Pair<BiliConfig, BiliDataWrapper> {
        return config.deepCopyForRuntimeSnapshot() to BiliDataWrapper.deepCopyFrom(BiliData)
    }

    /**
     * 安装已验证的运行期快照；该入口只给热重载/回滚使用，仍不允许业务层直接写 YAML。
     */
    fun installRuntimeSnapshot(configSnapshot: BiliConfig, dataSnapshot: BiliDataWrapper) {
        installConfigRuntimeSnapshot(configSnapshot)
        installDataRuntimeSnapshot(dataSnapshot)
    }

    /**
     * 只安装 `BiliConfig.yml` 运行态切片，避免非数据保存重置 BiliData 及模板协调缓存。
     */
    fun installConfigRuntimeSnapshot(configSnapshot: BiliConfig) {
        config = configSnapshot.deepCopyForRuntimeSnapshot()
    }

    /**
     * 只安装 `BiliData.yml` 运行态切片，供数据热重载和失败回滚精确替换业务数据缓存。
     */
    fun installDataRuntimeSnapshot(
        dataSnapshot: BiliDataWrapper,
        preserveUnchangedTemplateRuntimeBindings: Boolean = false,
    ) {
        BiliDataWrapper.applyTo(
            dataSnapshot.deepCopy(),
            BiliData,
            preserveUnchangedTemplateRuntimeBindings = preserveUnchangedTemplateRuntimeBindings,
        )
        data = BiliData
    }

    /**
     * 将当前业务数据写回数据文件，并对空写入结果做保护检查。
     */
    fun saveData(dataToSave: BiliData = BiliData): Boolean {
        val wrapper = BiliDataWrapper.from(
            biliData = dataToSave,
            templatePolicies = TemplateRuntimeCoordinator.snapshotPolicies(),
        ).deepCopy()
        return saveDataWrapper(wrapper)
    }

    /**
     * 只更新链接解析黑名单联系人集合，并确保成功落盘后才切换全局运行态。
     * 这样 WebUI 在保存失败时不会出现“磁盘未提交、内存已变更”的半提交状态。
     */
    fun saveLinkParseBlacklistContacts(contacts: Set<String>): Boolean {
        val wrapperToSave = BiliDataWrapper.from(
            biliData = BiliData,
            templatePolicies = TemplateRuntimeCoordinator.snapshotPolicies(),
        ).copy(
            linkParseBlacklistContacts = contacts.toMutableSet(),
        )
        val saved = saveDataWrapper(wrapperToSave)
        if (saved) {
            BiliData.linkParseBlacklistContacts = contacts.toMutableSet()
        }
        return saved
    }

    /**
     * 将业务数据 wrapper 按 owner 路径写回磁盘，并可选择在写盘成功后同步安装到运行态。
     */
    fun saveDataSnapshot(dataSnapshot: BiliDataWrapper, installAfterSave: Boolean = false): Boolean {
        val snapshot = dataSnapshot.deepCopy()
        val saved = saveDataWrapper(snapshot)
        if (saved && installAfterSave) {
            BiliDataWrapper.applyTo(snapshot.deepCopy(), BiliData)
            data = BiliData
        }
        return saved
    }

    /**
     * 同时保存配置与业务数据。
     */
    fun saveAll() {
        saveConfig()
        saveData()
    }

    /**
     * 数据文件统一通过同一条落盘路径写入，避免不同调用方各自实现空文件保护和错误日志。
     */
    private fun saveDataWrapper(wrapperToSave: BiliDataWrapper): Boolean {
        return try {
            val yamlContent = yaml.encodeToString(wrapperToSave)
            writeConfigFileAtomically(dataFile, yamlContent)

            val savedContent = dataFile.readText(Charsets.UTF_8)
            if (savedContent.trim() == "{}") {
                logger.error("警告：保存的数据文件为空！")
                false
            } else {
                logger.info("数据已保存到 {}", dataFile.absolutePath)
                true
            }
        } catch (e: Exception) {
            logger.error("保存数据文件失败", e)
            false
        }
    }

    /**
     * 主配置写盘 helper 只处理磁盘原子写，调用方决定是否安装运行态。
     */
    private fun writeConfigSnapshotToDisk(configSnapshot: BiliConfig) {
        val configToSave = configSnapshot
        writeConfigFileAtomically(configFile, yaml.encodeToString(configToSave))
    }

    /**
     * 配置 owner 写盘统一采用 temp-file + rename，并保留有限备份，避免直接覆盖造成半写文件。
     */
    private fun writeConfigFileAtomically(targetFile: File, content: String) {
        if (!targetFile.parentFile.exists()) {
            targetFile.parentFile.mkdirs()
        }
        val tempFile = File.createTempFile(targetFile.nameWithoutExtension, ".tmp", targetFile.parentFile)
        try {
            tempFile.writeText(content, Charsets.UTF_8)
            rotateConfigBackups(targetFile)
            moveReplacing(tempFile, targetFile)
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    /**
     * 每个配置文件只保留最近几份 bak.N，避免 WebUI 多次保存无限堆积备份。
     */
    private fun rotateConfigBackups(targetFile: File) {
        if (!targetFile.exists()) {
            return
        }
        for (index in MAX_CONFIG_BACKUPS downTo 1) {
            val backup = configBackupFile(targetFile, index)
            if (!backup.exists()) {
                continue
            }
            if (index == MAX_CONFIG_BACKUPS) {
                backup.delete()
            } else {
                moveReplacing(backup, configBackupFile(targetFile, index + 1))
            }
        }
        java.nio.file.Files.copy(
            targetFile.toPath(),
            configBackupFile(targetFile, 1).toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    /**
     * 原子移动在文件系统不支持时降级为替换移动，保证 Windows 和 Linux 都能完成持久化。
     */
    private fun moveReplacing(source: File, target: File) {
        runCatching {
            java.nio.file.Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            java.nio.file.Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    /**
     * 备份文件沿用原文件名追加 bak 序号，便于人工识别和回滚。
     */
    private fun configBackupFile(targetFile: File, index: Int): File {
        return File(targetFile.parentFile, "${targetFile.name}.bak.$index")
    }

    /**
     * 重新加载主配置。
     */
    fun reloadConfig() {
        config = loadConfig()
        logger.info("配置已重新加载")
    }

    /**
     * 重新加载业务数据。
     */
    fun reloadData() {
        data = loadData()
        logger.info("数据已重新加载")
    }

    /**
     * 重新加载配置与业务数据。
     */
    fun reloadAll() {
        reloadConfig()
        reloadData()
    }

    /**
     * 迁移操作的统一返回结果。
     */
    private data class MigrationResult<T>(
        val value: T,
        val changed: Boolean,
    )
}
