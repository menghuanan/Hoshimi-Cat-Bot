# 实现与文档偏差登记

_最后更新：2026-07-11_

本文件是工程偏差及其处理状态的登记入口，记录源码、架构约束与维护文档之间已确认的不一致。面向运行人员的症状、影响和临时绕过维护在 [`context/known-issues.md`](context/known-issues.md)；活跃条目通过 BUG/KI 编号互链，不复制同一类说明。

## BUG-001: Skia worker process 配置与实际实现不一致

**状态**：已关闭。

**现象**：`SkiaConfig.enableWorkerProcess` 默认值为 `true`，并存在 worker 进程相关配置项；但 `SkiaManager.currentMode` 当前固定初始化为 `IN_PROCESS`，`WORKER_PROCESS` 分支会抛出 `UnsupportedOperationException("Worker process mode not implemented yet")`。

**处理**：删除未实现的 worker process 配置、模式枚举与异常分支，`SkiaManager` 只暴露实际可用的 in-process 绘图路径。

## BUG-002: SendTasker 存在疑似乱码注释（已修复）

**状态**：已关闭。

**现象**：`src/main/kotlin/top/bilibili/tasker/SendTasker.kt` 中曾存在乱码注释，疑似原意为“发送间隔”。

**处理**：已按 UTF-8 读取并将乱码注释修复为正常中文说明，未修改发送逻辑。

## BUG-003: 当前文档体系新入口与根 AGENTS.md 可能被误解为同级（已修复）

**状态**：已关闭。

**现象**：本轮新增 `docs/AGENTS.md`，但根目录已存在仓库级 `AGENTS.md`。

**影响**：AI 或人工开发者可能只读 `docs/AGENTS.md` 而跳过根目录强制规范。

**处理**：`docs/AGENTS.md` 与根目录 `AGENTS.md` 均已明确声明根目录 `AGENTS.md` 优先，且不得删除、覆盖或弱化。

## BUG-004: deprecated Long 联系人入口仍处于迁移期

**状态**：已关闭。

**偏差**：平台中立边界已经要求使用 `PlatformContact`，但 `BiliBiliBot`、`PlatformAdapter` 和 `PlatformCapabilityService` 仍保留 Long 群号/私聊兼容入口。

**处理**：移除 `BiliBiliBot`、`PlatformAdapter`、`PlatformConnectorManager`、`PlatformCapabilityService` 和 `MessageGateway` 的数字联系人兼容入口；OneBot11 数字 ID 只在协议实现内部解析和探测，并由平台边界回归测试约束。

## BUG-005: 启动失败状态没有稳定传递到进程退出码

**状态**：已关闭。

**偏差**：`BiliBiliBot.start()` 可以记录失败并返回 `STOPPED`，`Main.main()` 随后仍进入无条件 `join()`，与启动层应暴露失败状态的目标不一致。

**处理**：`BiliBiliBot.start()` 返回 `BotStartResult`，`Main` 只在 `STARTED` 时等待，`FAILED` 时以状态码 1 退出；已补启动入口回归测试。

## BUG-006: Skia cache purge 不在完整清理闸门内

**状态**：已关闭。

**偏差**：`awaitAllCompleted()` 在空 block 结束后释放 `isCleaning`，`SkiaManager` 随后才执行 paragraph、Skia 和图片 cache 清理，无法保证清理全过程与新绘图互斥。

**处理**：普通与紧急 cache purge 均移入 `runExclusiveCleanup()` block；闸门等待超时时跳过本轮 purge，不再失去互斥后强制清理，并补并发与源码契约测试。

## BUG-007: 本地业务队列和 Skia native 压力缺少直接快照

**状态**：已关闭。

**偏差**：`ProcessGuardian` 的实时背压检测只读取平台 pressure；三条业务 channel 与 `SendTasker` 队列没有填充度快照，`SkiaManagerStatus.memoryUsage` 也只是 JVM heap 比例。

**处理**：三条 core Channel 与 `SendTasker` 队列改用有界可观测包装器，`ProcessGuardian` 输出填充率并在 80% 时告警；Skia 状态新增 `Graphics.resourceCacheTotalUsed` 字节数，并继续结合 NMT/RSS 指标判断 native 压力。

## BUG-008: Utils 管理员通知跨越平台发送边界

**状态**：已关闭。

**偏差**：`utils/General.kt` 的 `actionNotify()` 直接依赖 capability service 与 message gateway，违反 utils 低依赖且不承载消息发送的层边界。

**处理**：新增 service 层 `AdminNoticeService`，统一承担通知格式化、开关/能力判断和 message gateway 发送；全部调用方改用 service 入口，utils 不再依赖发送能力。

## BUG-009: 群管理员可写入不属于当前群的分组模板策略

**状态**：已关闭。

**偏差**：模板命令只校验当前群管理员身份以及目标分组存在并订阅 UID，没有校验当前群属于目标分组，弱于文档规定的群内权限边界。

**处理**：`TemplateService` 默认校验当前群属于目标分组，只有超级管理员入口显式允许跨分组维护；已补跨群拒绝测试。

## BUG-010: 平台中立迁移仍残留 OneBot11/NapCat 命名与依赖

**状态**：已关闭。

**偏差**：`SendTasker` KDoc 仍描述“转换为 OneBot v11 并通过 NapCat 发送”，`ConversationStateStore` KDoc 仍写成 NapCat 会话状态；`service/MessageLogSimplifier` 还直接委托 `connector.onebot11.core.OneBot11MessageLogSimplifier`。当前发送路径已经走平台中立 `OutgoingPart`、capability guard 和 message gateway，这些命名会误导后续维护者把 vendor 逻辑重新带回 service/tasker。

**处理**：修正 `SendTasker` 与 `ConversationStateStore` 的平台特定 KDoc；通用日志简化实现迁到 connector 根包，OneBot11 core 仅保留协议模型适配，service 不再反向依赖 OneBot11 core。

## BUG-011: 两个绘图基线测试源码不是严格 UTF-8

**状态**：已关闭。

**偏差**：`src/test/kotlin/top/bilibili/draw/DrawLabelCardBaselineTest.kt` 与 `src/test/kotlin/top/bilibili/draw/DynamicMediaLabelBaselineTest.kt` 当前无法通过启用异常回退的 UTF-8 解码器读取，违反仓库所有文件读写使用 UTF-8 的约束。本轮只修缮文档，未改写这两个测试文件。

**处理**：按原 GB18030 字符内容无损转为 UTF-8（无 BOM），保留测试语义；两个基线测试与严格 UTF-8 解码检查均通过。

## BUG-012: Metaspace 上限接近当前运行峰值

**状态**：已关闭，保留长期运行观察，不对应开放 KI。

**偏差**：后续稳定峰值约 45 MB，在 56 MB 上限下约占 80%，持续触发容量告警；同期 G1 周期回收、heap shrink 与默认 `-Xmx160m` 未观察到不足。

**处理**：Docker、Windows 裸机和 Linux 裸机统一使用 `MetaspaceSize=16m`、`MaxMetaspaceSize=64m`；同时把 CodeCache 统一为 32/48 MiB、修复真实上限监控和 80%/75% 告警迟滞。heap 与 G1 参数保持不变；若 Metaspace 后续持续超过约 51 MB，转入动态类加载或 ClassLoader 保留调查。
