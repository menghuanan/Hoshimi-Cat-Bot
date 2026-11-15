package top.bilibili.service

import java.security.SecureRandom
import java.util.UUID
import org.slf4j.LoggerFactory
import top.bilibili.BiliConfigManager
import top.bilibili.config.BotConfig
import top.bilibili.config.ConfigManager
import top.bilibili.config.isValidOfficialAdminId
import top.bilibili.connector.PlatformChatType
import top.bilibili.connector.PlatformInboundMessage
import top.bilibili.connector.PlatformType
import top.bilibili.core.BiliBiliBot

/**
 * 官方 QQ 首次管理员绑定：只保留一个限时内存会话，不创建 worker 或客户端。
 * 配置写入经 ConfigManager，与 WebUI 共用事务锁；connector 只调用无副作用的消息谓词。
 */
class QQAdminBindingService(
    private val configProvider: () -> BotConfig = { ConfigManager.botConfig },
    private val legacyAdminProvider: () -> String? = { BiliConfigManager.config.normalizedAdminSubject() },
    private val saveConfig: (BotConfig) -> Boolean = { candidate ->
        ConfigManager.saveConfig(candidate).also { saved ->
            if (saved) BiliBiliBot.installRuntimeConfig(ConfigManager.botConfig)
        }
    },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val logger = LoggerFactory.getLogger(QQAdminBindingService::class.java)
    private val random = SecureRandom()
    @Volatile private var session: Session? = null
    @Volatile private var accepting = false

    /** 启动和已提交配置变更时检查缺失管理员，生成的代码只向本机管理者提示。 */
    fun startIfNeeded() = ConfigManager.withConfigLock {
        accepting = true
        if (canBind()) {
            val previous = session
            val current = begin()
            if (previous?.id != current.id) {
                logger.warn("QQ 官方尚未绑定管理员。请在QQ内添加bot好友后发送以下代码：{} 后等待绑定完成。代码10分钟内有效。", current.code)
            }
        } else if (session?.phase == Phase.WAITING) {
            // 已完成会话保留到下一次绑定或停机，避免普通配置保存让弹窗漏掉成功结果。
            session = null
        }
    }

    /** 热重载只刷新已启动服务，不能在测试或停机阶段隐式复活绑定入口。 */
    fun refreshIfStarted() {
        if (accepting) ConfigManager.withConfigLock { if (accepting) startIfNeeded() }
    }

    /** WebUI 与启动提示复用未过期代码；已有管理员时拒绝隐式覆盖。 */
    fun begin(): Session = ConfigManager.withConfigLock {
        check(accepting) { "绑定服务尚未启动或已停止" }
        check(canBind()) { "请先保存官方 QQ 适配器配置，并清空已有的超级管理员后再绑定" }
        val config = configProvider()
        session?.takeIf { it.phase == Phase.WAITING && validContext(it) && nowMillis() < it.expiresAtEpochMillis }
            ?: Session(
                id = UUID.randomUUID().toString(),
                code = "QQ-" + ByteArray(12).also(random::nextBytes).joinToString("") { "%02X".format(it) },
                expiresAtEpochMillis = nowMillis() + 10 * 60_000L,
                appId = config.platform.qqOfficial.appId,
                originalAdmin = config.admin,
                legacyAdmin = legacyAdminProvider(),
            ).also { session = it }
    }

    /** 无锁门禁只放行当前代码的官方私聊，避免平台停机等待与配置事务相互阻塞。 */
    fun matches(event: PlatformInboundMessage): Boolean {
        val current = session ?: return false
        return accepting && current.phase == Phase.WAITING && nowMillis() < current.expiresAtEpochMillis &&
            validContext(current) && !event.fromSelf && event.platform == PlatformType.QQ_OFFICIAL &&
            event.chatType == PlatformChatType.PRIVATE && event.chatContact == event.senderContact &&
            event.senderContact.type == PlatformChatType.PRIVATE &&
            event.senderContact.platform == PlatformType.QQ_OFFICIAL &&
            isValidOfficialAdminId(event.senderContact.id) &&
            event.metadata["app_id"] == current.appId && event.messageText.trim() == current.code
    }

    /** 在同一事务内重新校验并持久化 OpenID，失败不授予权限，成功即消费代码。 */
    fun consume(event: PlatformInboundMessage): Boolean {
        // 普通消息不等待配置事务；只有匹配代码才进锁，并在锁内再次校验防止竞态。
        if (!matches(event)) return false
        return ConfigManager.withConfigLock {
            if (!matches(event)) return@withConfigLock false
            val current = requireNotNull(session)
            val saved = runCatching { saveConfig(configProvider().copy(admin = event.senderContact.id)) }
                .onFailure { logger.error("QQ 管理员绑定保存失败", it) }.getOrDefault(false)
            session = current.copy(code = null, phase = if (saved) Phase.SUCCEEDED else Phase.FAILED)
            if (saved) logger.info("QQ 官方管理员绑定完成，已保存到 bot.yml")
            else logger.error("QQ 管理员绑定失败，未能保存 bot.yml，请通过快速绑定重试")
            true
        }
    }

    /** 查询只保留一个会话；过期或配置变化立即清除可用代码，不启动定时清理任务。 */
    fun read(id: String): Session? = ConfigManager.withConfigLock {
        val current = session?.takeIf { it.id == id } ?: return@withConfigLock null
        if (current.phase == Phase.WAITING && (!validContext(current) || nowMillis() >= current.expiresAtEpochMillis)) {
            current.copy(code = null, phase = Phase.EXPIRED).also { session = it }
        } else current
    }

    /** 随平台入口关闭清除绑定能力，进程内重启也不能继续使用旧代码。 */
    fun stop() = ConfigManager.withConfigLock {
        accepting = false
        session = null
    }

    /** 缺少官方平台有效管理员时才允许首次绑定，旧 OneBot11 数字账号不算已绑定。 */
    private fun canBind(): Boolean {
        val config = configProvider()
        return config.selectedPlatformType() == PlatformType.QQ_OFFICIAL && config.validateSelectedPlatform() &&
            config.resolvedAdminSubject(legacyAdminProvider()) == null
    }

    /** 会话固定 bot 应用及管理员基线，平台切换或人工配置管理员后旧代码失效。 */
    private fun validContext(current: Session): Boolean {
        val config = configProvider()
        return canBind() && config.platform.qqOfficial.appId == current.appId &&
            config.admin == current.originalAdmin && legacyAdminProvider() == current.legacyAdmin
    }

    enum class Phase { WAITING, SUCCEEDED, EXPIRED, FAILED }

    /** 内部快照含配置基线；HTTP 层仅映射 id、code、有效期及状态，不直接序列化此对象。 */
    data class Session(
        val id: String,
        val code: String?,
        val expiresAtEpochMillis: Long,
        val appId: String,
        val originalAdmin: String?,
        val legacyAdmin: String?,
        val phase: Phase = Phase.WAITING,
    )
}
