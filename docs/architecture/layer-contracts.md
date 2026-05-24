# 层职责边界与跨层规则

本文定义各层允许做什么、禁止做什么，以及跨层调用的正确路径。

## 启动层

**入口**：`Main.kt`、`BiliBiliBot`

**允许**：

- 初始化 Skiko、配置、平台连接、消息网关和后台任务。
- 注册 shutdown hook。
- 注册 `ResourceSupervisor` 分区。

**禁止**：

- 在启动层直接实现平台 vendor 协议细节。
- 在启动层手写业务命令逻辑。

**原因**：启动层只负责组装和生命周期，业务规则应留在 service/tasker。

**详见**：[`modules/bootstrap.md`](../modules/bootstrap.md)、[`modules/core.md`](../modules/core.md)。

## Core 资源协调层

**入口**：`core/*`、`core/resource/*`

**允许**：

- 装配 bot 根生命周期、channel、命令入口、资源分区和 tasker 策略。
- 协调 `ResourceSupervisor`、`BusinessLifecycleManager` 和 `TaskResourcePolicyRegistry`。
- 暴露运行期资源边界给 tasker、monitoring 和停机流程。

**禁止**：

- 在 core 中实现具体业务命令规则。
- 在 core 中写 vendor 协议或绕过 connector 发送消息。
- 新增长期资源但不登记关闭路径、分区和 strictness。

**原因**：core 是运行期骨架，不是业务或协议实现层。

**详见**：[`modules/core.md`](../modules/core.md)。

## 配置层

**入口**：`BiliConfigManager`、`ConfigManager`、`BotConfigFileStore`

**允许**：

- 创建默认配置。
- 读取、迁移、归一化和保存配置。
- 基于 `dataVersion` 执行业务数据迁移。

**禁止**：

- 业务层直接写配置文件。
- `ConfigManager` 写 `BiliConfig.yml` 或 `BiliData.yml`。
- `BiliConfigManager` 写 `bot.yml`。

**原因**：当前存在旧主配置与新平台配置两套文件，边界混淆会导致迁移覆盖。

## 平台连接层

**入口**：`PlatformConnectorManager`、`PlatformAdapter`

**允许**：

- 创建 vendor adapter。
- 封装 start/stop 生命周期。
- 暴露平台中立事件、发送、能力和观测模型。

**禁止**：

- 把 vendor DTO 暴露给业务层。
- 停机后由发送链路隐式重新创建 adapter。
- 在业务层绕过 manager 调用 transport。

**原因**：manager 内部 `currentAdapter()` 明确避免 stop 后复活实例；绕过会破坏停机语义。

## 服务层

**入口**：`service/*`

**允许**：

- 编排配置、数据、平台能力、绘图和消息发送。
- 实现命令、订阅、模板、链接解析、过滤和功能开关。
- 通过 manager/gateway/capability service 访问平台。

**禁止**：

- 直接创建 vendor adapter 或底层 transport。
- 直接保存配置文件。
- 在模板策略变更时绕过 `TemplateRuntimeCoordinator`。

**原因**：服务层是业务编排层，不是资源拥有者。

## WebUI 管理层

**入口**：`webui/*`、`webui-frontend/*`

**允许**：

- 启动本地管理面嵌入式服务器并托管静态 React shell。
- 通过认证、CSRF、高风险确认和审计记录保护管理 API。
- 通过 `BiliConfigManager`、`ConfigManager`、`TemplateRuntimeCoordinator` 和 service facade 读取或写入受控文件。
- 读取固定白名单日志源、运行态快照和订阅概览。

**禁止**：

- 直接写 `BiliConfig.yml`、`BiliData.yml`、`bot.yml` 或凭据文件。
- 通过任意文件路径读取或清空日志。
- 绕过 session cookie、CSRF、确认密码或审计直接执行高风险动作。
- 把 vendor DTO、平台 transport 或业务可变对象暴露给浏览器。

**原因**：WebUI 是本地运维入口，不是业务层或配置层；它必须把认证、写入、日志和静态资源边界收紧在同一组 facade 里。

## Tasker 层

**入口**：`BiliTasker`、`BiliCheckTasker`、各 `*Tasker`

**允许**：

- 执行周期任务和长生命周期 channel 消费。
- 通过 `launchManagedWorker` 注册可恢复 worker。
- 使用 `runBusinessOperation` 包装业务 tick。

