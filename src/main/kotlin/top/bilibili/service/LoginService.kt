package top.bilibili.service

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import top.bilibili.BiliConfigManager
import top.bilibili.api.getLoginQrcode
import top.bilibili.api.loginInfo
import top.bilibili.api.markBiliLoginSucceeded
import top.bilibili.connector.ImageSource
import top.bilibili.connector.OutgoingPart
import top.bilibili.connector.PlatformContact
import top.bilibili.core.BiliBiliBot
import top.bilibili.core.deepCopyForRuntimeSnapshot
import top.bilibili.core.resource.BusinessLifecycleManager
import top.bilibili.core.resource.ResourceStrictness
import top.bilibili.draw.loginQrCodeBytes
import top.bilibili.initTagid
import top.bilibili.utils.toSubject
import java.io.File
import java.net.URI
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 统一封装扫码登录流程，避免消息入口直接处理二维码轮询与 Cookie 落盘。
 */
object LoginService {
    private const val LOGIN_TTL_MS = 180_000L
    private val loginGeneration = AtomicLong(0L)
    private val activeLogin = AtomicReference<ActiveQrLoginState?>(null)

    /** 当前全局活动二维码登录，代际用于阻止旧流程的迟到成功提交。 */
    internal data class ActiveQrLoginState(
        val generation: Long,
        val startedAtEpochMillis: Long,
        val expiresAtEpochMillis: Long,
        val requester: String,
        val committing: Boolean = false,
    )
    /**
     * 登录回调解析结果：统一携带 cookie 字符串和可选的 DedeUserID。
     */
    internal data class LoginCallbackPayload(
        val cookie: String,
        val dedeUserId: String?,
    )

    /**
     * 执行二维码登录全流程，并在成功后刷新运行时账号状态与持久化配置。
     */
    suspend fun login(contact: PlatformContact): Boolean {
        val now = System.currentTimeMillis()
        val state = ActiveQrLoginState(
            generation = loginGeneration.incrementAndGet(),
            startedAtEpochMillis = now,
            expiresAtEpochMillis = now + LOGIN_TTL_MS,
            requester = contact.toSubject(),
        )
        val existing = activeLogin.get()
        if (existing != null && (existing.committing || existing.expiresAtEpochMillis > now)) {
            val remainingSeconds = ((existing.expiresAtEpochMillis - now + 999L) / 1_000L).coerceAtLeast(1L)
            sendMessage(contact, "已有登录流程进行中，请在 ${remainingSeconds} 秒后重试")
            return false
        }
        if (!activeLogin.compareAndSet(existing, state)) {
            sendMessage(contact, "已有登录流程进行中，请稍后重试")
            return false
        }

        try {
        BusinessLifecycleManager.run(
            owner = "LoginService",
            operation = "login:${contact.toSubject()}",
            strictness = ResourceStrictness.RELAXED_LONG_RUNNING,
        ) {
            logger.info("开始 BiliBili QR 码登录流程，联系人 ${contact.toSubject()}")

            var qrImageFile: File? = null
            onFinally {
                runCatching { qrImageFile?.delete() }
                    .onFailure { logger.warn("删除二维码临时文件失败: ${it.message}") }
            }

            try {
                val loginData = client.getLoginQrcode()
                if (loginData == null) {
                    sendMessage(contact, "获取登录二维码失败")
                    logger.error("获取登录二维码失败")
                    return@run
                }

                val qrImageFileName = "bili_qr_${System.currentTimeMillis()}.png"
                val qrImageBytes = loginQrCodeBytes(loginData.url)
                val generatedQrImageFile = createLoginQrTempFile(qrImageFileName, qrImageBytes)
                if (generatedQrImageFile != null) {
                    logger.info("登录二维码临时文件已生成: ${generatedQrImageFile.name}")
                } else {
                    logger.warn("登录二维码临时文件生成失败，将退回文本登录链接")
                }
                qrImageFile = generatedQrImageFile

                val qrSendSucceeded = if (generatedQrImageFile != null) {
                    sendPartsWithCapabilityFallback(
                        contact,
                        listOf(
                            OutgoingPart.text("请使用 BiliBili 手机 APP 扫码登录（3 分钟有效）"),
                            OutgoingPart.image(ImageSource.LocalFile(generatedQrImageFile.absolutePath)),
                        ),
                        fallbackText = buildString {
                            appendLine("当前平台不支持直接发送登录二维码图片。")
                            appendLine("请复制下面的二维码链接到浏览器打开后完成扫码登录：")
                            append(loginData.url)
                        },
                    )
                } else {
                    false
                }
                if (!qrSendSucceeded) {
                    logger.warn("登录二维码发送失败，改为发送文本登录链接")
                    sendMessage(
                        contact,
                        buildString {
                            appendLine("登录二维码发送失败，请复制下面的链接到浏览器打开后完成登录：")
                            append(loginData.url)
                        },
                    )
                }

                runCatching {
                    withTimeout(LOGIN_TTL_MS) {
                        while (isActive) {
                            delay(3_000)
                            val loginInfo = client.loginInfo(loginData.qrcodeKey!!)

                            when (loginInfo?.code) {
                                0 -> {
                                    val callbackPayload = parseLoginCallback(loginInfo.url!!)
                                    if (callbackPayload.cookie.isNotEmpty()) {
                                        if (!commitLoginConfigForGeneration(state, callbackPayload.cookie)) {
                                            sendMessage(contact, "登录凭据保存失败，请稍后重试")
                                            logger.error("二维码登录凭据保存失败，未安装运行态 Cookie")
                                            break
                                        }
                                        BiliBiliBot.cookie.replaceWith(BiliBiliBot.cookie.fromHeader(callbackPayload.cookie))
                                        // 若当前登录回调携带 DedeUserID，则直接刷新运行时 UID，避免额外调用 userInfo。
                                        callbackPayload.dedeUserId?.toLongOrNull()?.let { dedeUserId ->
                                            BiliBiliBot.uid = dedeUserId
                                        }
                                        initTagid()
                                        // 登录成功后才恢复失效提醒门闩，避免启动期普通成功请求造成重复提醒。
                                        markBiliLoginSucceeded()
                                        sendMessage(contact, "BiliBili 登录成功")
                                        logger.info("BiliBili 登录成功")
                                    } else {
                                        val message = if (activeLogin.get()?.generation != state.generation) {
                                            "登录流程已失效，请重新发送 /login"
                                        } else {
                                            "Cookie 解析失败"
                                        }
                                        sendMessage(contact, message)
                                        logger.error(message)
                                    }
                                    break
                                }

                                86038 -> {
                                    sendMessage(contact, "二维码已失效，请重新登录")
                                    logger.warn("二维码已失效")
                                    break
                                }

                                86090 -> logger.debug("二维码已扫描，等待确认...")
                                86101 -> logger.debug("等待扫码...")
                                else -> logger.warn("登录状态未知: ${loginInfo?.code} - ${loginInfo?.message}")
                            }
                        }
                    }
                }.onFailure {
                    val userMessage = if (it is TimeoutCancellationException) {
                        "登录超时，请重新发送 /login"
                    } else {
                        "登录失败，请稍后重试"
                    }
                    sendMessage(contact, userMessage)
                    logger.error("登录失败", it)
                }
            } catch (e: Exception) {
                logger.error("登录流程发生异常", e)
                sendMessage(contact, "登录过程出错，请稍后重试")
            }
        }
        return true
        } finally {
            // 只允许当前代际释放占用，旧流程不得清除后来者的 active 状态。
            activeLogin.updateAndGet { current -> if (current?.generation == state.generation) null else current }
        }
    }

