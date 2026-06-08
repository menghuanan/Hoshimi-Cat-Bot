# Connector 模块

## 模块定位

Connector 模块把平台 vendor 协议转换为项目平台中立模型，并统一管理平台 adapter 生命周期、能力判断和运行态观测。

## 代码入口

- `src/main/kotlin/top/bilibili/connector/PlatformAdapter.kt`
- `src/main/kotlin/top/bilibili/connector/PlatformConnectorManager.kt`
- `src/main/kotlin/top/bilibili/connector/PlatformModels.kt`
- `src/main/kotlin/top/bilibili/connector/CapabilityGuard.kt`
- `src/main/kotlin/top/bilibili/connector/PlatformCapabilityService.kt`
- `src/main/kotlin/top/bilibili/connector/onebot11/*`
- `src/main/kotlin/top/bilibili/connector/qqofficial/*`

## 主要职责

- 定义平台中立联系人、消息、图片来源和能力模型。
- 根据配置创建具体 adapter。
- 管理 adapter start/stop 生命周期。
- 防止停机后发送链路隐式重新拉起 adapter。
- 聚合 transport 观测信息。

## 子域约束

| 子域 | 代码入口 | 关键约束 |
| --- | --- | --- |
| 平台中立模型 | `PlatformAdapter.kt`、`PlatformModels.kt`、`PlatformCapability.kt` | 只能暴露 `PlatformContact`、`PlatformInboundMessage`、`OutgoingPart` 等中立类型。 |
| 生命周期管理 | `PlatformConnectorManager.kt` | 只能由 manager 创建、启动、停止和清空 adapter。 |
| OneBot11 通用层 | `onebot11/core/*`、`onebot11/generic/*` | 只承载协议通用传输与转换，不能写入业务配置或订阅数据。 |
| NapCat vendor | `onebot11/vendors/napcat/*` | vendor DTO 只能停留在 vendor 包内，出包前必须转换为中立模型。 |
| LlBot vendor | `onebot11/vendors/llbot/*` | vendor 差异只能通过 adapter/transport 封装，不得泄露到 service/tasker。 |
| QQ Official | `qqofficial/*` | 新能力必须补齐 `declaredCapabilities()` 和发送降级路径。 |

## 当前适配器矩阵

| adapter kind | 平台类型 | 代码入口 | 发送/接收边界 | 当前能力要点 |
| --- | --- | --- | --- | --- |
| `ONEBOT11` | `ONEBOT11` | `GenericOneBot11Adapter`、`KtorOneBot11Transport`、`OneBot11Models.kt` | 通用 OneBot11 WebSocket 与 action 请求 | 保守支持文本发送；图片和 @全体按能力 guard 降级 |
| `NAPCAT` | `ONEBOT11` | `NapCatAdapter`、`NapCatClient`、`OneBotModels.kt` | NapCat vendor DTO 只停留在 vendor 包内 | 支持 NapCat 配置、sendMode、连接重试和运行态观测 |
| `LLBOT` | `ONEBOT11` | `LlBotAdapter`、`LlBotClient`、`LlBotTransport`、`LlBotModels.kt` | LlBot vendor 模型只在 vendor 包内转换 | 与 NapCat 分离 client/transport，业务层只看中立能力 |
| `QQ_OFFICIAL` | `QQ_OFFICIAL` | `QQOfficialAdapter`、`QQOfficialTransport`、`QQOfficialConfig` | 非 OneBot11 平台，必须使用 `PlatformContact` 字符串 ID | 新能力必须显式声明，并提供不支持时的降级或拒绝结果 |

## 运行态观测

- `PlatformRuntimeStatus` 只描述连接是否可用和重连次数，供 Dashboard、ProcessGuardian 和业务 guard 使用。
- `PlatformObservabilitySnapshot` 聚合 adapter、transport、WebSocket session、HTTP client、dispatcher 和 note；实现层需要清洗或避免暴露 token、header、完整 URL 中的敏感参数。
- 新增 transport 或 vendor client 时，必须提供 `runtimeObservability()`，即使当前只能返回 `PlatformObservabilitySnapshot.empty(note)`，也要说明不可观测原因。
- 停机后 `PlatformConnectorManager.currentAdapter()` 不得隐式创建新实例；发送失败应表现为平台未初始化或不可用，而不是重新拉起连接。

## 生命周期规则

`PlatformConnectorManager` 状态：

- `IDLE`
- `STARTING`
- `STARTED`
- `STOPPING`

`initialize()` 只创建并缓存 adapter；`start()` 负责启动底层连接；`stop()` 停止 adapter 并清空引用。

## 关键流程

启动层调用 `PlatformConnectorManager.initialize()` 选择平台并创建 adapter，随后由 `start()` 建立底层连接；入站消息转换为 `PlatformInboundMessage` 后交给 service，出站消息由 gateway 和 capability service 转为 vendor 请求；停机时统一走 `stop()`，不得由业务层直接关闭 vendor client。

## 资源与生命周期

Connector 拥有平台 adapter、transport、WebSocket/session 和 vendor client 的生命周期。所有长期连接必须能被 `PlatformConnectorManager.stop()` 回收，并能被 `ProcessGuardian` 观测；新增连接、队列或重连状态必须说明关闭和快照路径。

## 配置与数据

Connector 只读取 `bot.yml` 中的平台配置、targets 和管理员配置，不写业务数据。新增平台配置字段必须更新 config 文档、平台领域文档和兼容迁移逻辑。

## 测试与验证

- 修改平台中立模型或能力判断后，运行 `PlatformModelSmokeTest`、`CapabilityGuardTest` 和 `ConnectorBoundaryRegressionTest`。
- 修改 manager 生命周期后，运行 `PlatformConnectorManagerTest` 和停机/重连相关测试。
- 修改 vendor adapter 后，运行对应 `OneBot11AdapterTest`、`LlBotAdapterTest`、`QQOfficialAdapterTest` 或 NapCat 回归测试。

## 禁止事项

- 禁止向业务公开 vendor DTO。
- 禁止业务层直接 new adapter。
- 禁止在 `stop()` 后通过发送路径调用 `adapter()` 创建新实例。
- 禁止能力判断散落在业务层。

## 查询 checklist

- [ ] 是否已阅读根目录 `AGENTS.md` 与 `docs/AGENTS.md`？
- [ ] 是否确认查询对象属于本模块，而不是相邻模块、历史计划或过期文档？
- [ ] 是否阅读本文档列出的代码入口、禁止事项和相关 domain/architecture 文档？
- [ ] 是否区分当前实现、阶段性计划和过期记录？
## 变更 checklist

- [ ] 新平台是否实现 `PlatformAdapter`？
- [ ] 是否补齐 `declaredCapabilities()`？
- [ ] 入站消息是否转换为 `PlatformInboundMessage`？
- [ ] 出站消息是否只消费 `OutgoingPart`？
- [ ] 是否更新 [`../domain/platform-adapters.md`](../domain/platform-adapters.md)？
- [ ] 是否运行 `PlatformConnectorManagerTest`、`CapabilityGuardTest`、`ConnectorBoundaryRegressionTest`？
## 新建 checklist

- [ ] 新文件是否优先归入本模块既有入口，而不是新增顶层包？
- [ ] 新函数、方法或逻辑块是否补充紧邻注释，说明用途、意图或关键约束？
- [ ] 新配置、数据结构、资源、协程、客户端、缓存、channel 或 native 对象是否有明确生命周期和归属边界？
- [ ] 新外部行为是否同步更新相关 domain、architecture、development 或 operations 文档？
- [ ] 新测试是否只验证源码行为或产物，不复制项目文档内容？