**禁止**：

- 裸 `GlobalScope.launch` 或无父 Job 协程。
- 新增未登记资源策略的 tasker。
- 在停机阶段启动新任务或创建新共享客户端。

**原因**：Tasker 健康、停机和资源追踪都依赖 `BiliTasker` 的结构化生命周期。

## Skia 与绘图层

**入口**：`SkiaManager`、`DrawingSession`、`DrawingQueueManager`、`FontManager`

**允许**：

- 通过绘图队列限制并发。
- 在 `DrawingSession` 中创建并追踪 native 资源。
- 由 `SkiaCleanupTasker` 周期清理缓存。

**禁止**：

- 绘图热路径绕过 `SkiaManager.executeDrawing`。
- 会话内资源逃逸到全局缓存。
- close 后继续使用 `Managed` 对象。

**原因**：Skia native 资源不是普通 JVM 对象，必须显式管理。

## API/Client 层

**入口**：`BiliClient`、`api/*`

**允许**：

- 封装 B 站 API 地址、参数、超时、重试、代理和 trace。
- 解析响应为 data 模型。

**禁止**：

- 在 API 层发送平台消息。
- 在 API 层读取或修改订阅数据。
- close 后复用 `BiliClient`。

**原因**：API 层应保持纯外部请求封装，避免网络失败影响业务状态一致性。

## Data 层

**入口**：`data/*`、`BiliData.kt`

**允许**：

- 定义序列化模型、业务消息模型和轻量派生属性。
- 对未知枚举或可选字段提供安全回退。

**禁止**：

- 调用 service/tasker。
- 发起网络请求。
- 写配置文件。

**原因**：data 层必须保持低依赖，避免序列化模型变成业务执行入口。

## Utils 层

**入口**：`utils/*`

**允许**：

- 提供低依赖、可复用、调用方可控的轻量工具。
- 封装字体、图片预处理、联系人 subject 和 JSON 复用实例等辅助能力。

**禁止**：

- 反向依赖 service/tasker 或隐藏业务编排。
- 直接写配置文件或发送平台消息。
- 持有未登记的长期协程、网络客户端、native 资源或缓存。

**原因**：工具层容易演变成隐式全局状态，必须保持低依赖和可回收。

**详见**：[`modules/utils.md`](../modules/utils.md)。

## Resources 层

**入口**：`src/main/resources/*`

**允许**：

- 提供运行时必需字体、图标、兜底图片和日志配置。
- 保持资源路径、授权说明和代码加载入口一致。

**禁止**：

- 把临时输出、截图产物或调试缓存打入发行包。
- 替换资源但不同步授权、代码引用、绘图验证和包体影响说明。
- 在日志配置中输出敏感凭据。

**原因**：resources 会随发行包分发，并影响绘图、日志和内存行为。

**详见**：[`modules/resources.md`](../modules/resources.md)。

## 跨层调用速查

| 需求 | 正确入口 | 禁止路径 |
| --- | --- | --- |
| 发送消息 | `MessageGatewayProvider.require()` | 直接调用 vendor client |
| 判断平台能力 | `PlatformCapabilityService` | 业务层手写 adapter 类型判断 |
| 修改模板策略 | `TemplateRuntimeCoordinator` | 直接改 `BiliData.*PolicyByScope` |
| 绘制图片 | `SkiaManager.executeDrawing` | 裸建 Skia native 对象 |
| 保存主配置/数据 | `BiliConfigManager` | 业务层写 YAML |
| 保存平台配置 | `ConfigManager` | 业务层写 `bot.yml` |
| WebUI 管理配置/日志 | `WebUiConfigFacade`、`WebUiConfigWriteFacade`、`WebUiLogFacade`、`WebUiAuditService` | 直接写 YAML、直读任意日志文件、绕过确认或审计 |
| 新增后台任务 | `BiliTasker` + `TaskResourcePolicyRegistry` | 裸协程或未登记 tasker |
| 新增启动逻辑 | `Main.kt` / `BiliBiliBot` 装配入口 | 在启动层写业务规则 |
| 新增共享工具 | `utils/*` 低依赖工具 | 在 utils 中隐藏 service/tasker 调用 |
| 新增运行资源 | `src/main/resources/*` + 对应加载工具 | 提交临时产物或无授权资源 |
| 修改 CI/发布 | Gradle / workflow / Docker 入口 | 只改脚本不更新部署约束 |
