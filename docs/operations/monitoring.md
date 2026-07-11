# 监控指标与告警阈值

本文记录当前项目内置监控来源、指标和阈值。主要实现位于 `ProcessGuardian`。

## 监控入口

`ProcessGuardian` 是一个 `BiliTasker`，默认每 30 秒检查一次。

日志目录：

```text
logs/daemon/Daemon_<yyyy-MM-dd>.log
```

普通应用日志：

- `logs/bilibili-bot.log`
- `logs/error.log`

Logback 为主日志和错误日志配置 30 天滚动上限。`LogClearTasker` 还会每 7 天删除命中的普通滚动日志和守护日志；它当前不匹配 `error.YYYY-MM-DD.log`，因此实际保留不是一个统一天数。

`ProcessGuardian` 每 30 秒检查一次，正常守护报告约每 10 分钟写入一次；出现异常信号时可以提前触发 NMT 采样，不必等待固定 10 分钟采样间隔。

## Tasker 健康

监控内容：

- 已注册 tasker 数量。
- 主任务 active 状态。
- worker active 状态。
- worker 最近失败原因。
- worker 重启预算是否耗尽。

异常处理：

- 对仍存活但 worker 不健康的 tasker 调用 `recoverUnhealthyWorkers()`。
- 已终止 tasker 由僵尸清理逻辑处理。

## Heap 阈值

`ProcessGuardian` 当前阈值：

- warning：70%
- critical：85%

critical 会触发紧急清理。

## 非堆阈值

当前限制口径：

- Metaspace：48 MB。
- CodeCache：32 MB。
- 非堆 warning threshold：80%。

监控会记录：

- NON_HEAP 分区。
- Metaspace/CodeCache 总量。
- 突发增长。
- 预热完成后的长期增长趋势。
- 增长回落日志。

## Native/NMT 采样

常规采样策略：

- `NATIVE_MEMORY_SAMPLE_INTERVAL_MS=10分钟`
- `NATIVE_MEMORY_COMMAND_TIMEOUT_MS=5秒`
- 通过 `jcmd VM.native_memory summary` 可降级采样。

如果环境不支持 NMT，应记录为 `UNAVAILABLE`，不要让守护任务失败。

守护快照还采集 Linux `/proc/self/status`、`smaps_rollup`、BufferPool、线程、受管协程、业务 owner、native 增量和 `max(VmRSS - NMT committed, 0)` 未归类驻留估算。环境不支持的字段必须标记为不可用，不能用替代指标伪装成同一口径。

## RSS 软限制

当前默认：

- `RSS_SOFT_LIMIT_MB=300`
- hold：30 分钟
- warning after：10 分钟
- restart exit code：78

满足条件后会写入守护日志，再执行退出。

## 平台背压与本地队列

当前实时背压检测对象只有：

- 平台 outbound pressure 与 dropped 计数
- 平台 inbound pressure 与 dropped 计数

`dynamicChannel`、`liveChannel`、`messageChannel` 的容量均为 20，`SendTasker` 内部发送队列容量为 100；当前只用固定容量做内存估算，没有暴露实时填充度。守护日志没有背压告警时，不能据此证明本地队列没有积压。

出现背压时建议优先检查：

- 平台发送是否失败或降级。
- B 站轮询是否短时间产生大量消息。
- `SendTasker` 是否仍健康。
- 平台连接是否断开。

观测缺口见 [`../context/known-issues.md#ki-005-本地队列与-skia-native-压力存在观测盲区`](../context/known-issues.md#ki-005-本地队列与-skia-native-压力存在观测盲区)。

## 平台连接观测

`PlatformConnectorManager.runtimeStatus()` 和 `runtimeObservability()` 提供：

- connected。
- reconnectAttempts。
- inbound/outbound pressure。
- transport client 快照。
- WebSocket session 状态。

## BiliClient 观测

`BiliClient.runtimeSnapshot()` 提供：

- 累计创建实例数。
- 活跃实例数。
- retry slot 容量和已创建数量。
- 每个 slot 的 connection/idle/queued/running。

## Skia 观测

`SkiaManager.getStatus()` 提供：

- mode。
- memoryUsage。
- totalDrawingCount。
- totalCleanupCount。
- queue pending/active/full。
- uptimeMs。

其中 `memoryUsage` 来自 JVM `Runtime` 的 heap 使用率，不是 Skia native heap。判断 native 压力时必须结合 NMT、RSS、Graphics cache 和队列状态。

## 告警处理原则

- 先看 `logs/error.log` 是否有同一时间窗口异常。
- 再看 `logs/daemon/Daemon_*.log` 的资源快照。
- 资源问题优先判断是 heap、non-heap、native、RSS、平台 pressure 还是本地队列积压。
- 不要只根据单一 RSS 数值调整 heap；需要结合 NMT、Skia、BiliClient 和平台连接快照。
- 按症状执行的证据收集和恢复步骤见 [`troubleshooting.md`](troubleshooting.md)。
