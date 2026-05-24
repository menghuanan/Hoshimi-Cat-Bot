# 故障记录与根因

本文件记录已确认的故障案例、根因和处理约束。不要把猜测写成已发生事故；未确认项应放到 [`../bugs.md`](../bugs.md) 或 [`../context/known-issues.md`](../context/known-issues.md)。

## INC-001: Skia native 资源长期运行风险

**状态**：已形成架构约束  
**范围**：Skia 绘图、字体、图片缓存  

**现象**：Skiko `Managed` 对象包装 native 资源，JVM heap 指标无法完整反映 native 内存压力。

**根因**：仅依赖 GC/finalizer 无法保证 native 资源及时释放。

**处理**：

- 绘图统一进入 `SkiaManager.executeDrawing`。
- 单次绘图使用 `DrawingSession` 追踪资源。
- `SkiaCleanupTasker` 周期和阈值清理 Skia/图片/段落缓存。
- Docker 和裸机默认启用软件渲染与 jemalloc。

**防复发文档**：[`../architecture/decisions/adr-002-skia-lifecycle.md`](../architecture/decisions/adr-002-skia-lifecycle.md)

## INC-002: 平台 adapter 边界迁移风险

**状态**：已形成架构约束  
**范围**：OneBot11/NapCat/LlBot/QQ 官方平台  

**现象**：多平台适配过程中，如果业务层直接依赖 vendor 类型，会导致平台替换困难。

**根因**：早期 OneBot11/NapCat 语义容易泄露到业务层。

**处理**：

- `PlatformConnectorManager` 统一创建和持有 adapter。
- 业务层使用 `PlatformContact`、`OutgoingPart` 和 capability guard。
- deprecated Long 群号入口只作为迁移兼容。

**防复发文档**：[`../architecture/decisions/adr-001-platform-adapter.md`](../architecture/decisions/adr-001-platform-adapter.md)

## INC-003: 长时间静默场景 heap committed 高水位

**状态**：已通过启动参数缓解  
**范围**：Docker 与平台发行包启动参数  

**现象**：长时间静默后，JVM committed heap 可能保持高位。

**处理**：

- 启用 `-XX:G1PeriodicGCInterval=60000`。
- 启用 `-XX:G1PeriodicGCSystemLoadThreshold=0`。
- 设置 `MinHeapFreeRatio=10`、`MaxHeapFreeRatio=20`。
- 保持 `-Xms64m -Xmx160m` 默认策略。

**防复发文档**：[`memory-tuning.md`](memory-tuning.md)

