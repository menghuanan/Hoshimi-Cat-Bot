# 系统不变量

这些约束描述当前实现中必须持续成立的系统级事实。违反任何不变量前必须先说明原因、影响范围、回滚方式，并经人类确认。

## INV-001: BiliConfigManager 与 ConfigManager 是配置和数据写入边界

**约束**：`BiliConfigManager` 负责 `config/BiliConfig.yml` 与 `config/BiliData.yml` 的加载、迁移和保存；`ConfigManager`/`BotConfigFileStore` 负责 `config/bot.yml` 的加载、迁移和保存。业务服务、Tasker、平台适配器不得直接写这些配置文件。

**为什么**：当前项目同时保留旧主配置和 v1.8 平台配置。写入边界分离可以避免旧字段覆盖新结构，保证迁移、标准化写回和运行态快照一致。

**违反后果**：配置热重载或停机保存可能覆盖用户配置；模板策略、联系人 subject 或平台 adapter 字段可能回退到旧结构。

**相关文档**：[`modules/config.md`](../modules/config.md)、[`development/change-classification.md`](../development/change-classification.md)

## INV-002: Skia native 资源必须绑定 DrawingSession 或明确全局生命周期

**约束**：绘图过程中创建的 `Surface`、`Image`、`Paint`、`Shader`、`TextLine`、`Paragraph`、`SVGDOM` 等 native 资源必须经 `DrawingSession` 创建或调用 `track()`；全局字体资源只能由 `FontManager` 持有并在 `SkiaManager.shutdown()` 时释放。Skiko 对象级 close/cache/no-close 分类以 [`../development/skiko-object-lifecycle.md`](../development/skiko-object-lifecycle.md) 为准。

**为什么**：Skiko `Managed` 对象包装 native 资源，JVM heap 指标无法完整反映 native heap 压力。当前项目通过 `DrawingSession.use`、`SkiaCleanupTasker` 和 `Graphics.purge*` 主动控制生命周期。

**违反后果**：native heap 持续增长、JVM crash、use-after-free、长时间运行后 RSS 无法回落。

**相关 ADR**：[`decisions/adr-002-skia-lifecycle.md`](decisions/adr-002-skia-lifecycle.md)

**相关细则**：[`../development/skiko-object-lifecycle.md`](../development/skiko-object-lifecycle.md)

## INV-003: BiliTasker 子协程崩溃不得静默吞噬

**约束**：长生命周期子循环必须通过 `launchManagedWorker` 注册；Tasker 主循环异常必须由 `BiliTasker` 记录、退避并在连续失败达到阈值后停止。新增 worker 不得裸 `launch` 后无人观测。

**为什么**：`ProcessGuardian` 依赖 `healthSnapshot()` 识别 worker 失效并调用 `recoverUnhealthyWorkers()`。未注册 worker 会绕过健康检查与自愈。

**违反后果**：消息队列、监听循环或发送循环退出后进程仍显示存活，但业务静默停止。

**相关文档**：[`modules/tasker.md`](../modules/tasker.md)

## INV-004: 平台 adapter 生命周期只能由 PlatformConnectorManager 管理

**约束**：业务层只能通过 `BiliBiliBot.requireConnectorManager()`、`MessageGatewayProvider` 或 `PlatformCapabilityService` 访问平台能力，不得直接持有 `NapCatAdapter`、`LlBotAdapter`、`OneBot11Transport` 等实例。

**为什么**：`PlatformConnectorManager` 负责 adapter 创建、缓存、启动、停止和防止停机后隐式重新拉起。绕过它会破坏平台可替换性和生命周期收敛。

**违反后果**：停机后发送链路复活已关闭连接；多平台适配退化为 vendor 分支；能力 guard 失效。

**相关 ADR**：[`decisions/adr-001-platform-adapter.md`](decisions/adr-001-platform-adapter.md)

## INV-005: 停机必须按资源分区阶段逆依赖收敛

