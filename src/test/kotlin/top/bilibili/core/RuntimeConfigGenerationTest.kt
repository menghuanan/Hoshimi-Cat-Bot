package top.bilibili.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import top.bilibili.BiliConfig
import top.bilibili.BiliDataWrapper
import top.bilibili.config.BotConfig
import top.bilibili.webui.model.WebUiConfigFileKind

class RuntimeConfigGenerationTest {
    /**
     * 运行代际同时携带旧快照和候选快照，失败回滚只能依赖这两个 manager-owned 对象。
     */
    @Test
    fun `runtime generation should carry old and candidate snapshots for rollback`() {
        val old = RuntimeConfigSnapshot(
            biliConfig = BiliConfig(admin = 1L),
            biliData = BiliDataWrapper(dataVersion = 4),
            botConfig = BotConfig(firstRunFlag = 1),
        )
        val candidate = old.copy(biliConfig = BiliConfig(admin = 2L))

        val generation = RuntimeConfigGeneration(
            oldSnapshot = old,
            candidateSnapshot = candidate,
            changedFiles = setOf(WebUiConfigFileKind.BILI_CONFIG),
        )

        assertEquals(1L, generation.oldSnapshot.biliConfig.admin)
        assertEquals(2L, generation.candidateSnapshot.biliConfig.admin)
        assertEquals(setOf(WebUiConfigFileKind.BILI_CONFIG), generation.changedFiles)
    }

    /**
     * 运行快照 deepCopy 必须复制可变配置对象，避免候选 mutation 污染失败回滚基线。
     */
    @Test
    fun `runtime snapshots should not share mutable config objects`() {
        val old = RuntimeConfigSnapshot(
            biliConfig = BiliConfig(admin = 1L),
            biliData = BiliDataWrapper(dataVersion = 4),
            botConfig = BotConfig(firstRunFlag = 1),
        )

        val copy = old.deepCopy()

        assertEquals(old, copy)
        assertNotSame(old.biliConfig, copy.biliConfig)
        assertNotSame(old.biliData, copy.biliData)
        assertNotSame(old.botConfig, copy.botConfig)
    }
}
