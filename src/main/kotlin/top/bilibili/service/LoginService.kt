package top.bilibili.service

import top.bilibili.BiliConfigManager
import top.bilibili.connector.ImageSource
import top.bilibili.connector.OutgoingPart
import top.bilibili.connector.PlatformContact
import top.bilibili.core.BiliBiliBot
import top.bilibili.core.deepCopyForRuntimeSnapshot
import top.bilibili.utils.toSubject
import java.io.File
import java.net.URI

/**
 * 统一封装扫码登录流程，避免消息入口直接处理二维码轮询与 Cookie 落盘。
 */
object LoginService {
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
        logger.info("开始 BiliBili QR 码登录流程，联系人 ${contact.toSubject()}")
        val started = when (val result = QrLoginCoordinator.shared.start("command:${contact.toSubject()}")) {
            is QrLoginStartResult.Conflict -> {
                val message = result.remainingSeconds?.let { remainingSeconds ->
                    "已有登录流程进行中，请在 $remainingSeconds 秒后重试"
                } ?: "已有登录流程正在提交凭据，请稍候"
                sendMessage(contact, message)
                return false
            }
            is QrLoginStartResult.Failed -> {
                sendMessage(contact, result.message)
                logger.error(result.message)
                return false
            }
            is QrLoginStartResult.Started -> result
        }

        var qrImageFile: File? = null
        try {
            val qrImageFileName = "bili_qr_${System.currentTimeMillis()}.png"
            val generatedQrImageFile = createLoginQrTempFile(qrImageFileName, started.qrImageBytes)
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
                        append(started.fallbackUrl)
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
                        append(started.fallbackUrl)
                    },
                )
            }

            val terminal = QrLoginCoordinator.shared.awaitTerminal(started.snapshot.sessionId)
            when (terminal?.phase) {
                QrLoginPhase.SUCCEEDED -> {
                    sendMessage(contact, "BiliBili 登录成功")
                    logger.info("BiliBili 登录成功")
                }
                QrLoginPhase.EXPIRED -> {
                    sendMessage(contact, terminal.message)
                    logger.warn(terminal.message)
                }
                QrLoginPhase.CANCELLED -> sendMessage(contact, "登录流程已失效，请重新发送 /login")
                QrLoginPhase.FAILED, null -> {
                    val message = terminal?.message ?: "登录失败，请稍后重试"
                    sendMessage(contact, message)
                    logger.error(message)
                }
                else -> Unit
            }
            return true
        } catch (e: Exception) {
            // 会话创建后的命令编排异常必须先释放等待态占用；COMMITTING 会由协调器拒绝取消并继续收口。
            QrLoginCoordinator.shared.cancel(started.snapshot.sessionId)
            logger.error("登录流程发生异常", e)
            sendMessage(contact, "登录过程出错，请稍后重试")
            return false
        } finally {
            runCatching { qrImageFile?.delete() }
                .onFailure { logger.warn("删除二维码临时文件失败: ${it.message}") }
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
