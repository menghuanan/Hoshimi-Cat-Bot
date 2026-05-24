# 模板占位符契约

本文记录 `TemplateRenderService` 当前实际支持的模板占位符、特殊消息段占位符和不适用时的行为。这里的内容是模板业务契约，不是示例文案。

如果修改了占位符集合、适用消息类型或特殊行为，必须同时同步：

- `src/main/kotlin/top/bilibili/service/TemplateRenderService.kt`
- `src/main/kotlin/top/bilibili/service/TemplateService.kt` 中的 `/bili template explain`
- [`../modules/service.md`](../modules/service.md)

## 总体规则

- 模板渲染入口是 `TemplateRenderService.buildSegments()`。
- 模板先按 `\r` 拆段，再逐段做占位符替换和消息段解析。
- `replacePlaceholders()` 只替换本文列出的文本占位符。
- `{draw}` 和 `{images}` 不会被替换成文本，而是在 `parseContent()` 中转成 `OutgoingPart.image(...)`。
- 未识别占位符会原样保留在文本里发送，不会自动报错。
- 某个占位符在当前消息类型没有对应数据时，也会原样保留，除非它属于 `{draw}` 或 `{images}` 这种特殊消息段占位符。

## 默认兜底模板

当未显式传入模板正文，且当前默认模板名在配置中找不到对应模板时，渲染器会回退到以下正文：

| 消息类型 | 默认正文 |
| --- | --- |
| 动态 `dynamic` | `{draw}\n{name}@{type}\n{link}` |
| 开播 `live` | `{draw}\n{name}@直播\n{link}` |
| 下播 `liveClose` | `{name} 直播结束啦!\n直播时长: {duration}` |

## 通用文本占位符

这些占位符对三类消息都会尝试替换：

| 占位符 | 含义 | 实际取值 |
| --- | --- | --- |
| `{name}` | UP 主或主播名称 | `message.name` |
| `{uid}` | 用户 UID | `message.mid.toString()` |
| `{mid}` | 用户 MID | `message.mid.toString()`，与 `{uid}` 当前等价 |
| `{time}` | 时间文本 | `message.time` |

## 动态消息占位符

仅 `DynamicMessage` 会替换以下字段：

| 占位符 | 含义 | 实际取值 |
| --- | --- | --- |
| `{type}` | 动态类型文案 | `message.type.text` |
| `{did}` | 动态 ID | `message.did` |
| `{content}` | 动态正文摘要 | `message.content` |
| `{link}` | 动态链接 | `https://t.bilibili.com/{did}` |
| `{links}` | 动态内提取出的链接列表 | `message.links` 按 `tag: value` 用 `\n` 拼接；为空时替换为空字符串 |

## 开播消息占位符

仅 `LiveMessage` 会替换以下字段：

| 占位符 | 含义 | 实际取值 |
| --- | --- | --- |
| `{title}` | 直播标题 | `message.title` |
| `{area}` | 直播分区 | `message.area` |
| `{link}` | 直播间链接 | `message.link` |
| `{cover}` | 直播封面链接 | `message.cover` |

## 下播消息占位符

仅 `LiveCloseMessage` 会替换以下字段：

| 占位符 | 含义 | 实际取值 |
| --- | --- | --- |
| `{title}` | 直播标题 | `message.title` |
| `{duration}` | 直播时长文案 | `message.duration` |
| `{area}` | 直播分区 | `message.area` |
| `{link}` | 直播间链接 | `message.link` |

## 特殊消息段占位符

这些占位符不是普通字符串替换，而是会影响 `OutgoingPart` 结构：

### `{draw}`

- 只要模板段中包含 `{draw}`，渲染器就会尝试读取 `message.drawPath`。
- 当 `drawPath != null` 且 `FeatureSwitchService.canRenderPushDraw(config)` 为 `true` 时，会追加一个图片消息段。
- 无论是否成功追加图片，`{draw}` 都会从文本中被移除。
- 如果模板包含 `{draw}`，但整个结果里没有任何有效图片或非空文本消息段，会触发兜底文案：
  - 动态：`{name}@{type}\nhttps://t.bilibili.com/{did}`
  - 开播：`{name}@直播\n{link}`
  - 下播：没有额外兜底文案

### `{images}`

- 只有 `DynamicMessage` 且 `message.images` 非空时才会生效。
- 每张图片会先经过 `ImageCache.cacheImage(imageUrl)`，缓存成功后才追加图片消息段。
- 无论图片列表是否存在，`{images}` 都会从文本中被移除。
- `LiveMessage` 和 `LiveCloseMessage` 中使用 `{images}` 不会报错，但也不会产生图片消息段。

## 维护要求

- 新增占位符前，先判断它是“文本替换”还是“消息段控制”；不要把两类逻辑混进同一个替换分支。
- 不要把只适用于某一种消息的数据，写成所有类型共享的“通用占位符”。
- 如果改变 `{uid}` 与 `{mid}` 的等价关系，必须同步更新所有说明文案和用户帮助。
- 如果改变默认兜底模板或 `{draw}` 失败时的兜底文案，必须检查消息发送回退路径是否仍然可读。
