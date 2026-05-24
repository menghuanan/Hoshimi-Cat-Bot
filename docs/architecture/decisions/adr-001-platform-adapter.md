# ADR-001: 平台适配器统一收口到 PlatformConnectorManager

**状态**：已采纳  
**日期**：2026-04-22  
**决策者**：项目当前实现  

## 问题背景

项目最初主要面向 OneBot11/NapCat，但当前已经出现 generic OneBot11、NapCat、LlBot 和 QQ 官方机器人等多个平台入口。如果业务层直接依赖某个 vendor 的事件模型、发送模型或连接对象，后续每新增一个平台都需要在命令、发送、订阅和能力判断中加入平台分支。

同时，平台连接有明确生命周期：需要先创建 adapter、订阅 eventFlow、注册发送网关，再启动连接；停机时必须停止入口并避免发送链路把已关闭 adapter 重新拉起。

## 考察的方案

1. **业务层直接按平台分支调用 vendor adapter**  
   **否决**：短期简单，但会让业务服务到处出现 NapCat/OneBot11/QQOfficial 类型，平台替换成本高。

2. **只抽象发送，不抽象事件和能力**  
   **否决**：命令入口、@全体、图片发送、回复能力仍需要平台分支，抽象不完整。

3. **统一 `PlatformAdapter` 接口，由 `PlatformConnectorManager` 管理创建和生命周期**  
   **采纳**：业务层只消费平台中立模型，manager 封装 adapter 选择、缓存、启动、停止和运行态观测。

## 决策

平台接入统一采用以下结构：

- `PlatformAdapter` 定义平台中立事件、发送、能力、运行态和可达性接口。
- `PlatformConnectorManager` 根据 `BotConfig.selectedAdapterKind()` 创建具体 adapter。
- 业务层发送消息走 `MessageGatewayProvider` 或 `BiliBiliBot.sendMessage(contact, parts)`。
- 能力判断走 `PlatformCapabilityService` 和 `CapabilityGuard`。
- 入站事件统一为 `PlatformInboundMessage`，发送出站统一为 `OutgoingPart`。
- vendor DTO 只允许存在于 connector vendor/core 包内部。

## 已知权衡

- 迁移期仍保留 deprecated Long 群号/私聊入口，以兼容历史调用链。
- Generic OneBot11 采用保守能力声明，例如默认不支持 @全体，本地/二进制图片会降级。
- 新增平台时需要先补齐平台中立模型映射，不能直接把 vendor 能力暴露给业务层。

## 若要修改此决策

必须同步更新：

- [`../layer-contracts.md`](../layer-contracts.md)
- [`../invariants.md`](../invariants.md) 中 `INV-004`
- [`../../development/red-lines.md`](../../development/red-lines.md) 中 `RL-004`、`RL-005`
- [`../../modules/connector.md`](../../modules/connector.md)
- 平台相关测试，如 `PlatformConnectorManagerTest`、`ConnectorBoundaryRegressionTest`

