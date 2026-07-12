# Service 模块

**覆盖度**：核心流程 ✅ | 完整参数 ⚠️ | 边界情况 ⚠️

## 模块定位

Service 模块是业务编排层，负责命令、订阅、模板、链接解析、消息网关、缓存维护、功能开关和启动初始化。

## 代码入口

- `src/main/kotlin/top/bilibili/service/*`
- 关键服务：`MessageEventDispatchService`、`BiliCommandDispatchService`、`TemplateRuntimeCoordinator`、`TemplateSelectionService`、`TemplateRenderService`、`ResolveLinkService`、`MessageGatewayProvider`

## 主要职责

- 将平台入站消息路由到命令或链接解析。
- 管理订阅、分组、过滤器、@全体、模板和颜色配置。
- 渲染发送消息段。
- 统一发送网关。
- 启动数据初始化和运行期预热。

## 子域约束

| 子域 | 代码入口 | 关键约束 |
| --- | --- | --- |
| 入站分发 | `MessageEventDispatchService`、`MessageCommandRouterService` | 只能把平台中立消息路由到命令或链接解析，不直接写 vendor 协议。 |
| `/bili` 命令 | `BiliCommandDispatchService`、各 `*CommandService` | 命令名、别名、帮助文案和权限判断必须同步维护。 |
| 订阅与分组 | `SubscriptionCommandService`、`GroupCommandService`、`QuickSubscriptionService` | 写入必须走配置/数据协调入口，群管理员不得跨群写。 |
| 模板链路 | `TemplateRuntimeCoordinator`、`TemplateSelectionService`、`TemplateRenderService`、`TemplateService` | 策略写入、随机选择、占位符和运行态缓存必须保持一致。 |
| 链接解析 | `ResolveLinkService`、`LinkResolvePolicyService` | 群内放行必须先过统一策略，不得在入口绕过限流和黑名单。 |
| 发送网关 | `MessageGatewayProvider`、`DefaultMessageGateway`、`PlatformMessageSupport` | 发送必须走平台能力判断和降级，不得直接调用 vendor client。 |
| 二维码登录 | `LoginService`、`QrLoginCoordinator` | 命令端和 WebUI 共用单一会话；核心凭据提交无挂起且不可取消，成功后的网络刷新独立限时执行。 |
| 启动与维护 | `StartupDataInitService`、`RuntimeWarmupService`、`CacheMaintenanceService` | 只做初始化、预热或维护，不承载命令权限或平台协议。 |

## 关键流程

平台入站消息先由 connector 转成中立模型，再进入 service 的消息分发；命令路径按快捷命令或 `/bili` 子命令执行权限校验和数据写入；非命令链接路径由统一解析策略决定是否绘图和发送；推送路径由 tasker 构造消息身份后进入模板选择、模板渲染和 gateway 发送。

## 模板规则

模板选择分层：

1. `SendTasker` 构造消息身份和候选 scope。
2. `TemplateSelectionService` 选择最终模板名和正文。
3. `TemplateRuntimeCoordinator` 串行处理策略、随机选择、last-used 和 batch 缓存。
4. `TemplateRenderService` 替换占位符并生成 `OutgoingPart`。

模板正文里的占位符是业务契约，完整清单与按消息类型的适用范围见 [`../domain/template-placeholders.md`](../domain/template-placeholders.md)。

维护模板链路时还要同步注意：

- 只允许 `TemplateRenderService` 扩展或删除占位符；新增占位符后必须同步更新 `/bili template explain`。
- 模板拆段使用 `\r`，不是 `\n`。改动拆段规则前必须检查预览、帮助文案和现有用户模板兼容性。
- 未识别占位符会按普通文本原样发送，不会自动报错或自动删除。

**禁止**：命令服务直接修改模板策略后不清理运行态缓存。

## 命令路由与权限矩阵

### 快捷命令入口

`MessageCommandRouterService` 维护的快捷命令是独立于 `/bili` 的兼容入口，不能随意并入或删除别名。

