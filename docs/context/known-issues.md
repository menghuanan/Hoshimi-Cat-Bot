# 已知问题、处理状态与临时绕过

_最后更新：2026-07-11_

本文只维护运行人员需要知道的活跃症状、影响和临时绕过；没有运行期症状的开发迁移或架构债务只登记在 [`../bugs.md`](../bugs.md)。确认修复时必须同步关闭关联 BUG/KI 条目。

## KI-001: Skia worker process 配置尚未落地

**工程登记**：[`BUG-001`](../bugs.md#bug-001-skia-worker-process-配置与实际实现不一致)。

**现象**：`SkiaConfig` 暴露 worker process 配置，但 `SkiaManager` 当前实际模式为 `IN_PROCESS`，`WORKER_PROCESS` 分支未实现。

**影响**：配置项不能提供进程隔离；如果代码路径显式选择 `WORKER_PROCESS`，会抛出 `UnsupportedOperationException`，不能依赖 worker 崩溃隔离或独立回收 native 内存。

**临时绕过**：按 `IN_PROCESS` 模式规划容量和故障恢复；不要尝试切换到 `WORKER_PROCESS`，内存压力继续通过 software rendering、cleanup tasker、NMT 与 RSS 监控处理。

**后续处理**：实现 worker process、切换逻辑和隔离回归测试后，再把该能力标记为可用。

## KI-003: 启动失败可能不会结束进程

**工程登记**：[`BUG-005`](../bugs.md#bug-005-启动失败状态没有稳定传递到进程退出码)。

**现象**：`BiliBiliBot.start()` 在主配置初始化失败或平台配置无效时会把生命周期设为 `STOPPED` 后返回；`Main.main()` 随后仍无条件执行 `Thread.currentThread().join()`。

**影响**：进程可能保持存活，但 Bot、平台连接和 Tasker 并未进入运行态；外部守护只看 PID 时会误判启动成功。

**临时绕过**：启动后同时检查“Bot 启动成功”日志、平台连接和 Tasker 状态。命中失败日志时主动终止进程并修复配置。

**后续处理**：让启动入口返回明确结果或抛出异常，并由 `Main` 以非零状态退出。

## KI-004: Skia purge 未保持在清理闸门窗口内

**工程登记**：[`BUG-006`](../bugs.md#bug-006-skia-cache-purge-不在完整清理闸门内)。

**现象**：`DrawingQueueManager.awaitAllCompleted()` 使用空 block 等待 active 绘图后释放 `isCleaning`；`SkiaManager` 随后才重置 paragraph cache、purge Skia cache 和清理图片缓存。

**影响**：等待结束与实际 purge 之间可能有新绘图进入 active，当前实现不能保证清理全过程与绘图互斥。

**临时绕过**：保持清理由 `SkiaCleanupTasker` 在空闲或低负载窗口触发，排障时结合队列 active/pending 状态判断风险。

**后续处理**：把全局缓存清理 block 放入 `runExclusiveCleanup()`，并补并发回归测试。

## KI-005: 本地队列与 Skia native 压力存在观测盲区

**工程登记**：[`BUG-007`](../bugs.md#bug-007-本地业务队列和-skia-native-压力缺少直接快照)。

**现象**：`ProcessGuardian.checkChannelBackpressure()` 只读取平台 inbound/outbound pressure 和 dropped 计数；`dynamicChannel`、`liveChannel`、`messageChannel` 与 `SendTasker` 内部队列没有填充度快照。`SkiaManagerStatus.memoryUsage` 还是 JVM heap 使用率，不是 Skia native heap。

**影响**：守护日志不能直接证明本地业务 channel 或发送队列是否接近满载，也不能用 `memoryUsage` 单独判断 Skia native 泄漏。

**临时绕过**：结合 Tasker 健康、平台丢弃计数、Skia pending/active、Native Memory Tracking（NMT）和 Linux Resident Set Size（RSS）趋势判断。

**后续处理**：为本地 channel、发送队列和 Skia native 指标增加只读快照。

## KI-007: 群管理员可修改任意已有分组的模板策略

**工程登记**：[`BUG-009`](../bugs.md#bug-009-群管理员可写入不属于当前群的分组模板策略)。

**现象**：`PresentationCommandService.handleTemplate()` 只校验发送者是当前群管理员；`TemplateService.resolveScope()` 校验分组存在且已订阅 UID，但不校验当前群属于目标分组。

**影响**：普通群管理员可以通过 `template ... group <分组名>` 修改不包含当前群的共享 `groupRef` 模板策略。

**临时绕过**：在修复前只向可信管理员授予群管理员权限，并避免向其公开其他分组名。

**后续处理**：在命令入口或 scope 解析处校验当前群属于目标分组，并补权限回归测试。
