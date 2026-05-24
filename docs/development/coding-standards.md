# Kotlin 编码规范

本文记录本项目在通用 Kotlin 之外的专属编码要求。

## 编码与文件

- 所有文本文件必须使用 UTF-8。
- PowerShell 读取/写入必须显式使用 `-Encoding UTF8`。
- 仓库 `.editorconfig` 已声明 Kotlin、Markdown、YAML、XML、Shell、Gradle 等常见文本文件为 UTF-8。
- 不得删除、减少、绕过或覆盖已有注释。
- 新增函数、方法或新的代码逻辑块时，必须补充紧邻代码的说明性注释。
- 修改函数签名时，必须同步更新 KDoc。

## Kotlin 风格

- JVM target 17。
- Kotlin 2.0.0。
- 行宽按 `.editorconfig`：120。
- 缩进 4 空格。
- 优先使用现有注释风格，避免空泛注释。

## 协程

- 后台任务必须挂在 `BiliTasker` 或 `BiliBiliBot` 的结构化作用域下。
- 长生命周期子循环必须使用 `launchManagedWorker`。
- 停机路径要识别 `CancellationException` 并避免误报 ERROR。

## 资源

- `AutoCloseable`、`Closeable`、Skia native 资源必须明确关闭。
- `BiliClient` close 后不得复用。
- Skia 绘图必须使用 `DrawingSession`。
- 新增共享资源要注册到 `ResourceSupervisor` 或明确说明生命周期。

## 配置

- 不直接写 YAML。
- 主配置/业务数据走 `BiliConfigManager`。
- 平台配置走 `ConfigManager`。
- 模板策略走 `TemplateRuntimeCoordinator`。

## 平台抽象

- 业务层只使用 `PlatformContact`、`PlatformInboundMessage`、`OutgoingPart`、`ImageSource`。
- vendor DTO 只允许在 connector 实现内部。
- 能力判断统一走 `PlatformCapabilityService`。

## 日志

- 用户可理解的关键路径日志保留中文风格。
- 网络请求应包含任务来源和接口名。
- 停机期间的预期取消不要升级为 ERROR。
- 资源清理失败要带 owner/operation 或资源分区信息。