| 会话 | 命令 | 权限 | 实际动作 |
| --- | --- | --- | --- |
| 群聊 | `/login`、`登录` | 仅超级管理员 | 调用 `LoginService.login(groupContact)` |
| 群聊 | `/add <UID>` | 仅超级管理员 | 快速订阅当前群 |
| 群聊 | `/del <UID>` | 仅超级管理员 | 快速取消订阅当前群 |
| 群聊 | `/list` | 仅超级管理员 | 查看当前群快捷订阅列表 |
| 群聊 | `/black list` | 仅超级管理员 | 查看链接解析黑名单 |
| 群聊 | `/black <联系人>` | 仅超级管理员 | 快速加入链接解析黑名单 |
| 群聊 | `/unblock <联系人>` | 仅超级管理员 | 快速移出链接解析黑名单 |
| 群聊 | `/check` | 仅超级管理员 | 触发 `DynamicCheckTasker.executeManualCheck()` |
| 群聊 | `/bili ...` | 超级管理员或本群普通管理员 | 转入 `BiliCommandDispatchService` |
| 私聊 | `/login`、`登录` | 仅超级管理员 | 调用 `LoginService.login(userContact)` |
| 私聊 | `/add <UID>` | 仅超级管理员 | 快速订阅当前私聊联系人 |
| 私聊 | `/del <UID>` | 仅超级管理员 | 快速取消订阅当前私聊联系人 |
| 私聊 | `/list` | 仅超级管理员 | 查看当前私聊联系人订阅列表 |
| 私聊 | `/bili ...` | 仅超级管理员 | 转入 `BiliCommandDispatchService` |

当前实现中，黑名单快捷命令和 `/check` 只在群聊入口注册；私聊路由不会兜底支持这些命令。

### `/bili` 顶层命令契约

`BiliCommandProcessor` 只负责解析 `/bili` 后的第一个子命令和顶层别名。修改命令名、别名或分支顺序时，必须同步检查帮助文案、命令服务和回归测试。

| 子命令 | 顶层别名 | 群聊可达权限 | 私聊可达权限 | 额外限制 |
| --- | --- | --- | --- | --- |
| `add` | 无 | 超级管理员、本群普通管理员 | 超级管理员 | 普通管理员只能操作当前会话；传第三个参数指定目标群时仅超级管理员允许 |
| `remove` | `rm` | 超级管理员、本群普通管理员 | 超级管理员 | 与 `add` 相同 |
| `list` | `ls` | 超级管理员、本群普通管理员 | 超级管理员 | `list <UID\|ss\|md\|ep>` 查询跨群推送范围时仅超级管理员允许 |
| `color` | 无 | 仅超级管理员 | 仅超级管理员 | 等价于 `config color` 的独立入口 |
| `groups` | 无 | 仅超级管理员 | 仅超级管理员 | 列出全部分组 |
| `group` | 无 | 仅超级管理员 | 仅超级管理员 | 子命令别名：`delete/del`、`remove/rm`、`list/ls`、`subscribe/sub`、`unsubscribe/unsub` |
| `filter` | 无 | 超级管理员、本群普通管理员 | 超级管理员 | 子命令别名：`list/ls`、`del/delete/rm` |
| `template` | `tpl` | 超级管理员、本群普通管理员 | 超级管理员 | 子命令别名：`list/ls`、`preview/pv`、`del/delete/rm`、`explain/exp` |
| `atall` | `aa` | 超级管理员、本群普通管理员 | 超级管理员 | 子命令别名：`add/set`、`del/remove/rm`、`list/ls`；实际写入仅群聊有意义 |
| `config` | `cfg` | 超级管理员、本群普通管理员 | 超级管理员 | `config color` 仅超级管理员允许，普通 `config [uid]` 可由群管理员查看 |
| `admin` | 无 | 仅超级管理员 | 仅超级管理员 | 子命令别名：`remove/rm`、`list/ls`；`add/remove/list` 仅群聊可执行，`all` 可在群聊或私聊查询全部普通管理员 |
| `blacklist` | `bl` | 仅超级管理员 | 仅超级管理员 | 子命令别名：`remove/rm/del`、`list/ls` |
| `help` | 无 | 超级管理员、本群普通管理员 | 超级管理员 | 帮助内容会按权限返回不同版本 |

### 权限边界约束

- 超级管理员判断统一走 `CommandPermission.isSuperAdmin(senderContact)`，数据来源是规范化后的管理员 subject。
- 群普通管理员判断统一走 `CommandPermission.isGroupAdmin(groupContact, senderContact)`，长期约束是只对当前群生效，不得复用到跨群写操作。
- `template ... group <分组名>` 在 `TemplateService` 默认校验当前群属于目标分组；只有超级管理员入口会显式放宽跨分组维护，普通群管理员不得跨群读写分组模板策略。
- `/bili` 的“可路由”不等于“可完整执行”。例如群管理员可以进入 `config`、`list`、`add`，但仍会在具体服务里被限制跨群参数或超管专属子功能。
- 帮助文案、命令解析器和各命令服务的权限判断必须保持一致；只改其中一处会造成“命令可见但不可用”或“绕过帮助暴露超权命令”。

## 链接解析规则

链接解析由 `ResolveLinkService` 统一维护正则、短链解析、结果排序和绘图入口。群内实际放行由 `LinkResolvePolicyService` 决定。

**禁止**：在消息入口绕过统一策略直接调用 B 站详情 API 并发送。

