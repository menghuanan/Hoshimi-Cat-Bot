# Skia 模块

**覆盖度**：核心流程 ✅ | 完整参数 ✅ | 边界情况 ⚠️

## 模块定位

Skia 模块负责图片绘制的并发控制、会话级 native 资源管理、全局缓存清理和字体资源释放。它是项目内最容易产生 native 内存问题的模块。

## 代码入口

- `src/main/kotlin/top/bilibili/skia/SkiaManager.kt`
- `src/main/kotlin/top/bilibili/skia/DrawingSession.kt`
- `src/main/kotlin/top/bilibili/skia/DrawingQueueManager.kt`
- `src/main/kotlin/top/bilibili/skia/SkiaConfig.kt`
- `src/main/kotlin/top/bilibili/tasker/SkiaCleanupTasker.kt`
- `src/main/kotlin/top/bilibili/draw/FontManager.kt`

## 主要职责

- `SkiaManager.executeDrawing`：统一绘图入口。
- `DrawingQueueManager`：限制并发、队列长度和单次绘图超时。
- `DrawingSession`：追踪并关闭会话内 native 资源。
- `SkiaCleanupTasker`：空闲或周期触发普通清理，临界内存阈值触发紧急清理，警告阈值只记录告警。
- `FontManager`：管理全局字体对象生命周期。

## 关键流程

业务绘图只能通过 `SkiaManager.executeDrawing` 入队，由 `DrawingQueueManager` 控制并发、队列和超时，再在 `DrawingSession` 内创建并追踪 native 对象。普通清理由空闲检测或周期任务触发，紧急清理由临界内存阈值触发；业务绘图路径不能各自拼装清理顺序。

## 当前配置

`SkiaConfig` 的参数应视为完整清单。下面的表必须与 `src/main/kotlin/top/bilibili/skia/SkiaConfig.kt` 保持一致。

| 参数 | 默认值 | 当前状态 | 约束/说明 |
| --- | --- | --- | --- |
| `maxQueueSize` | `20` | 生效中 | 必须 `> 0`；超过后绘图请求会被拒绝 |
| `maxConcurrent` | `2` | 生效中 | 必须 `> 0`；控制并发绘图数 |
| `idleTimeoutMs` | `60_000` | 生效中 | 必须 `> 0`；空闲超时后可清理资源 |
| `cleanupIntervalMs` | `300_000` | 生效中 | 必须 `> 0`；定期清理间隔 |
| `drawingTimeoutMs` | `60_000` | 生效中 | 必须 `> 0`；单次绘图超时 |
| `memoryWarningThreshold` | `0.70` | 生效中 | 必须在 `0.0..1.0`；比较的是当前 JVM heap 比例，达到后只记录警告 |
| `memoryCriticalThreshold` | `0.85` | 生效中 | 必须在 `0.0..1.0` 且大于 warning；比较的是当前 JVM heap 比例，达到后触发紧急清理 |
| `emergencyCleanupCooldownMs` | `180_000` | 生效中 | 必须 `> 0`；限制紧急清理触发频率 |
| `workerRestartIntervalMs` | `24 * 3600 * 1000` | 预留参数 | 必须 `> 0`；当前没有 worker 进程实现消费它 |
| `workerMaxMemoryMb` | `512` | 预留参数 | 必须 `> 0`；当前仅保留配置和校验 |
| `workerIdleTimeoutMs` | `120_000` | 预留参数 | 必须 `> 0`；当前仅保留配置和校验 |
| `enableWorkerProcess` | `true` | 预留开关 | 当前仓库没有实际 worker-process 绘图路径，不能因为默认值是 `true` 就假定功能已启用 |
| `autoSwitchMode` | `true` | 预留开关 | 当前没有自动切换模式的生产调用点，不能把它当作已生效的自愈机制 |

维护要求：

- `validate()` 是唯一的参数合法性守卫；新增参数时必须同步补校验。
- `memoryWarningThreshold < memoryCriticalThreshold` 是硬约束，不能只改其中一个。
- 两个 memory threshold 当前都基于 `Runtime.totalMemory/freeMemory/maxMemory` 计算，不代表 Skia native 使用率；改成 native 指标前必须同步监控、告警和回归测试。
- worker 相关 5 个参数当前都属于“声明存在但未接线”的状态；激活前必须先更新实现、文档和 `bugs.md`。

## 绘图规则

所有业务绘图应采用：

```kotlin
SkiaManager.executeDrawing {
    val surface = createSurface(width, height)
    // 使用当前 DrawingSession 创建或 track native 资源。
}
```

允许会话创建：

- `createSurface`
- `createImage`
- `createParagraph`
- `createTextLine`
- `createFont`
- `createPaint`
- `createLinearGradient`
- `createSweepGradient`
- `createBlendColorFilter`
- `createSvg`

对象级 close/cache/no-close 分类见 [`../development/skiko-object-lifecycle.md`](../development/skiko-object-lifecycle.md)。新增 Skiko 对象时必须先确认它是否已有 `DrawingSession` factory；没有 factory 时，优先补充 factory，或在创建处紧邻调用 `track()` 并说明生命周期边界。

