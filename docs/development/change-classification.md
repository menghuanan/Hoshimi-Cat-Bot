# 变更分类

本文定义常见变更属于“可运行期重载”“需要重启”“需要迁移”还是“需要部署变更”。

## 可运行期重载

这些变更理论上可通过现有 reload 入口生效，但仍需确认调用方是否重新读取配置。

- `BiliConfigManager.reloadConfig()`
- `BiliConfigManager.reloadData()`
- `BiliConfigManager.reloadAll()`
- `ConfigManager.reload()`

示例：

- 模板文本调整。
- 功能开关调整。
- 链接解析配置调整。
- 部分联系人、分组、过滤器数据调整。

**注意**：存在运行态缓存时必须同步清理，例如模板策略需经 `TemplateRuntimeCoordinator`。

## 需要重启

这些变更需要重启进程：

- 平台类型或 adapter 切换。
- OneBot11/NapCat/LlBot/QQ 官方连接参数变化。
- JVM 参数变化。
- Docker/entrypoint/发行包启动脚本变化。
- Skiko 渲染参数变化。
- `SkiaConfig` 全局策略变化。
- 新增或删除 Tasker。
- 修改 `TaskBootstrapService` 启动顺序。

**原因**：这些变更影响长生命周期连接、全局资源或启动时注册表。

## 需要数据迁移

这些变更必须考虑 `dataVersion`、wrapper 和旧数据兼容：

- `BiliData` 持久化结构变化。
- 订阅联系人 subject 结构变化。
- 模板策略结构变化。
- 分组、过滤、黑名单持久化字段变化。

**正确做法**：

- 在 `BiliConfigManager.loadData()` 链路处理迁移。
- 保留 legacy wrapper 或兼容读取。
- 迁移完成后写回当前版本。

## 需要配置迁移

这些变更影响 `bot.yml`：

- `PlatformConfig` 字段变化。
- `NapCatConfig` 字段变化。
- `QQOfficialConfig` 字段变化。
- adapter 默认值或兼容逻辑变化。

**正确做法**：

- 修改 `BotConfigFileStore` 兼容读取。
- 保存时仍输出 canonical 结构。
- 更新 `PlatformConfigCompatibilityTest`。

## 需要部署变更

这些变更需要同步 Docker、compose、发行包或文档：

- JVM 内存参数。
- jemalloc 策略。
- 系统依赖。
- 字体依赖。
- 日志保留策略。
- 发布资产名称。

**正确做法**：同步更新 [`../operations/deployment.md`](../operations/deployment.md) 和 [`../operations/memory-tuning.md`](../operations/memory-tuning.md)。

## 分类 checklist

- [ ] 只改内存态策略，还是改磁盘配置？
- [ ] 是否有运行态缓存？
- [ ] 是否涉及长连接或全局 native 资源？
- [ ] 是否需要版本迁移？
- [ ] 是否需要重启才能重新创建 adapter/client/tasker？
- [ ] 是否需要更新部署文档？