**约束**：停机顺序必须保持 `INGRESS -> WORKERS -> CHANNELS -> DEPENDENCIES -> ROOT_SCOPE` 的阶段语义。入口和事件收集器先停，Tasker 和 channel 再停，底层客户端、Skia、缓存最后随依赖关闭。

**为什么**：如果先关闭底层依赖，仍存活的入口或 worker 会继续产生请求；如果先关闭根作用域，分区 stop action 可能无法执行。

**违反后果**：停机卡死、资源泄漏、关闭后的客户端被重试链路复活、消息丢失且无日志。

**相关代码**：`ResourceSupervisor`、`BiliBiliBot.registerResourcePartitions()`

## INV-006: 模板策略写入必须经 TemplateRuntimeCoordinator

**约束**：动态、直播、下播三类模板策略和 last-used、batch 缓存必须由 `TemplateRuntimeCoordinator` 串行读写。命令服务不得直接改 `BiliData.*TemplatePolicyByScope` 后跳过运行态清理。

**为什么**：模板随机、分组批次复用和持久化快照依赖策略表与运行态缓存同步。

**违反后果**：删除模板后仍复用旧缓存；随机策略跨消息串扰；保存到 `BiliData.yml` 的快照不完整。

**相关文档**：[`modules/service.md`](../modules/service.md)

## INV-007: Channel 背压必须保持有界

**约束**：`dynamicChannel`、`liveChannel`、`messageChannel` 和 `SendTasker` 内部发送队列必须保持有界容量；新增生产者要能在停机或背压时退出。

**为什么**：项目是 7x24 常驻进程，无界队列会把平台抖动或 B 站接口回流转化为 heap/RSS 增长。

**违反后果**：消息堆积、ProcessGuardian 背压告警、OOM 或长时间 GC。

**相关文档**：[`operations/monitoring.md`](../operations/monitoring.md)

## INV-008: BiliClient close 后不得复用

**约束**：`BiliClient.close()` 后禁止再次发起请求或创建 retry slot。共享客户端只能通过对应 owner 的关闭入口释放，例如 `BiliCheckTasker.closeSharedClient()`、`closeUtilsClient()`、`closeServiceClient()`。

**为什么**：`BiliClient` 通过 `closed` 状态阻断 close 后“复活”，并由 `ProcessGuardian` 聚合 ownerTag 维度资源快照。

**违反后果**：OkHttp 连接池和 dispatcher 残留；停机后网络资源再次创建；native/task 相关性日志失真。

**相关文档**：[`modules/client.md`](../modules/client.md)

## INV-009: 数据版本迁移只能在加载阶段完成

**约束**：`BiliData` 的旧字段迁移、联系人 subject 归一、模板策略迁移必须在 `BiliConfigManager.loadData()` 链路完成。业务命令不应在运行中执行一次性全量结构迁移。

**为什么**：加载阶段能基于 `dataVersion` 选择 legacy wrapper，并在迁移完成后统一写回。

**违反后果**：运行态和磁盘态版本不一致；旧模板绑定被重新写出；联系人 scope 部分迁移。

**相关文档**：[`modules/config.md`](../modules/config.md)

## INV-010: 链接解析必须经过统一匹配与限流链路

**约束**：消息入口解析链接时必须使用 `matchingAllRegular`/`matchingRegular` 与 `LinkResolvePolicyService`，不得为某个链接类型单独绕过去重、冷却和用户频率限制。

**为什么**：短链接解析、opus 兼容、专栏动态归一和每用户每窗口限制都集中在服务层。

**违反后果**：同一消息重复解析、短链接递归失控、群内刷屏、B 站接口请求峰值放大。

**相关文档**：[`domain/bilibili-api.md`](../domain/bilibili-api.md)

## INV-011: 停机资源分区登记表必须与 `registerResourcePartitions()` 保持一一对应

**约束**：`BiliBiliBot.registerResourcePartitions()` 中登记的分区 ID、owner 列表、strictness 和 phase 必须被视为停机契约。新增长期资源时，必须显式加入现有分区或新增分区；修改 ownerTag、分区 ID 或 phase 时，必须同步更新文档与兜底回收逻辑。

