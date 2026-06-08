package top.bilibili.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import top.bilibili.BiliConfig
import top.bilibili.BiliDataWrapper
import top.bilibili.config.BotConfig
import top.bilibili.deepCopy
import top.bilibili.utils.json
import top.bilibili.webui.model.WebUiConfigFileKind

/**
 * 运行代际快照只保存 manager-owned 配置对象，不持有 connector、WebUI server 或 tasker 实例。
 */
data class RuntimeConfigSnapshot(
    val biliConfig: BiliConfig,
    val biliData: BiliDataWrapper,
    val botConfig: BotConfig,
)

/**
 * 双代际热重载以旧快照和候选快照为边界，确保 apply 失败时可以恢复旧运行态。
 */
data class RuntimeConfigGeneration(
    val oldSnapshot: RuntimeConfigSnapshot,
    val candidateSnapshot: RuntimeConfigSnapshot,
    // 文件边界由 WebUI 保存请求传入，core applier 只能刷新本次真正变更的运行切片。
    val changedFiles: Set<WebUiConfigFileKind>,
)

/**
 * 运行代际快照复制必须深拷贝配置对象，避免候选 mutation 污染失败回滚基线。
 */
fun RuntimeConfigSnapshot.deepCopy(): RuntimeConfigSnapshot {
    return RuntimeConfigSnapshot(
        biliConfig = biliConfig.deepCopyForRuntimeSnapshot(),
        biliData = biliData.deepCopy(),
        botConfig = botConfig.deepCopyForRuntimeSnapshot(),
    )
}

/**
 * 运行配置快照使用结构化序列化 round-trip，避免 plain copy 共享 nested mutable map/list。
 */
fun BiliConfig.deepCopyForRuntimeSnapshot(): BiliConfig {
    return runtimeSnapshotJson.decodeFromString(
        BiliConfig.serializer(),
        runtimeSnapshotJson.encodeToString(BiliConfig.serializer(), this),
    )
}

/**
 * bot.yml 快照同样通过结构化 round-trip 复制，确保 targets/admins 等可变集合不共享。
 */
fun BotConfig.deepCopyForRuntimeSnapshot(): BotConfig {
    return runtimeSnapshotJson.decodeFromString(
        BotConfig.serializer(),
        runtimeSnapshotJson.encodeToString(BotConfig.serializer(), this),
    )
}

/**
 * runtime snapshot JSON 是热重载控制面工具，不在渲染或轮询热路径中重复创建。
 */
private val runtimeSnapshotJson = Json(json) {
    encodeDefaults = true
}
