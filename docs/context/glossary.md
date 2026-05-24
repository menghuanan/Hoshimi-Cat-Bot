# 术语表

## Adapter

平台适配器，实现 `PlatformAdapter`，把具体平台协议转换为项目平台中立模型。

## BiliClient

B 站 HTTP 客户端，封装 Ktor/OkHttp、超时、重试、代理、Cookie 和运行态观测。

## BiliConfigManager

旧主配置和业务数据 manager，负责 `BiliConfig.yml` 和 `BiliData.yml`。

## ConfigManager

平台配置 manager，负责 `bot.yml`。

## DrawingSession

单次 Skia 绘图会话，负责追踪和关闭本次绘图创建的 native 资源。

## Gateway

消息发送网关。业务层通过 `MessageGatewayProvider` 获取 gateway，而不是直接调用平台 adapter。

## Managed

Skiko 中包装 native 资源的对象类型。关闭后不得继续引用或使用。

## OneBot11

QQ 机器人常见协议之一。本项目支持 generic OneBot11，也支持 NapCat、LlBot 等 vendor 实现。

## OutgoingPart

平台中立的出站消息片段，包括文本、图片、@全体和回复。

## PlatformContact

平台中立联系人，包含平台类型、聊天类型和字符串 ID。

## ProcessGuardian

系统守护 Tasker，负责任务健康、内存、平台连接、channel 背压和资源快照监控。

## ResourceSupervisor

停机资源总管，按阶段回收入口、worker、channel、依赖和根协程作用域。

## Subject

项目内常用的联系人字符串表示，例如 `onebot11:group:123`。

## Tasker

后台任务抽象，继承 `BiliTasker`，用于周期轮询、消息消费、发送、清理和守护。

## TemplateRuntimeCoordinator

模板策略与运行态缓存协调器，负责串行读写策略、随机选择、last-used 和 batch 缓存。