`Paint` 的绑定对象不会随 `Paint.close()` 级联释放。新增 `Shader`、`ImageFilter`、`ColorFilter`、`MaskFilter`、`PathEffect` 等绑定对象时，必须独立纳入 `DrawingSession` 追踪。

## 资源与生命周期

Skia 模块拥有绘图队列、会话内 native 对象、全局字体对象和清理策略的生命周期。会话资源必须随 `DrawingSession` 关闭，全局资源必须由 `SkiaManager.shutdown()`、`FontManager` 或清理 tasker 明确释放；新增全局缓存必须登记清理入口和观测指标。

## 配置与数据

Skia 读取 `SkiaConfig` 和绘图相关运行参数，不写业务数据。新增配置必须同步 `SkiaConfig.validate()`、本文参数表、内存调优文档和必要的已知问题说明，不能只加字段不接线。

## 测试与验证

- 修改 `DrawingSession` factory 或对象生命周期后，运行 `ParagraphBuilderLifecycleRegressionTest`、相关 Skia 生命周期测试和对象分类检查。
- 修改队列、超时、并发或清理阈值后，运行 `SkiaNativeMemoryEvidenceTest`、tasker 清理测试或监控快照测试。
- 修改 worker 预留参数前，先更新 `bugs.md` 或当前状态文档，确认实现已经接线。

## 禁止事项

- 禁止在绘图主路径绕过 `SkiaManager.executeDrawing`。
- 禁止把 `DrawingSession` 内创建的 `Image`、`Surface`、`Paint`、`Paragraph` 缓存到全局。
- 禁止 close 后继续使用 `Managed` 对象。
- 禁止新增未关闭的 `ParagraphBuilder`。
- 禁止手动 close 由父对象管理的 `Canvas`。
- 禁止新增 Skiko `Managed` 对象却不更新对象生命周期细则或 `DrawingSession` 追踪能力。

## 清理流程

普通清理：

1. 通过 `awaitAllCompleted()` 临时设置清理闸门并等待活动绘图完成，最长 30 秒。
2. 空 block 返回后清理闸门会释放；当前实际 purge 不在同一个互斥窗口内。
3. `FontUtils.resetParagraphCache()`。
4. `Graphics.purgeResourceCache()`。
5. `ImageCache.cleanCache()`。
6. 执行 3 轮 GC/finalization。

紧急清理：

1. 冷却时间内跳过。
2. 通过 `awaitAllCompleted()` 临时设置清理闸门并等待活动绘图完成，最长 15 秒。
3. 闸门释放后重置 `FontUtils` paragraph cache。
4. `Graphics.purgeAllCaches()` 并执行 `ImageCache.cleanCache()`。
5. 执行 5 轮 GC/finalization。

当前清理等待与实际 cache purge 不处于同一 `runExclusiveCleanup()` block，新绘图可能在两者之间进入 active。该实现缺口见 [`../context/known-issues.md#ki-004-skia-purge-未保持在清理闸门窗口内`](../context/known-issues.md#ki-004-skia-purge-未保持在清理闸门窗口内)。

## 查询 checklist

- [ ] 是否已阅读根目录 `AGENTS.md` 与 `docs/AGENTS.md`？
- [ ] 是否确认查询对象属于本模块，而不是相邻模块、历史计划或过期文档？
- [ ] 是否阅读本文档列出的代码入口、禁止事项和相关 domain/architecture 文档？
- [ ] 是否区分当前实现、阶段性计划和过期记录？
## 变更 checklist

- [ ] 是否阅读 [`../architecture/decisions/adr-002-skia-lifecycle.md`](../architecture/decisions/adr-002-skia-lifecycle.md)？
- [ ] 是否检查 `SkiaConfig.validate()` 对新增参数的约束？
- [ ] 是否阅读 [`../development/skiko-object-lifecycle.md`](../development/skiko-object-lifecycle.md) 并确认对象分类？
- [ ] 新增 native 对象是否被 `DrawingSession` 追踪？
- [ ] 新增 `Paint` 绑定对象是否独立追踪，而不是依赖 `Paint.close()`？
- [ ] 是否避免会话资源逃逸？
- [ ] 是否误把 worker 预留参数当作已实现能力？
- [ ] 是否需要更新 `SkiaCleanupTasker` 清理逻辑？
- [ ] 是否运行 Skia 相关测试，如 `ParagraphBuilderLifecycleRegressionTest`、`SkiaGradientRuntimeRegressionTest`、`SkiaNativeMemoryEvidenceTest`？
## 新建 checklist

- [ ] 新文件是否优先归入本模块既有入口，而不是新增顶层包？
- [ ] 新函数、方法或逻辑块是否补充紧邻注释，说明用途、意图或关键约束？
- [ ] 新配置、数据结构、资源、协程、客户端、缓存、channel 或 native 对象是否有明确生命周期和归属边界？
- [ ] 新 Skiko 对象是否先补入 [`../development/skiko-object-lifecycle.md`](../development/skiko-object-lifecycle.md) 并决定是否扩展 `DrawingSession`？
- [ ] 新外部行为是否同步更新相关 domain、architecture、development 或 operations 文档？
- [ ] 新测试是否只验证源码行为或产物，不复制项目文档内容？
