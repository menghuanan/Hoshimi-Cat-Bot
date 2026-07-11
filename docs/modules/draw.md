# Draw 模块

**覆盖度**：核心流程 ✅ | 完整参数 ✅ | 边界情况 ⚠️

## 模块定位

Draw 模块负责把 B 站动态、直播、视频、专栏、用户等业务模型渲染为图片卡片。它依赖 Skia 模块提供的会话和资源管理。

## 代码入口

- `src/main/kotlin/top/bilibili/draw/DynamicDraw.kt`
- `src/main/kotlin/top/bilibili/draw/DynamicMajorDraw.kt`
- `src/main/kotlin/top/bilibili/draw/DynamicModuleDraw.kt`
- `src/main/kotlin/top/bilibili/draw/LiveDraw.kt`
- `src/main/kotlin/top/bilibili/draw/ContentDescLayout.kt`
- `src/main/kotlin/top/bilibili/draw/FontManager.kt`
- `src/main/kotlin/top/bilibili/draw/General.kt`
- `src/main/kotlin/top/bilibili/draw/LoginQrCodeRenderer.kt`
- `src/main/kotlin/top/bilibili/draw/QrCodeDraw.kt`
- `src/main/kotlin/top/bilibili/utils/ImagePreprocessing.kt`
- `src/main/kotlin/top/bilibili/utils/General.kt`
- `src/main/kotlin/top/bilibili/utils/ImageCache.kt`
- `src/main/kotlin/top/bilibili/service/PlatformMessageSupport.kt`

## 主要职责

- 渲染动态主体、作者信息、附加卡片、直播卡片和二维码。
- 处理字体、emoji、粉丝卡字体和布局。
- 输出可缓存的图片路径或字节。

## 关键流程

业务层准备 data 模型和展示配置后进入 Draw 模块，Draw 通过 `SkiaManager.executeDrawing` 获取 `DrawingSession`，按动态类型选择主体渲染、下载或降级图片资源，最终输出可发送的图片路径或字节。新增渲染分支必须先确认动态类型、资源下载、降级和缓存键是否已有统一入口。

`QrCodeDraw.kt` 保留 `loginQrCode`、`loginQrCodeBytes` 和 `qrCode` 兼容入口；当前登录热路径委托 `LoginQrCodeRenderer`。不要在兼容入口重新实现另一套二维码 native 生命周期。

## 图片下载与大图预处理

绘图主链的远程图片读取走 `getOrDownload()` / `getOrDownloadImageDefault()`，不是直接 `Image.makeFromEncoded(url)`。

### URL 规范化边界

- 允许：`http://`、`https://`、`//`、`cache/`、`file://`
- `//example.com/a.png` 会被规范化成 `https://example.com/a.png`
- 以 `@` 开头的字符串会被直接拒绝，避免把业务占位符误当成真实图片地址
- 其他协议或空白字符串会被拒绝

### 大图预处理阈值

`ImagePreprocessing.kt` 当前只对“静态且双阈值同时超限”的图片做预处理：

| 参数 | 当前值 | 含义 |
| --- | --- | --- |
| `LARGE_IMAGE_WIDTH_THRESHOLD` | `4000` | 宽度必须大于 4000 |
| `LARGE_IMAGE_HEIGHT_THRESHOLD` | `3000` | 高度必须大于 3000 |
| `PREPROCESSED_IMAGE_MAX_LONG_EDGE` | `2800` | 命中预处理后，最长边缩到 2800 |

维护要点：

- 条件是 `width > 4000 && height > 3000`，不是任一边超限就缩放。
- 仅静态图参与预处理；PNG `acTL`、多帧 GIF、带动画标志的 WebP 都会跳过。
- 识别文件头的格式只有 PNG、JPEG、GIF、WEBP。
- 预处理结果写到 `resized_<原文件名>`，命中已有缩放缓存时直接复用。
- 预处理失败时必须回退原图字节，不能让整条绘图链因为缩放失败而中断。
- GIF 一旦进入预处理会重新编码为 PNG；这是当前实现的既定行为，不要误以为还能保留 GIF 编码。

### 原图/兜底图链路

`getOrDownloadImageDefault(url, fallbackUrl)` 的顺序是：

