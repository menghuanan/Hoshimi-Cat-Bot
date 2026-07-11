# Skiko 对象生命周期细则

## 文档定位

本文是当前维护版 Skiko 对象级生命周期规则，细化 [`../architecture/invariants.md#inv-002-skia-native-资源必须绑定-drawingsession-或明确全局生命周期`](../architecture/invariants.md#inv-002-skia-native-资源必须绑定-drawingsession-或明确全局生命周期) 和 [`../architecture/decisions/adr-002-skia-lifecycle.md`](../architecture/decisions/adr-002-skia-lifecycle.md)。历史来源见 [`../规则/Skiko 对象生命周期管理总表.md`](../规则/Skiko 对象生命周期管理总表.md)，但当前约束以本文、Skia 模块文档和 ADR 为准。

## 总规则

| 规则 | 当前项目语义 |
| --- | --- |
| ✅ 必须 close | 继承或包装 native 资源的对象，必须由 `DrawingSession` 创建、`track()` 追踪，或在明确局部作用域内 `use {}` |
| 🔁 允许全局缓存 | 仅限字体、字体管理器等高成本资源，且必须由 `FontManager`、`SkiaManager.shutdown()` 或明确登记的清理入口释放 |
| ⛔ 禁止 close | 由父对象拥有的视图或句柄，例如 `Surface.canvas` 返回的 `Canvas`，不得手动关闭 |
| 🟢 无需 close | 纯 JVM 值类型、枚举或不可变结构，不得伪造生命周期管理 |

业务绘图主路径必须进入 `SkiaManager.executeDrawing { ... }`。在会话中优先使用 `DrawingSession.create*()`；若 Skiko 新对象暂未提供 factory，必须先评估是否扩展 `DrawingSession`，或在紧邻创建处调用 `track()` 并补充注释说明生命周期边界。

## 当前 DrawingSession 支持

| 对象 | 推荐入口 | 生命周期 |
| --- | --- | --- |
| `Surface` | `createSurface(width, height)` | 会话结束逆序 close |
| `Image` | `createImage(bytes)`、`surface.makeImageSnapshot().track()` | 会话结束逆序 close；导出字节后不得逃逸 |
| `Data` | `image.encodeToData(...).use { ... }` 或 `drawToBytes()` 内部关闭 | 取出 `bytes` 后立即 close |
| `Paragraph` | `createParagraph(...)` | `ParagraphBuilder` 在 build 后立即 close，`Paragraph` 会话追踪 |
| `TextLine` | `createTextLine(text, font)` | 会话结束逆序 close |
| `Font` | `createFont(typeface, size)` | 会话结束逆序 close |
| `Paint` | `createPaint { ... }` | 会话结束逆序 close；绑定对象仍需独立追踪 |
| `Shader` | `createLinearGradient(...)`、`createSweepGradient(...)` | 会话结束逆序 close |
| `ColorFilter` | `createBlendColorFilter(...)` | 会话结束逆序 close |
| `SVGDOM` | `createSvg(path)` | 会话结束逆序 close |

`drawToImage()` 这类返回 `Image` 的能力表示所有权转移给调用方；新增调用点必须说明由谁关闭返回的 `Image`，否则优先使用 `drawToBytes()`。

## 返回 Image 的所有权转移 helper

以下 helper 返回的 `Image` 没有加入最终关闭的 session 追踪列表，调用方接管所有权：

| Helper | 内部资源行为 | 返回值责任 |
| --- | --- | --- |
| `createImageWithSession()` | 内部 `executeDrawing` 会关闭 `DrawingSession` 与 `Surface`，快照不调用 `track()` | 调用方必须在使用结束后 `close()` 或立即放入明确外层 session 的 `track()` |
| `createImageWithArea()` | 局部 `Surface` 在 `finally` 中关闭 | 调用方必须关闭区域快照 |
| `DrawingSession.drawToImage()` | 方法内部创建并关闭独立 `Surface`，返回快照不属于当前 session | 调用方必须关闭快照；方法名位于 session 上不代表自动追踪 |

同一条规则适用于其它返回未追踪 `Image`、`Data`、`Picture` 或 `Managed` 对象的 helper：返回 native 对象前必须在 KDoc 说明所有权，调用点必须在紧邻作用域 `use {}`、`close()` 或转交给仍存活的 `DrawingSession.track()`。如果调用方只需要编码结果，优先返回 `ByteArray`，避免让 native 所有权跨层传播。

## 渲染核心对象

| 对象 | 管理方式 | 项目约束 |
| --- | --- | --- |
| `Surface` | ✅ 会话创建或局部 `use {}` | 业务绘图优先 `createSurface()`；不得绕过 `executeDrawing` 裸建长期对象 |
| `Canvas` | ⛔ 禁止手动 close | 由 `Surface` 管理，只在 surface 有效期内使用 |
| `Image` | ✅ 会话追踪或局部 `use {}` | `makeImageSnapshot()` 后尽快编码或发送给明确所有者 |
| `Bitmap` | ✅ 局部 `use {}` 或新增 session factory | 若新增到业务绘图，先补 `DrawingSession` 支持或紧邻 `track()` |
| `Data` | ✅ 取出字节后立即 close | 不得把 `Data` 缓存或跨会话传递 |
| `Picture` | ✅ 局部 `use {}` 或新增 session factory | 录制结果不得逃逸到未登记缓存 |
| `PictureRecorder` | ✅ 录制结束立即 close | `finishRecordingAsPicture()` 后仍需关闭 recorder |

## 绘制属性对象

| 对象 | 管理方式 | 项目约束 |
| --- | --- | --- |
| `Paint` | ✅ `createPaint {}` 或局部 `use {}` | 不会级联关闭 shader/filter/effect，绑定对象必须独立追踪 |
| `Path` | ✅ 局部 `use {}` 或新增 session factory | 复杂路径复用必须有明确缓存清理入口 |
| `Vertices` | ✅ 局部 `use {}` 或新增 session factory | 自定义顶点数据不得跨会话持有 native 对象 |

## Paint 绑定对象

`Paint.close()` 不会级联释放绑定对象；以下对象必须各自 `use {}`、`track()` 或由 `DrawingSession.create*()` 返回。

| 对象 | 管理方式 | 挂载属性 |
| --- | --- | --- |
| `Shader` | ✅ 独立追踪 | `paint.shader` |
| `ImageFilter` | ✅ 独立追踪；新增前补 session factory 或 `track()` | `paint.imageFilter` |
| `ColorFilter` | ✅ 独立追踪 | `paint.colorFilter` |
| `MaskFilter` | ✅ 独立追踪；新增前补 session factory 或 `track()` | `paint.maskFilter` |
| `PathEffect` | ✅ 独立追踪；新增前补 session factory 或 `track()` | `paint.pathEffect` |
| `BlendMode` | 🟢 无需 close | 枚举值 |

## 文字排版对象

| 对象 | 管理方式 | 项目约束 |
| --- | --- | --- |
| `Typeface` | 🔁 由 `FontManager` 管理 | 业务代码不得自行建立未登记全局字体缓存 |
| `FontMgr` / `FontCollection` | 🔁 明确全局生命周期 | 必须随 `SkiaManager.shutdown()` 或字体管理器释放 |
| `Font` | ✅ `createFont()` | 由 typeface 和 size 构造，仍持有 native 指针 |
| `TextBlob` | ✅ 局部 `use {}` 或新增 session factory | 绘制完立即 close；新增公共用法前补 session 支持 |
| `TextLine` | ✅ `createTextLine()` | 会话结束逆序 close |
| `ParagraphBuilder` | ✅ build 后立即 close | 必须放在 `try/finally`；不得缓存 builder |
| `Paragraph` | ✅ `createParagraph()` | 排版结果在会话结束关闭 |

## 图片与 SVG I/O 对象

| 对象 | 管理方式 | 项目约束 |
| --- | --- | --- |
| `Codec` | ✅ 局部 `use {}` 或新增 session factory | 解码完立即 close，不得持有到缓存 |
| `SVGDOM` | ✅ `createSvg()` | 渲染后由会话关闭 |

## 纯值类型

| 对象 | 类型说明 |
| --- | --- |
| `Rect` / `RRect` / `IRect` | 值对象 |
| `Point` / `IPoint` | 坐标值对象 |
| `Matrix33` / `Matrix44` | 变换矩阵值对象 |
| `Color` / `Color4f` | 颜色值 |
| `BlendMode` | 枚举 |
| `FilterTileMode` | 枚举 |
| `PaintMode` | 枚举 |
| `StrokeCap` / `StrokeJoin` | 枚举 |
| `ClipMode` | 枚举 |

## 项目模板

```kotlin
fun render(width: Int, height: Int): ByteArray {
    return SkiaManager.executeDrawing {
        val surface = createSurface(width, height)
        val canvas = surface.canvas // Canvas 由 Surface 管理，不要 close。

        val blur = ImageFilter.makeBlur(4f, 4f, FilterTileMode.CLAMP).track()
        val shader = createLinearGradient(Point(0f, 0f), Point(width.toFloat(), 0f), intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()))
        val paint = createPaint {
            imageFilter = blur
            this.shader = shader
        }

        canvas.drawRect(Rect.makeWH(width.toFloat(), height.toFloat()), paint)

        val image = surface.makeImageSnapshot().track()
        val data = image.encodeToData(EncodedImageFormat.PNG)
            ?: throw IllegalStateException("Failed to encode image")
        try {
            data.bytes
        } finally {
            data.close()
        }
    }
}
```

该模板强调项目边界：`Canvas` 不关闭，`Paint` 绑定对象独立追踪，只有 `ByteArray` 这种纯 JVM 数据可以离开会话。

## 查询 checklist

- [ ] 是否先阅读 [`../modules/skia.md`](../modules/skia.md) 和 ADR-002？
- [ ] 是否确认对象是必须 close、允许全局缓存、禁止 close 还是无需 close？
- [ ] 是否核对当前 `DrawingSession` 是否已有对应 factory？

## 变更 checklist

- [ ] 新增 Skiko `Managed` 对象是否纳入 `DrawingSession` factory 或紧邻 `track()`？
- [ ] `Paint` 绑定对象是否独立追踪，而不是依赖 `Paint.close()`？
- [ ] 全局缓存是否由 `FontManager`、`SkiaManager.shutdown()` 或资源分区释放？
- [ ] 返回 native 对象的函数是否明确所有权转移和关闭责任？
- [ ] 是否检查 `createImageWithSession()`、`createImageWithArea()` 和 `drawToImage()` 的返回值由调用方关闭？
- [ ] 是否更新 [`../modules/skia.md`](../modules/skia.md)、[`../modules/draw.md`](../modules/draw.md) 或 ADR-002？

## 新建 checklist

- [ ] 新 Skiko 对象是否先加入本文分类表？
- [ ] 新 factory 是否补充紧邻注释，说明对象用途和生命周期边界？
- [ ] 新缓存是否登记清理入口、停机顺序和测试建议？
- [ ] 新示例是否使用 `SkiaManager.executeDrawing`，而不是裸建业务绘图流程？
