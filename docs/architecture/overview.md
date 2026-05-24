# 架构总览

本文描述当前 dynamic-bot 的静态结构、运行期启动流程和主要依赖方向。内容以现有 Kotlin/JVM 代码为准。

## 系统定位

dynamic-bot 是一个常驻运行的 B 站动态/直播推送机器人。它通过 B 站 API 轮询动态和直播状态，通过平台适配层接入 OneBot11/NapCat/LlBot/QQ 官方等消息平台，并使用 Skia/Skiko 生成图文推送卡片。

## 运行期主链路

1. `Main.kt` 调用 `SkikoInitializer.initialize()`，注册 shutdown hook，然后启动 `BiliBiliBot.start()`。
2. `BiliBiliBot.start()` 先初始化旧主配置与数据 `BiliConfigManager.init()`，再初始化平台配置 `ConfigManager.init()`。
3. `PlatformConnectorManager` 根据 `BotConfig.selectedAdapterKind()` 创建并缓存平台 adapter。
4. `MessageGatewayProvider` 注册统一发送网关，业务层通过 gateway 发送平台中立消息。
5. `TaskBootstrapService.startTasks()` 按固定顺序启动监听、轮询、消息、发送、清理和守护任务。
6. Tasker 通过 channel、service 和 B 站 API 生成消息；绘图任务经 `SkiaManager.executeDrawing()` 进入 `DrawingSession`。
7. 停机时 `ResourceSupervisor.stopAll()` 按阶段回收入口、worker、channel、底层依赖和根协程作用域。

## 主要层次

### 启动与生命周期层

**代码位置**：`top.bilibili.MainKt`、`top.bilibili.core.BiliBiliBot`

**职责**：进程入口、全局协程作用域、配置加载、平台连接、任务启动、停机资源分区。

**关键约束**：不要在业务模块中绕过 `BiliBiliBot` 直接启动平台 adapter 或 Tasker 集合。

### 配置与数据层

**代码位置**：`BiliConfigManager`、`BiliConfig.kt`、`BiliData.kt`、`config/*`

**职责**：旧主配置、业务数据、平台配置、配置迁移、数据版本迁移、联系人 subject 归一。

**关键约束**：配置文件写入只能走 manager。`BiliConfigManager` 和 `ConfigManager` 的文件边界不同，不可混用。

### 平台连接层

**代码位置**：`connector/*`

**职责**：把不同平台的传输、事件和发送能力统一为 `PlatformAdapter`、`PlatformContact`、`PlatformInboundMessage`、`OutgoingPart`。

**关键约束**：vendor DTO 不得泄露到业务 service 或 tasker。

### 业务服务层

**代码位置**：`service/*`

**职责**：命令处理、订阅管理、链接解析、模板选择、模板渲染、消息网关、功能开关、缓存维护。

**关键约束**：服务层可以编排配置、数据、平台能力和绘图，但不能直接创建底层 adapter 或绕过配置写入边界。

### WebUI 管理层

**代码位置**：`webui/*`、`webui-frontend/*`

**职责**：本地管理面、静态 React shell、认证与会话、运行态与配置查询、订阅编辑、日志查看和高风险动作入口。

**关键约束**：WebUI 只能通过 facade 和 manager 访问配置、数据和日志，不能直写文件、直读任意路径或绕过确认审计。

### Tasker 层

**代码位置**：`tasker/*`

**职责**：周期轮询、消息流水线、发送队列、缓存/日志清理、Skia 清理、进程守护。

**关键约束**：所有后台任务必须继承 `BiliTasker`，新增任务必须声明资源策略。

### Skia 绘图层

**代码位置**：`skia/*`、`draw/*`、`draw/FontManager.kt`

**职责**：绘图队列、绘图会话、字体资源、图片卡片生成、Skia 全局缓存清理。

**关键约束**：native 资源必须绑定 `DrawingSession` 或明确全局生命周期。

### API 与客户端层

**代码位置**：`client/BiliClient.kt`、`api/*`

**职责**：B 站 HTTP 请求、重试、超时、代理、API trace、运行期连接池观测。

**关键约束**：共享客户端关闭后不得复用；新增 API 应补充 trace source/api，便于日志定位。

### 数据模型层

**代码位置**：`data/*`

**职责**：B 站响应模型、业务消息模型、动态/直播/番剧/用户等结构。

**关键约束**：对 B 站未知枚举或可变字段要平稳降级，不能让单个新类型导致整个轮询链路失败。

## 依赖方向

推荐依赖方向如下：

`Main/BiliBiliBot -> config/connector/service/tasker/skia/client/data`

`tasker -> service -> connector/config/data/client/skia`

`draw -> skia/data/utils`

`api -> client/data`

`connector vendor -> connector core models`

禁止方向：

- `service/tasker` 直接依赖 vendor transport。
- `draw/skia` 直接写配置或业务数据。
- `api/client` 直接发送平台消息。
- `data` 反向依赖 service/tasker。

## 运行期资源观测

`ProcessGuardian` 是当前主要运行期观测入口，覆盖：

- Tasker 健康和 worker 恢复。
- Heap、Metaspace、CodeCache、BufferPool、线程。
- BiliClient retry slot、平台 transport、Skia 队列。
- Linux 下 `/proc/self/status`、`smaps_rollup` 和可降级 NMT summary。
- RSS 软限制和可选重启退出码。

## 架构风险

- Skia worker process 为预留/未实现能力，当前仅支持 in-process。见 [`../bugs.md`](../bugs.md)。
- 平台迁移仍保留部分 deprecated Long 群号/私聊入口，新逻辑应优先使用 `PlatformContact`。
- 主配置与平台配置双 manager 并存，修改配置相关逻辑时必须明确文件归属。