## 平台发送规则

发送必须走：

- `MessageGatewayProvider.require()`
- `DefaultMessageGateway`
- `PlatformCapabilityService`

**禁止**：service 直接调用 vendor client。

## 禁止事项

- 禁止 service 直接依赖 NapCat、LlBot、OneBot11 或 QQ Official vendor DTO。原因：平台差异必须停留在 connector。
- 禁止绕过 `CommandPermission` 新增命令权限判断。原因：帮助文案、路由和执行权限会分裂。
- 禁止绕过 `TemplateRuntimeCoordinator` 写模板策略。原因：运行态缓存、随机选择和持久化会不同步。
- 禁止绕过 `MessageGatewayProvider` 或 `PlatformCapabilityService` 发送消息。原因：平台能力降级和停机边界会失效。
- 禁止在 service 中创建长期协程、裸网络客户端或 native 资源。原因：这些生命周期应归 core、client、tasker、draw 或 skia。

## 资源与生命周期

Service 通常不拥有长期协程或底层连接，但会协调模板缓存、绘图缓存键、消息网关引用和启动预热。新增缓存、批处理状态或维护任务必须说明清理入口、所属模块和停机行为；需要周期运行的逻辑应进入 tasker。

`QrLoginCoordinator` 是例外的共享业务 worker owner：worker 仍使用 Bot 根作用域，但关闭权属于 Core 的 `qr-login-workers` 分区。二维码等待租约为 180 秒；`COMMITTING` 只覆盖同步核心凭据提交，不返回伪造 retryAfter；`initTagid()` 属于成功后的 best-effort 刷新，超时或停机取消不得重新占用登录互斥。

管理员通知由 `AdminNoticeService.kt` 统一执行功能开关、联系人能力判断和网关发送；utils 只保留纯辅助逻辑。通用消息日志简化规则位于 connector 根包，service 不得反向依赖 OneBot11 core。

## 配置与数据

Service 可以通过 manager 或协调器修改订阅、分组、过滤、模板、颜色、@全体、黑名单和功能开关，但不得直接写 YAML。新增可配置行为必须同步 config/data/domain 文档，说明默认值、权限边界、迁移和热重载影响。

## 测试与验证

- 修改命令路由、权限或帮助文案后，运行 `CommandRegressionGuardTest`、相关 `*CommandServiceTest` 和权限测试。
- 修改模板策略、占位符或渲染后，运行 `TemplateRuntimeCoordinatorTest`、`TemplateRenderServiceTest`、`TemplateSelectionServiceTest`。
- 修改链接解析或发送降级后，运行 `LinkResolvePolicyServiceTest`、`ResolveLinkServiceOrderingTest`、平台能力和 message gateway 测试。
- 修改订阅、分组、过滤、颜色或 @全体后，运行对应 service feature/regression 测试，并检查配置迁移影响。

## 查询 checklist

- [ ] 是否已阅读根目录 `AGENTS.md` 与 `docs/AGENTS.md`？
- [ ] 是否确认查询对象属于本模块，而不是相邻模块、历史计划或过期文档？
- [ ] 是否阅读本文档列出的代码入口、禁止事项和相关 domain/architecture 文档？
- [ ] 是否区分当前实现、阶段性计划和过期记录？
## 变更 checklist

- [ ] 是否涉及配置写入？若是，是否走 manager？
- [ ] 是否涉及模板策略？若是，是否走 `TemplateRuntimeCoordinator`？
- [ ] 是否新增、删除或改名占位符？若是，是否同步更新 [`../domain/template-placeholders.md`](../domain/template-placeholders.md) 与 `/bili template explain`？
- [ ] 是否涉及平台能力？若是，是否走 `PlatformCapabilityService`？
- [ ] 是否涉及快捷命令或 `/bili` 子命令？若是，是否同步更新命令矩阵、帮助文案和别名处理？
- [ ] 是否涉及链接解析？若是，是否走 `LinkResolvePolicyService`？
- [ ] 是否运行相关 service 测试，如 `CommandRegressionGuardTest`、`TemplateRuntimeCoordinatorTest`、`LinkResolvePolicyServiceTest`？
## 新建 checklist

- [ ] 新文件是否优先归入本模块既有入口，而不是新增顶层包？
- [ ] 新函数、方法或逻辑块是否补充紧邻注释，说明用途、意图或关键约束？
- [ ] 新配置、数据结构、资源、协程、客户端、缓存、channel 或 native 对象是否有明确生命周期和归属边界？
- [ ] 新外部行为是否同步更新相关 domain、architecture、development 或 operations 文档？
- [ ] 新测试是否只验证源码行为或产物，不复制项目文档内容？