1. 如果 `cacheConfig.downloadOriginal=true`，先尝试原图 `url`
2. 原图失败后，再尝试 `fallbackUrl`
3. 两者都失败时，回退内置 `IMAGE_MISS.png`

这条链路是 Draw 模块的最后兜底，不要在业务绘图里各自再写一套“下载失败后随便换个图”的逻辑。

## SSRF 防护边界

绘图相关远程下载由 `BoundedRemoteResourceDownloader` 和 `RemoteResourcePolicy` 统一执行网络边界校验，`General.kt` 与 `ImageCache.kt` 只是调用入口。判断结果默认偏保守，解析或 DNS 校验异常按拒绝处理。

共享下载契约包括：

- 只接受无 userinfo 且带有效主机的 `http`/`https` URL。
- 初始地址、DNS 返回的全部地址和最多 5 次重定向的每一跳都必须是公网目标；混合公网/私网 DNS 结果整体拒绝。
- 单响应按声明长度和实际读取双重限制为 25 MiB，全局最多同时下载 2 个远程资源。
- 连接、读取和整体调用均有超时；非成功 HTTP 响应、空响应体和超限响应按拒绝处理。

当前拒绝范围：

- `10.0.0.0/8`
- `172.16.0.0/12`
- `192.168.0.0/16`
- `127.0.0.0/8`
- `169.254.0.0/16`
- `0.0.0.0/8`
- `localhost`
- `*.local`
- 以 `192.168.`、`10.`、`172.` 开头的主机名模式

额外约束：

- 文件名落盘前会经过 `sanitizeFileName()`，去掉 `..`、路径分隔符和空字节，避免路径穿越。
- 不要新增绕过 `BoundedRemoteResourceDownloader` 的图片下载 helper。只要绕过共享入口，就等于绕过 DNS/重定向复检、响应上限和并发闸门。

## 图片降级链路

Draw 相关图片降级分三层，不能混在一起理解：

### 1. 渲染阶段降级

- 远程资源下载失败：`getOrDownloadImageDefault()` 退到 `fallbackUrl`，再退到 `IMAGE_MISS.png`
- 静态大图预处理失败：回退原图字节，不中断绘图
- `FeatureSwitchService.canRenderPushDraw()` 为 `false` 时，动态/直播推送不生成 draw 图，返回 `null`
- `FeatureSwitchService.canRenderLinkResolveDraw()` 为 `false` 时，链接解析直接回退纯文本标准链接

### 2. 模板阶段降级

- 推送模板中的 `{draw}` 在 draw 图缺失时会被移除
- 如果模板包含 `{draw}` 且最终没有任何有效图片或文本消息段，`TemplateRenderService` 会补文本兜底
- 动态被锁内容的占位图只在推送绘图开启时生成；关闭时相关图片列表会回退为空

### 3. 发送阶段降级

- `sendPartsWithCapabilityFallback()` 会先做 `guardImageSend`
- 结果为 `Supported`：按原图片消息发送
- 结果为 `Degraded`：优先发显式 `fallbackText`，否则只保留原文本片段
- 结果为 `Unsupported`：当前 helper 直接返回失败，不自动拼兜底文本

当前边界还要注意：

- 链接解析图片发送失败时，`ListenerTasker` 的最后文本回退是标准链接或错误提示，不会重试另一套图片上传链。
- 模板 `{images}` 使用的是 `ImageCache.cacheImage(url)`，它负责 24 小时图片缓存和 SSRF 防护，但**不走** `ImagePreprocessing.kt` 的大图预处理。

## Skia 依赖规则

Draw 模块应在调用方提供的 `DrawingSession` 内创建资源。新增绘图函数优先接受 `DrawingSession` 参数，而不是内部裸建 Skia 对象。

新增或改造绘图对象前必须先查 [`../development/skiko-object-lifecycle.md`](../development/skiko-object-lifecycle.md)。如果需要使用 `Path`、`Bitmap`、`Picture`、`PictureRecorder`、`ImageFilter`、`MaskFilter`、`PathEffect`、`TextBlob`、`Codec` 等当前未列入 Skia 模块允许会话创建清单的对象，必须先决定是扩展 `DrawingSession` factory，还是在局部 `use {}` / `track()` 中封闭生命周期。

