# Tasker 模块

## 模块定位

Tasker 模块负责所有后台常驻任务，包括平台消息监听、B 站动态/直播轮询、消息生成、发送队列、缓存清理、日志清理、Skia 清理和进程守护。

## 代码入口

- `src/main/kotlin/top/bilibili/tasker/BiliTasker.kt`
- `src/main/kotlin/top/bilibili/tasker/BiliCheckTasker.kt`
- `src/main/kotlin/top/bilibili/service/TaskBootstrapService.kt`
- `src/main/kotlin/top/bilibili/core/resource/TaskResourcePolicyRegistry.kt`
- `src/main/kotlin/top/bilibili/tasker/ProcessGuardian.kt`

## 主要职责

- 用 `BiliTasker` 提供结构化协程生命周期。
- 用 `TaskBootstrapService` 固定启动顺序。
- 用 `TaskResourcePolicyRegistry` 强制新增任务声明资源策略。
- 用 `launchManagedWorker` 管理长生命周期子循环并支持自愈。
- 用 `ProcessGuardian` 监控任务健康、内存、平台连接、平台入站/出站 pressure 和资源快照。

## 当前启动顺序

`TaskBootstrapService` 当前启动：

1. `ListenerTasker`
2. `DynamicCheckTasker`
3. `LiveCheckTasker`
4. `LiveCloseCheckTasker`
5. `DynamicMessageTasker`
6. `LiveMessageTasker`
7. `SendTasker`
8. `CacheClearTasker`
9. `LogClearTasker`
10. `SkiaCleanupTasker`
11. `ProcessGuardian`

## 当前任务职责表

| Tasker | 类型 | 主要职责 | 关键资源 |
| --- | --- | --- | --- |
| `ListenerTasker` | 长生命周期 worker | 独立消费平台 `eventFlow`，对群消息执行统一链接匹配、策略判断和响应 | 平台入口、链接解析 service |
| `DynamicCheckTasker` | 周期轮询 | 检查动态更新，支持手动 `/check` | 共享 `BiliClient`、`dynamicChannel` |
| `LiveCheckTasker` | 周期轮询 | 检查开播状态 | 共享 `BiliClient`、`liveChannel` |
| `LiveCloseCheckTasker` | 周期轮询 | 检查下播状态 | 共享 `BiliClient`、`liveChannel` |
| `DynamicMessageTasker` | channel consumer | 把动态数据构造成业务消息 | `dynamicChannel`、绘图/模板链路 |
| `LiveMessageTasker` | channel consumer | 把直播状态构造成业务消息 | `liveChannel`、模板链路 |
| `SendTasker` | channel consumer + 内部队列 | 选择模板、渲染消息段、执行平台发送和降级 | `messageChannel`、容量 100 的发送队列、推送统计 |
| `CacheClearTasker` | 周期维护 | 清理图片缓存 | `ImageCache` |
| `LogClearTasker` | 周期维护 | 清理日志文件 | `logs/*` |
| `SkiaCleanupTasker` | 周期维护 | 触发 Skia 普通或紧急清理 | `SkiaManager`、Skia native cache |
| `ProcessGuardian` | 周期守护 | 汇总健康、资源、平台、NMT/RSS、channel 和 tasker 自愈 | 管理/观测快照，不直接替代资源 owner |

命令和快捷消息由 `BiliBiliBot.eventCollectorJob` 消费同一稳定 `eventFlow`，再交给 `MessageEventDispatchService`；不要把这条分发链误写成 `ListenerTasker` 消费 `messageChannel`。`PushStatistics` 是进程内有界辅助状态，维护当日推送计数和最近 4 条记录，不是独立 Tasker，也不需要单独停机分区。

## 关键流程

启动阶段由 `TaskBootstrapService` 按固定顺序创建并启动 tasker，每个 tasker 启动前必须能从 `TaskResourcePolicyRegistry` 查到资源策略。长生命周期循环通过 `launchManagedWorker` 接入自愈和退避；停机时由 `ResourceSupervisor` 按资源分区回收，tasker 不能自行绕过分区顺序。

## 生命周期规则

- `BiliTasker.start()` 在停机阶段会拒绝启动。
- 同一 tasker 已运行时重复 `start()` 会被拒绝。
- 启动前必须能从 `TaskResourcePolicyRegistry` 查到策略。
- 周期任务异常会记录连续失败次数，达到 10 次后停止任务。
- `interval == -1` 表示一次性任务。
- 受管 worker 失败后按 `ConnectionBackoffPolicy` 退避重启。

## 资源约束

`ResourceStrictness` 当前分为：

