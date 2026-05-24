# 平台适配器行为差异

本文记录当前平台适配层的共同模型和各平台差异。业务层应依赖这里描述的平台中立语义，而不是 vendor 协议细节。

## 平台中立模型

核心类型位于 `top.bilibili.connector`：

- `PlatformType`：`ONEBOT11`、`QQ_OFFICIAL`
- `PlatformAdapterKind`：`NAPCAT`、`LLBOT`、`ONEBOT11`、`QQ_OFFICIAL`
- `PlatformChatType`：`GROUP`、`PRIVATE`
- `PlatformContact`：平台、聊天类型、字符串 ID
- `PlatformInboundMessage`：平台中立入站消息
- `OutgoingPart`：文本、图片、@全体、回复
- `ImageSource`：本地文件、远程 URL、二进制图片
- `PlatformCapability`：发送消息、发送图片、回复、@全体、链接解析

## 适配器选择

`BotConfig.selectedAdapterKind()` 决定实际 adapter：

- `adapter=napcat` -> `NapCatAdapter`
- `adapter=llbot` -> `LlBotAdapter`
- `adapter=onebot11` 或未知值 -> `GenericOneBot11Adapter`
- `platform.type=qq_official` -> `QQOfficialAdapter`

旧配置兼容：

- 顶层 legacy `napcat` 块只在新版 `platform.onebot11` 仍为默认值时参与回退。
- 保存 `bot.yml` 时只写标准 `platform` 结构，不再写 legacy 顶层 `napcat` 块。

## Generic OneBot11

**定位**：保守、通用的 OneBot11 WebSocket 适配。

**实现入口**：

- `GenericOneBot11Adapter`
- `KtorOneBot11Transport`

**行为**：

- 只接收 `post_type=message` 的事件。
- 发送使用 `send_group_msg` 和 `send_private_msg`。
- 运行态暴露 OkHttp 连接池、dispatcher、WebSocket session。
- `@全体` 直接声明不支持。
- 本地文件或二进制图片发送会返回降级结果，提示调用方使用 fallback。

**约束**：不要假设 generic OneBot11 支持 NapCat 专有能力。

## NapCat

**定位**：OneBot11 vendor 实现，面向 NapCat 能力和发送模式。

**实现入口**：

- `NapCatAdapter`
- `NapCatClient`
- `OneBotModels.kt`

**行为差异**：

- 配置沿用 `NapCatConfig`，包括 host、port、token、sendMode、heartbeat、reconnect。
- `sendMode` 支持 `file` 和 `base64`，默认 `base64`。
- vendor 能力应在 NapCat adapter 内部转换为平台中立能力。

**约束**：NapCat DTO 不得进入 service/tasker 公开接口。

## LlBot

**定位**：OneBot11 vendor 实现，面向 LlBot 差异。

**实现入口**：

- `LlBotAdapter`
- `LlBotClient`
- `LlBotTransport`
- `LlBotModels.kt`

**行为差异**：

- 与 NapCat 同属 OneBot11 适配方向，但 vendor client、transport 和模型独立。
- 业务层不应通过 adapter kind 判断具体发送分支，能力判断应交给 guard。

## QQ 官方机器人

**定位**：非 OneBot11 平台。

**实现入口**：

- `QQOfficialAdapter`
- `QQOfficialTransport`
- `QQOfficialConfig`

**配置要求**：

- `appId` 必填。
- `appSecret` 必填。
- `botToken` 字段存在，但 `validateSelectedPlatform()` 当前只检查 `appId` 和 `appSecret`。

**约束**：不要把 QQ 官方平台适配成 Long 群号模型；必须使用 `PlatformContact`。

## 能力 guard 语义

能力判断统一返回 `CapabilityGuardResult`：

- `Supported`：允许当前操作。
- `Degraded`：当前能力临时不可用，但允许 fallback。
- `Unsupported`：能力不支持，不应继续当前操作。

**规则**：

- 业务层不应手写 `if (adapter is NapCatAdapter)`。
- 发送前能力判断应通过 `PlatformCapabilityService`。
- @全体失败时，`SendTasker` 会降级为普通消息并节流通知管理员。

## 联系人 subject

当前项目推荐统一 subject 格式：

- `onebot11:group:<id>`
- `onebot11:private:<id>`
- 后续平台按 `platform:type:id` 扩展

`normalizeContactSubject()` 和 `parsePlatformContact()` 是联系人字符串归一和解析入口。

## 新增平台 checklist

- [ ] 扩展 `PlatformType` 或 `PlatformAdapterKind`。
- [ ] 实现 `PlatformAdapter`。
- [ ] 在 `PlatformConnectorManager.createPlatformAdapter()` 中接入。
- [ ] 补齐配置模型与 `validateSelectedPlatform()`。
- [ ] 映射入站消息为 `PlatformInboundMessage`。
- [ ] 映射出站 `OutgoingPart` 和 `ImageSource`。
- [ ] 声明 `declaredCapabilities()` 并实现运行时 guard。
- [ ] 增加 connector、capability、message gateway 回归测试。
- [ ] 更新本文件、[`../architecture/decisions/adr-001-platform-adapter.md`](../architecture/decisions/adr-001-platform-adapter.md) 和 [`../modules/connector.md`](../modules/connector.md)。