**为什么**：`ResourceSupervisor` 按 `ShutdownPhase` 管理停机，不按“看起来像一类资源”自动归组。AI 或维护者如果只看到 `cancelAll()`、`close()` 之类局部调用，最容易遗漏共享客户端、入口协程或缓存资源的登记，导致 `ProcessGuardian` 和停机收敛路径看不到真实资源边界。

**当前登记表**：

| 分区 ID | ShutdownPhase | owns | strictness | 当前 stopAction 语义 |
| --- | --- | --- | --- | --- |
| `startup-delayed-jobs` | `INGRESS` | `startupDataInitJob`、`startupTaskBootstrapJob` | `RELAXED_LONG_RUNNING` | 取消延迟初始化与延迟启动任务，避免停机期间“边关边起” |
| `gateway-platform` | `INGRESS` | `PlatformConnectorManager`、`MessageGatewayProvider` | `RELAXED_LONG_RUNNING` | 停平台入口、清空 connector manager、注销消息网关 |
| `webui-manager` | `INGRESS` | `WebUiManager` | `RELAXED_LONG_RUNNING` | 取消 WebUI reload job，并关闭当前和待停止的本地 WebUI manager，避免停机期间继续接收新的 HTTP 请求 |
| `webui-config-hot-reload` | `INGRESS` | `WebUiConfigHotReloadCoordinator` | `RELAXED_LONG_RUNNING` | 拒收新保存，等待或取消当前 worker，并把未终态 job 收敛为失败 |
| `event-collector` | `INGRESS` | `eventCollectorJob` | `STRICT` | 取消事件收集协程 |
| `taskers` | `WORKERS` | `BiliTasker.*` | `RELAXED_LONG_RUNNING` | 调用 `BiliTasker.cancelAll(timeoutMs = 10_000)` 统一停止后台任务 |
| `channels` | `CHANNELS` | `dynamicChannel`、`liveChannel`、`messageChannel` | `STRICT` | 关闭三条消息通道 |
| `skia-manager` | `DEPENDENCIES` | `SkiaManager`、`FontManager` | `RELAXED_LONG_RUNNING` | 执行 `SkiaManager.shutdown()` |
| `bili-client` | `DEPENDENCIES` | `biliClient` | `STRICT` | 执行 `closeUtilsClient()` |
| `service-bili-client` | `DEPENDENCIES` | `service.client` | `STRICT` | 执行 `closeServiceClient()` |
| `check-tasker-bili-client` | `DEPENDENCIES` | `BiliCheckTasker.client` | `STRICT` | 执行 `BiliCheckTasker.closeSharedClient()` |
| `image-cache` | `DEPENDENCIES` | `ImageCache` | `STRICT` | 关闭图片缓存 |
| `scope-job` | `ROOT_SCOPE` | `BiliBiliBot.job` | `RELAXED_LONG_RUNNING` | 在 10 秒内取消并等待根协程作用域停止，超时后强制取消 |

**维护要求**：

- 新增共享客户端、全局缓存、入口协程、事件收集器或常驻 worker 时，不得只写 `close()`/`cancel()`；必须先决定它属于哪个分区。
- 新增本地管理面、调试入口或其他可选 ingress 时，必须和主消息入口一样显式登记停机分区，不能只在 `stop()` 里临时补一个 `close()`。
- 修改 `owns` 中的 ownerTag 命名时，必须同步检查 `BusinessLifecycleManager`、`ProcessGuardian` 和相关日志，否则资源快照会失真。
- 新分区的 `ShutdownPhase` 必须按依赖方向选择，不能为了“更早释放内存”把底层依赖提前到 `INGRESS` 或 `WORKERS`。
- `fallbackStopResources()` 是资源总管失效时的兜底路径；只更新分区登记、不更新兜底顺序，会让异常停机场景与正常停机场景出现分叉。

**相关代码**：`BiliBiliBot.registerResourcePartitions()`、`BiliBiliBot.fallbackStopResources()`、`ResourceSupervisor`