## 资源与生命周期

Draw 模块会触发图片下载、字体访问、Skia native 对象创建、缓存读写和临时文件输出，但长期资源归属 Skia、utils、cache tasker 或调用方。新增缓存、全局 map、临时文件或 native 对象必须明确清理入口，并避免绕过 `DrawingSession`。

`createImageWithSession()`、`createImageWithArea()` 和 `DrawingSession.drawToImage()` 返回未追踪的 `Image`，调用方必须关闭或转交给仍存活的外层 session。完整所有权表见 [`../development/skiko-object-lifecycle.md#返回-image-的所有权转移-helper`](../development/skiko-object-lifecycle.md#返回-image-的所有权转移-helper)。

## 配置与数据

Draw 读取图片质量、主题、缓存和展示相关配置，但不拥有配置写入权。新增展示字段必须优先落在 data/domain 文档，新增可配置项必须同步 config 文档并说明默认值、兼容旧配置和缺失降级。

## 测试与验证

- 修改布局、字体、颜色、动态类型分支或图片降级后，运行对应 draw、service 或 snapshot 回归测试。
- 修改动态附加卡片时，运行 `DynamicAdditionalCardRegressionTest`；已撤销且只剩空字段的预约卡片必须返回 `null`，不能绘制空白卡。
- 修改图片下载、预处理阈值或缓存键后，运行 `ImagePreprocessPolicyTest`、`DrawCacheKeyNormalizationTest` 或相关缓存测试。
- 新增 Skiko 对象后，检查 [`../development/skiko-object-lifecycle.md`](../development/skiko-object-lifecycle.md) 并运行相关 Skia 生命周期测试。

## 禁止事项

- 禁止绕过 `DrawingSession` 直接创建未关闭 native 资源。
- 禁止复制裸 `Surface.makeRasterN32Premul` 示例到业务绘图路径。
- 禁止把绘图对象缓存到全局状态。
- 禁止在 draw 层写配置或业务数据。
- 禁止在渲染热路径重复创建重量级解析器或客户端。
- 禁止绕过 `getOrDownload()` / `getOrDownloadImageDefault()` / `ImageCache.cacheImage()` 直接下载远程图片。
- 禁止擅自修改大图双阈值或 SSRF 范围后不更新文档与回归验证。

## 查询 checklist

- [ ] 是否已阅读根目录 `AGENTS.md` 与 `docs/AGENTS.md`？
- [ ] 是否确认查询对象属于本模块，而不是相邻模块、历史计划或过期文档？
- [ ] 是否阅读本文档列出的代码入口、禁止事项和相关 domain/architecture 文档？
- [ ] 是否区分当前实现、阶段性计划和过期记录？
## 变更 checklist

- [ ] 新增绘图对象是否由 `DrawingSession` 创建或 `track()`？
- [ ] 新增 Skiko 对象是否已按 [`../development/skiko-object-lifecycle.md`](../development/skiko-object-lifecycle.md) 分类并封闭生命周期？
- [ ] 图片输入是否仍走现有 URL 规范化、SSRF 防护和文件名清洗？
- [ ] 大图预处理是否仍保持 `4000x3000` 双阈值与 `2800` 长边缩放上限？
- [ ] 图片输入是否在 assemble/cache 后正确关闭？
- [ ] 返回未追踪 `Image` 的 helper 是否在调用方显式关闭或转移所有权？
- [ ] 字体资源是否仍由 `FontManager` 管理？
- [ ] 是否影响模板 `{draw}` 或链接解析文本回退？
- [ ] 是否运行 draw 相关测试，如 `ContentDescLayoutTest`、`PgcCardLayoutRegressionTest`、`LoginQrCodeBytesFeatureTest`？
## 新建 checklist

- [ ] 新文件是否优先归入本模块既有入口，而不是新增顶层包？
- [ ] 新函数、方法或逻辑块是否补充紧邻注释，说明用途、意图或关键约束？
- [ ] 新配置、数据结构、资源、协程、客户端、缓存、channel 或 native 对象是否有明确生命周期和归属边界？
- [ ] 新外部行为是否同步更新相关 domain、architecture、development 或 operations 文档？
- [ ] 新测试是否只验证源码行为或产物，不复制项目文档内容？