    /**
     * 从当前登录回调 URL 提取 Cookie 与可选用户 ID，保持成功路径停留在原有 API 包络内。
     */
    internal fun parseLoginCallback(url: String): LoginCallbackPayload {
        return try {
            val querys = URI(url).query.split("&")
            var dedeUserId: String? = null
            val cookie = buildString {
                querys.forEach { param ->
                    when {
                        param.startsWith("SESSDATA=") || param.startsWith("bili_jct=") -> {
                            append("${param.replace(",", "%2C").replace("*", "%2A")}; ")
                        }
                        param.startsWith("DedeUserID=") -> {
                            dedeUserId = param.substringAfter("=", missingDelimiterValue = "").ifBlank { null }
                        }
                    }
                }
            }.trim()
            LoginCallbackPayload(cookie = cookie, dedeUserId = dedeUserId)
        } catch (e: Exception) {
            logger.error("解析登录回调失败", e)
            LoginCallbackPayload(cookie = "", dedeUserId = null)
        }
    }

    /**
     * 二维码凭据使用候选配置先落盘后安装，失败时不得污染当前运行配置。
     *
     * @param persistCandidate 候选配置持久化边界，测试可注入失败验证原子性
     * @param installCandidate 持久化成功后的唯一运行态安装入口
     */
    internal fun commitLoginConfig(
        cookie: String,
        persistCandidate: (top.bilibili.BiliConfig) -> Boolean = BiliConfigManager::persistConfigSnapshot,
        installCandidate: (top.bilibili.BiliConfig) -> Unit = BiliConfigManager::installConfigRuntimeSnapshot,
    ): Boolean {
        val candidate = BiliConfigManager.config.deepCopyForRuntimeSnapshot()
        candidate.accountConfig.cookie = cookie
        if (!persistCandidate(candidate)) return false
        installCandidate(candidate)
        return true
    }

    /** 只有仍占用 active 状态的代际能进入不可替换的提交阶段，迟到结果在落盘前被拒绝。 */
    internal fun commitLoginConfigForGeneration(
        state: ActiveQrLoginState,
        cookie: String,
        persistCandidate: (top.bilibili.BiliConfig) -> Boolean = BiliConfigManager::persistConfigSnapshot,
        installCandidate: (top.bilibili.BiliConfig) -> Unit = BiliConfigManager::installConfigRuntimeSnapshot,
    ): Boolean {
        val committingState = state.copy(committing = true)
        if (!activeLogin.compareAndSet(state, committingState)) return false
        return commitLoginConfig(cookie, persistCandidate, installCandidate)
    }

    /** 测试隔离入口设置活动代际，生产流程只通过 login() 管理该状态。 */
    internal fun setActiveLoginForTest(state: ActiveQrLoginState?) {
        activeLogin.set(state)
    }

    /**
     * 将登录二维码统一落到共享 temp 目录，便于适配器按各自协议处理本地文件图片。
     */
    private fun createLoginQrTempFile(fileName: String, bytes: ByteArray): File? {
        return runCatching {
            BiliBiliBot.tempPath.resolve(fileName).toFile().apply {
                deleteOnExit()
                writeBytes(bytes)
            }
        }.onFailure {
            logger.warn("写入登录二维码临时文件失败: ${it.message}")
        }.getOrNull()
    }

    private suspend fun sendMessage(contact: PlatformContact, message: String) {
        sendMessage(contact, listOf(OutgoingPart.text(message)))
    }

    private suspend fun sendMessage(contact: PlatformContact, message: List<OutgoingPart>) {
        try {
            MessageGatewayProvider.require().sendMessage(contact, message)
        } catch (e: Exception) {
            logger.error("发送消息失败", e)
        }
    }
}
