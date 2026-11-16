package top.bilibili.tasker

import top.bilibili.core.BiliBiliBot

/** 群目录刷新复用受管任务生命周期，网络权限失败由服务降级，不阻断 Bot 启动。 */
object PlatformGroupDirectoryTasker : BiliTasker("PlatformGroupDirectoryTasker") {
    override var interval: Int = 3

    /** 初始化只加载本地缓存，启动阶段不等待官方白名单接口。 */
    override fun init() { BiliBiliBot.groupDirectory.initialize() }

    /** 单 tick 最多查询一个群，自动保存合并后的事件与资料快照。 */
    override suspend fun main() { BiliBiliBot.groupDirectory.tick() }
}
