# 红线：绝对禁止事项

这些约束不接受“但是这个场景下...”的例外。若确实需要改变，必须先改对应 ADR、模块文档和不变量，并经人类确认。

_最后复核：2026-07-12_

## RL-001: 禁止删除、缩减或覆盖根目录 AGENTS.md

**禁止**：删除、移动、覆盖、改写或弱化根目录 `AGENTS.md` 中已有内容。

**原因**：根目录 `AGENTS.md` 是仓库级执行规范，包含 UTF-8、注释保留、KDoc 同步、docs 读取边界等强制规则。

**正确做法**：新增规范写入 `docs/`，如有冲突记录到 [`../bugs.md`](../bugs.md)。

## RL-002: 禁止业务模块直接写配置文件

**禁止**：在 service、tasker、connector、draw、api 等业务模块中直接 `writeText`、`copyTo` 或覆盖 `config/BiliConfig.yml`、`config/BiliData.yml`、`config/bot.yml`。

**原因**：配置写入必须保留迁移、归一化、快照和版本处理。直接写文件会绕过 `BiliConfigManager` 或 `ConfigManager`。

**正确做法**：主配置快照走 `BiliConfigManager`，运行期业务数据变更走 `BiliDataRuntimeCoordinator.mutateAndPersist*`，平台配置走 `ConfigManager`；候选持久化成功后才能安装运行态。

## RL-003: 禁止绕过 SkiaManager.executeDrawing 创建绘图主流程

**禁止**：在业务绘图路径中直接创建长期未追踪的 `Surface.makeRasterN32Premul`、`Image.makeFromEncoded`、`Paint()`、`Shader.*` 或 `ParagraphBuilder`。

**原因**：Skia native 资源不受 JVM heap 直接约束，必须由 `DrawingSession` 跟踪并逆序释放。

**正确做法**：使用 `SkiaManager.executeDrawing { ... }`，在会话内调用 `createSurface/createImage/createPaint/createParagraph` 或显式 `track()`。

## RL-004: 禁止向平台中立接口泄露 vendor 类型

**禁止**：`PlatformAdapter`、`PlatformConnectorManager`、`MessageGateway`、业务 service 的公开接口出现 `NapCat*`、`LlBot*`、`OneBot11Models`、`QQOfficialTransport` 等 vendor DTO 或 transport 类型。

**原因**：平台抽象的目标是让业务层只消费 `PlatformContact`、`PlatformInboundMessage`、`OutgoingPart`、`ImageSource` 和 capability 结果。

**正确做法**：vendor 类型只留在 `connector/onebot11/vendors/*`、`connector/onebot11/core/*` 或对应平台实现内部。

## RL-005: 禁止业务层直接持有 raw PlatformAdapter

**禁止**：在 `service`、`tasker`、`draw`、`api` 中直接创建或保存 `NapCatAdapter`、`LlBotAdapter`、`GenericOneBot11Adapter`、`QQOfficialAdapter`。

**原因**：raw adapter 绕过 `PlatformConnectorManager` 的生命周期锁和停机后不复活约束。

**正确做法**：发送走 `MessageGatewayProvider`；能力判断走 `PlatformCapabilityService`；运行态读取走 `BiliBiliBot.requireConnectorManager()`。

## RL-006: 禁止新增未登记资源策略的 Tasker

**禁止**：新增 `BiliTasker` 子类后只调用 `start()`，但未加入 `TaskBootstrapService.startupTaskNames` 和 `TaskResourcePolicyRegistry`。

**原因**：`BiliTasker.start()` 会强制校验资源策略，启动集合也会执行覆盖校验。

**正确做法**：同时更新启动顺序、资源策略、模块文档和必要测试。

## RL-007: 禁止在 Tasker 主循环中新增无界阻塞 IO

**禁止**：在周期任务或 channel 消费循环中新增无超时网络请求、无限制文件扫描、大文件同步写、未限流外部进程调用。

**原因**：Tasker 是常驻后台循环，无界阻塞会导致 channel 背压、停机无法收敛和 ProcessGuardian 误判。

**正确做法**：网络请求走 `BiliClient`/平台 transport 的超时配置；文件维护任务要小批量、可中断、可记录失败。

## RL-008: 禁止 close 后继续引用 Skia Managed 对象

**禁止**：`close()` 后继续保存、传递或绘制 `Managed` 对象；禁止把会话内对象缓存到全局状态。