- `STRICT`：停止超时 10 秒，业务硬超时 120 秒。
- `RELAXED_LONG_RUNNING`：停止超时 60 秒，无业务硬超时，适合持续监听或 channel 消费。

新增 Tasker 必须根据是否长生命周期选择合适 strictness。

## 资源与生命周期

Tasker 拥有后台协程、轮询循环、channel 消费者、发送队列、缓存清理和进程守护采样的运行期生命周期。新增 worker、队列、共享 client 或定时器必须接入 `BiliTasker`、`launchManagedWorker`、`TaskResourcePolicyRegistry` 和停机退出路径，不能把资源藏在局部单例或裸协程里。

## 禁止事项

- 禁止新增未登记资源策略的 Tasker。原因：启动时会失败，停机和监控也无法识别资源边界。
- 禁止裸 `GlobalScope.launch`。原因：无法被 `BiliBiliBot` 根 Job 和 `ResourceSupervisor` 回收。
- 禁止在停机阶段创建共享客户端。原因：停机分区已开始回收，重新拉起会造成资源泄漏。
- 禁止在主循环中新增无界阻塞 IO。原因：会导致 channel 背压和停机超时。

## 与 channel 的关系

`BiliBiliBot` 持有：

- `dynamicChannel`：容量 20。
- `liveChannel`：容量 20。
- `messageChannel`：容量 20。

`SendTasker` 内部还有容量 100 的发送队列。新增生产者必须考虑这些容量限制和停机退出路径。

当前 `ProcessGuardian` 不读取这四个本地队列的填充度；实时背压告警只来自平台 runtime status 的 inbound/outbound pressure 与 dropped 计数。本地容量只用于内存估算，观测缺口见 [`../context/known-issues.md#ki-005-本地队列与-skia-native-压力存在观测盲区`](../context/known-issues.md#ki-005-本地队列与-skia-native-压力存在观测盲区)。

## 配置与数据

Tasker 可以读取轮询间隔、订阅数据、平台状态和缓存策略，但不直接写 YAML。需要持久化订阅、模板策略或链接解析状态时，必须通过 service/config 的既有入口；新增 tasker 配置必须说明默认值、热更新语义和停机时的读取行为。

WebUI 热重载应用 `BiliConfig.yml` 后，读取启动期缓存的 Tasker 必须通过显式 refresh 方法同步运行时配置。当前至少覆盖 `BiliCheckTasker`、`LiveCheckTasker`、`LiveCloseCheckTasker` 和 `CacheClearTasker` 的轮询间隔、报告间隔、低频时段和缓存过期策略；新增 Tasker 若缓存配置值，也必须加入热重载刷新路径和回归测试。

## 测试与验证

- 新增或修改 tasker 生命周期后，运行 `BiliTaskerRegressionTest`、`TaskLifecycleBoundaryRegressionTest` 和 `TaskSelfHealingTest`。
- 修改 channel 生产/消费、发送队列或 worker 自愈后，运行对应 message、send 或 ProcessGuardian 测试。
- 修改启动顺序或资源策略后，检查 `TaskBootstrapService`、`TaskResourcePolicyRegistry` 和 [`../architecture/invariants.md`](../architecture/invariants.md) 是否一致。

## 查询 checklist

- [ ] 是否已阅读根目录 `AGENTS.md` 与 `docs/AGENTS.md`？
- [ ] 是否确认查询对象属于本模块，而不是相邻模块、历史计划或过期文档？
- [ ] 是否阅读本文档列出的代码入口、禁止事项和相关 domain/architecture 文档？
- [ ] 是否区分当前实现、阶段性计划和过期记录？
## 变更 checklist

- [ ] 新 tasker 是否加入 `TaskBootstrapService.startupTaskNames`？
- [ ] 是否加入 `TaskResourcePolicyRegistry`？
- [ ] 长生命周期子循环是否使用 `launchManagedWorker`？
- [ ] 停机期间是否能快速退出？
- [ ] 是否更新 [`../architecture/invariants.md`](../architecture/invariants.md) 中相关约束？
- [ ] 是否运行 tasker 相关测试，如 `BiliTaskerRegressionTest`、`TaskLifecycleBoundaryRegressionTest`、`TaskSelfHealingTest`？
## 新建 checklist

- [ ] 新文件是否优先归入本模块既有入口，而不是新增顶层包？
- [ ] 新函数、方法或逻辑块是否补充紧邻注释，说明用途、意图或关键约束？
- [ ] 新配置、数据结构、资源、协程、客户端、缓存、channel 或 native 对象是否有明确生命周期和归属边界？
- [ ] 新外部行为是否同步更新相关 domain、architecture、development 或 operations 文档？
- [ ] 新测试是否只验证源码行为或产物，不复制项目文档内容？
