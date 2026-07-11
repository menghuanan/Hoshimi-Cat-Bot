# 动态类型枚举与处理规则

本文记录当前项目对 B 站动态类型的识别、过滤、推送和渲染规则。

## 枚举入口

动态类型定义在 `top.bilibili.data.DynamicType`。

当前已知类型：

| 类型 | 展示文本 | 当前处理语义 |
| --- | --- | --- |
| `DYNAMIC_TYPE_WORD` | 动态 | 普通文字动态 |
| `DYNAMIC_TYPE_DRAW` | 动态 | 图片动态 |
| `DYNAMIC_TYPE_ARTICLE` | 专栏 | 专栏动态 |
| `DYNAMIC_TYPE_FORWARD` | 转发动态 | 转发动态 |
| `DYNAMIC_TYPE_AV` | 投稿视频 | 视频动态 |
| `DYNAMIC_TYPE_MUSIC` | 音乐 | 音乐卡片 |
| `DYNAMIC_TYPE_LIVE` | 直播 | 动态轮询中过滤 |
| `DYNAMIC_TYPE_LIVE_RCMD` | 直播 | 动态轮询中过滤 |
| `DYNAMIC_TYPE_PGC` | 番剧 | 走番剧订阅路由 |
| `DYNAMIC_TYPE_PGC_UNION` | 番剧 | 走番剧订阅路由 |
| `DYNAMIC_TYPE_COMMON_SQUARE` | 动态 | 通用活动卡片 |
| `DYNAMIC_TYPE_COMMON_VERTICAL` | 动态 | 通用竖卡 |
| `DYNAMIC_TYPE_UGC_SEASON` | 合集 | 合集卡片 |
| `DYNAMIC_TYPE_NONE` | 动态被删除 | 删除或不可见动态 |
| `DYNAMIC_TYPE_UNKNOWN` | 未知的动态 | 未知类型兜底 |

## 未知类型规则

`DynamicItem.type` 通过 `typeStr` 计算：

```kotlin
val type: DynamicType get() = try {
    DynamicType.valueOf(typeStr)
} catch (e: IllegalArgumentException) {
    DynamicType.DYNAMIC_TYPE_UNKNOWN
}
```

**规则**：新增或未知动态类型必须平稳降级为 `DYNAMIC_TYPE_UNKNOWN`。不得让新类型导致整个动态列表解析失败。

## 列表逐项容错

`getNewDynamic()` 与 `getUserNewDynamic()` 使用 `decodeDynamicListSkippingInvalidItems()`。它先解码不含 items 的列表外层，再逐条解码 `DynamicItem`：单条字段异常只丢弃当前 item，保留同页其它动态和分页元数据；响应根对象或分页元数据异常仍按整体失败处理。

跳过日志只记录稳定调试摘要，不输出完整原始 payload。修改该边界时运行 `DynamicListSafeDecodeTest`，并确认日志不会包含 Cookie 或大段用户内容。

## 轮询过滤规则

`DynamicCheckTasker` 当前过滤：

- 排除 `DYNAMIC_TYPE_LIVE`
- 排除 `DYNAMIC_TYPE_LIVE_RCMD`
- 只处理 `time > lastDynamic` 的动态
- 通过 `historyDynamic` 做最近 200 条 ID 去重
- PGC 类型使用 `bangumi` 订阅判断
- 普通类型使用 `dynamic` 订阅判断

**原因**：直播类型由直播任务独立处理；动态轮询只负责动态/视频/专栏/PGC 等内容。

## 手动检查规则

`DynamicCheckTasker.executeManualCheck()` 会忽略时间窗口和历史去重，但只取最近一条命中的动态。

**原因**：手动检查用于验证链路，不应一次命令把历史堆积内容全部补推到聊天窗口。

## 推送路由规则

`PushFanoutService` 负责将动态 item 解析为面向联系人推送的 detail。`SendTasker` 再按消息类型和联系人选择模板并渲染。

PGC 路由规则：

- `DYNAMIC_TYPE_PGC` 或 `DYNAMIC_TYPE_PGC_UNION` 且存在 `pgcSeasonId` 时，使用 `BiliData.bangumi` 订阅。
- 否则使用 `BiliData.dynamic[mid]`。

## 过滤器映射

`SendTasker.mapDynamicType()` 将运行时动态类型映射为过滤器类型：

| 动态类型 | 过滤器类型 |
| --- | --- |
| `DYNAMIC_TYPE_FORWARD` | `FORWARD` |
| `DYNAMIC_TYPE_ARTICLE` | `ARTICLE` |
| `DYNAMIC_TYPE_AV` | `VIDEO` |
| `DYNAMIC_TYPE_MUSIC` | `MUSIC` |
| `DYNAMIC_TYPE_LIVE`、`DYNAMIC_TYPE_LIVE_RCMD` | `LIVE` |
| 其他 | `DYNAMIC` |

正则过滤规则：

- 白名单配置写错时默认放行，避免错误规则把所有消息误杀。
- 黑名单配置写错时默认不拦截，避免单条坏规则导致整组推送不可用。

## 渲染规则

动态渲染涉及：

- `DynamicDraw.kt`
- `DynamicMajorDraw.kt`
- `DynamicModuleDraw.kt`
- `ContentDescLayout.kt`
- `TemplateRenderService`
- `DrawCacheKeyService`

**核心约束**：动态渲染必须处于 `SkiaManager.executeDrawing` 会话内，不能让 `Image`、`Surface`、`Paragraph` 等 native 对象逃逸。

预约附加卡片在撤销后可能只剩 `rid=0`、`state=-1` 和空字段。`DynamicModuleDraw` 会把这种空壳识别为无卡片并返回 `null`，避免生成空白附加图；正常预约、视频预约、直播预约和首映预告仍按现有标签绘制。

## 新增动态类型 checklist

- [ ] 在 `DynamicType` 中增加枚举或确认继续走 `DYNAMIC_TYPE_UNKNOWN`。
- [ ] 检查 `DynamicCheckTasker.banType` 是否需要过滤。
- [ ] 检查 `SendTasker.mapDynamicType()` 是否需要新增过滤器映射。
- [ ] 检查 `TemplateRenderService` 占位符是否足够表达新类型。
- [ ] 检查绘图层是否需要新增 major/additional 模块。
- [ ] 增加或更新解析/渲染/发送回归测试。
- [ ] 是否保持单条坏 item 跳过、列表外层严格失败的两层容错？
- [ ] 是否检查撤销预约等空壳附加卡片不会生成空白图？