**原因**：Skia native 对象关闭后继续使用可能导致 JVM crash 或静默内存损坏。

**正确做法**：只缓存编码后的字节、文件路径或业务模型；不要缓存会话内 `Image`、`Surface`、`Paint`、`Paragraph`。

## RL-009: 禁止手动 close 父对象管理的 Skiko 对象

**禁止**：手动关闭 `Surface.canvas` 返回的 `Canvas`，或关闭其他由父 Skiko 对象拥有、生命周期不属于调用方的句柄。

**原因**：父对象管理的句柄被手动关闭可能造成 double-close、use-after-free 或 JVM crash。

**正确做法**：按 [`skiko-object-lifecycle.md`](skiko-object-lifecycle.md) 判断对象分类；`Canvas` 只在 `Surface` 有效期内使用，由 `Surface` 释放。

## RL-010: 禁止新增未纳入生命周期细则的 Skiko Managed 对象

**禁止**：在业务绘图、draw、skia 或 utils 中新增 `Bitmap`、`Picture`、`ImageFilter`、`MaskFilter`、`PathEffect`、`TextBlob`、`Codec` 等 Skiko `Managed` 对象，却不更新对象生命周期细则或不纳入 `DrawingSession` / 局部 `use {}` 管理。

**原因**：对象级 close 规则不完整会让维护者误以为 `Paint.close()` 或 GC 会兜底释放，导致 native 内存泄漏或崩溃。

**正确做法**：先更新 [`skiko-object-lifecycle.md`](skiko-object-lifecycle.md)，再扩展 `DrawingSession` factory、调用 `track()`，或用局部 `use {}` 封闭生命周期。

## RL-011: 禁止绕过 LinkResolvePolicyService 做群内链接解析

**禁止**：消息入口直接调用某个 `draw*Link` 或 B 站详情 API，然后立即发送结果。

**原因**：统一策略负责短链接优先、结果去重、每消息上限、每用户频率限制和群内冷却。

**正确做法**：先 `matchingAllRegular` 收集候选，再交给 `LinkResolvePolicyService.applyPolicy()`。

## RL-012: 禁止把项目文档内容塞入测试模块

**禁止**：将 `docs/`、根 `AGENTS.md` 或其中片段复制到 `src/test` 下作为测试资源或断言原文。

**原因**：仓库级规范明确禁止把项目内文档或文档片段加入测试模块，避免测试锁死文档表达。

**正确做法**：如果需要回归保护，只测试源码行为、配置结构或公开 API，不复制文档正文。

## RL-013: 禁止在渲染热路径中重复创建 Json 配置块

**禁止**：在被 Tasker 周期调用或绘图高频调用的函数内反复执行 `Json { ... }` 并作为局部临时解析器。

**原因**：高频创建 JSON 实例会增加分配和 GC 压力，尤其在轮询、链接解析和绘图路径叠加时更明显。

**正确做法**：复用 `utils.json`、类内单例或已有客户端级 `json` 实例；新增例外必须说明调用频率和生命周期。

## RL-014: 禁止绕过候选代际直接热切换平台连接

**禁止**：WebUI 保存 `bot.yml` 后直接停止当前 adapter、覆盖全局配置或创建并安装未经连通验证的候选 adapter。

**原因**：`RuntimeConfigApplier` 与 `PlatformConnectorManager.prepareReload/commitReload` 保证候选失败时旧平台连接和管理入口继续服务；绕过会把可回滚配置更新变成运行中断。

**正确做法**：通过 `WebUiConfigHotReloadCoordinator` 串行提交，先持久化候选并 prepare，成功后 commit；失败候选调用 `closeUncommitted()` 并执行磁盘/运行态回滚。

## RL-015: 禁止绕过共享下载器获取远程绘图资源

**禁止**：在 draw、service、tasker 或图片缓存路径中另建自动重定向 HTTP client，或在未复检 DNS 与重定向目标时下载远程图片。

**原因**：`BoundedRemoteResourceDownloader` 统一承担 SSRF 防护、逐跳公网校验、25 MiB 响应上限、超时与全局并发闸门；旁路会恢复内网探测和无界内存风险。

**正确做法**：所有远程绘图和缓存资源都通过 `BoundedRemoteResourceDownloader`，策略变化同步更新 `RemoteResourcePolicy` 及其回归测试。
