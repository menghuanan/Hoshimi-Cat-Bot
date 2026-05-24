# ADR-002: Skia 资源生命周期管理策略

**状态**：已采纳  
**日期**：2026-04-22  
**决策者**：项目当前实现  

## 问题背景

Skiko 的 `Managed` 对象包装 native Skia 资源。JVM heap 指标不能完整反映这些 native 资源的真实占用，依赖 GC/finalizer 会导致长时间运行后 RSS 或 native heap 高水位不回落。项目又需要在 Tasker 周期推送和链接解析中生成图片，因此绘图路径必须显式限制并发、追踪资源、周期清理缓存。

## 考察的方案

1. **依赖 GC 自动释放 Skia 对象**  
   **否决**：GC 触发时机不可控，native 内存压力可能先于 JVM heap 压力出现。

2. **所有绘图对象都使用局部 `use {}`**  
   **部分采纳**：短生命周期对象适合，但复杂卡片绘制会创建多类资源，分散 `use` 容易遗漏。

3. **统一 `SkiaManager.executeDrawing` + `DrawingSession` + 清理 Tasker**  
   **采纳**：每次绘图创建一个会话，资源集中追踪并逆序关闭；全局缓存由 `SkiaCleanupTasker` 和 `SkiaManager.performCleanup()` 处理。

## 决策

当前 Skia 生命周期规则如下：

- 所有业务绘图主路径必须进入 `SkiaManager.executeDrawing { ... }`。
- `SkiaManager` 把绘图提交给 `DrawingQueueManager`，由 semaphore 限制并发，并设置单次绘图超时。
- `DrawingSession` 负责创建和追踪 `Surface`、`Image`、`Paragraph`、`TextLine`、`Font`、`Paint`、`Shader`、`ColorFilter`、`SVGDOM`。
- `DrawingSession.close()` 逆序关闭已追踪资源，并清空资源列表。
- Skiko 对象级 close/cache/no-close 分类以 [`../../development/skiko-object-lifecycle.md`](../../development/skiko-object-lifecycle.md) 为当前维护细则；新增对象必须先纳入该细则，再决定是否扩展 `DrawingSession`。
- `FontManager` 管理全局字体资源，随 `SkiaManager.shutdown()` 关闭。
- `SkiaCleanupTasker` 在空闲、周期或内存阈值触发时调用 `performCleanup()` 或 `performEmergencyCleanup()`。
- 清理会重置 `FontUtils` paragraph cache、调用 `Graphics.purgeResourceCache()` 或 `Graphics.purgeAllCaches()`，并触发多轮 GC/finalization。

## 已知权衡

- 绘图代码需要传递 `DrawingSession`，比直接创建 Skia 对象更啰嗦。
- 部分全局资源如字体由 `FontManager` 持有，必须保持全局生命周期和 shutdown 顺序一致。
- `SkiaConfig` 中存在 worker process 预留配置；worker process 为预留/未实现能力，当前仅支持 in-process。

## 若要修改此决策

必须同步更新：

- [`../invariants.md`](../invariants.md) 中 `INV-002`
- [`../../development/red-lines.md`](../../development/red-lines.md) 中 `RL-003`、`RL-008`
- [`../../modules/skia.md`](../../modules/skia.md)
- [`../../development/skiko-object-lifecycle.md`](../../development/skiko-object-lifecycle.md)
- `SkiaManager`、`DrawingSession`、`DrawingQueueManager`、`SkiaCleanupTasker`
- Skia 资源相关测试，如 `ParagraphBuilderLifecycleRegressionTest`、`SkiaNativeMemoryEvidenceTest`
