package top.bilibili

import org.slf4j.LoggerFactory
import top.bilibili.core.BiliBiliBot
import top.bilibili.core.BotStartResult
import kotlin.system.exitProcess

private val logger = LoggerFactory.getLogger("Main")

/**
 * 返回当前程序版本标签，优先读取启动参数注入的版本号。
 */
internal fun currentVersionLabel(
    systemVersion: String? = System.getProperty("app.version"),
    implementationVersion: String? = BiliConfigManager::class.java.`package`?.implementationVersion,
): String {
    val resolvedVersion = sequenceOf(systemVersion, implementationVersion)
        .firstOrNull { !it.isNullOrBlank() }

    return resolvedVersion?.let(::normalizeVersionLabel) ?: "unknown"
}

/**
 * 统一补齐版本号前缀 `v`。
 */
private fun normalizeVersionLabel(version: String): String {
    val trimmedVersion = version.trim()
    return if (trimmedVersion.startsWith("v")) trimmedVersion else "v$trimmedVersion"
}

/**
 * 程序主入口。
 */
fun main(args: Array<String>) {
    SkikoInitializer.initialize()
    // Skiko native 限额已经落到 Graphics API 后再输出摘要，避免日志只反映部署侧假设。
    logJvmRuntimeSummary()

    try {
        var enableDebug: Boolean? = null
        var showHelp = false

        for (arg in args) {
            when (arg.lowercase()) {
                "--debug", "-d" -> enableDebug = true
                "--help", "-h" -> showHelp = true
            }
        }

        if (showHelp) {
            println(
                """
                BiliBili 动态推送 Bot ${currentVersionLabel()}

                用法: java -jar hoshimi-cat-bot.jar [选项]

                选项:
                  --debug, -d    启用 Debug 日志模式
                  --help, -h     显示帮助信息

                示例:
                  java -jar hoshimi-cat-bot.jar
                  java -jar hoshimi-cat-bot.jar --debug
                """.trimIndent(),
            )
            exitProcess(0)
        }

        Runtime.getRuntime().addShutdownHook(
            Thread {
                // 使用关闭钩子统一收尾，是为了在外部终止进程时也尽量释放 Bot 持有的资源。
                logger.info("收到停止信号，正在关闭...")
                try {
                    BiliBiliBot.stop()
                    logger.info("Bot 已正常停止")
                } catch (e: Exception) {
                    logger.error("关闭过程中发生错误: ${e.message}", e)
                }
            },
        )

        // 只有明确进入运行态后才永久等待；启动失败必须交还非零退出状态给外部守护。
        when (BiliBiliBot.start(enableDebug)) {
            BotStartResult.STARTED -> Thread.currentThread().join()
            BotStartResult.FAILED -> exitProcess(1)
        }
    } catch (_: InterruptedException) {
        logger.info("程序被中断")
    } catch (e: Exception) {
        logger.error("程序运行异常: ${e.message}", e)
        exitProcess(1)
    }
}
