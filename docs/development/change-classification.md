# 变更分类

本文定义常见变更属于“可运行期重载”“需要重启”“需要迁移”还是“需要部署变更”。

_最后复核：2026-07-12_

## 可运行期重载

这些变更理论上可通过现有 reload 入口生效，但仍需确认调用方是否重新读取配置。

- `BiliConfigManager.reloadConfig()` / `reloadData()` / `reloadAll()`：显式调用时重载磁盘快照。
- `ConfigManager.reload()`：显式调用时重载 `bot.yml` 快照，本身不等于完成平台运行态切换。
- `WebUiConfigHotReloadCoordinator`：WebUI 保存链路的串行运行代际入口。

示例：

- 模板文本调整。
- 功能开关调整。
- 链接解析配置调整。
- 部分联系人、分组、过滤器数据调整。
- WebUI 设置页或订阅页保存的 `BiliConfig.yml`、`BiliData.yml`、`bot.yml` 当前可编辑字段，会进入 WebUI 热重载协调器串行处理；该能力只覆盖前端保存链路，不监听手动改文件。
- 经 WebUI 保存的 `bot.yml` 平台类型、adapter、OneBot11/NapCat/LlBot/QQ 官方连接参数和 WebUI 运行面参数，会通过候选代际、平台 connector prepare/commit、WebUI 受控重启和失败回滚完成热切换；候选未真实连通或 WebUI 入口启动失败时旧运行态继续工作并尝试回滚磁盘。
- 命令和 WebUI 触发的订阅、分组、过滤、黑名单、主题色和模板策略变更，通过 `BiliDataRuntimeCoordinator` 修改候选快照；持久化失败时运行态保持不变。

**注意**：存在运行态缓存时必须同步清理，例如模板策略需经 `TemplateRuntimeCoordinator`。

## 需要重启

这些变更在手工修改文件、启动参数或源码后需要重启进程：

- 平台类型或 adapter 切换。
- OneBot11/NapCat/LlBot/QQ 官方连接参数变化。
- JVM 参数变化。
- Docker/entrypoint/发行包启动脚本变化。
- Skiko 渲染参数变化。
- `SkiaConfig` 全局策略变化。
- 新增或删除 Tasker。
- 修改 `TaskBootstrapService` 启动顺序。

**原因**：这些变更影响长生命周期连接、全局资源或启动时注册表。

**例外**：平台类型、adapter 和连接参数如果来自 WebUI 保存接口，并且字段已纳入 `WebUiConfigHotReloadCoordinator`、`RuntimeConfigApplier` 和 connector prepare/commit 测试覆盖，则按“可运行期重载”处理。直接编辑 `config/*.yml` 不会触发 watcher，仍需重启或显式调用受控 reload 入口。

## 需要数据迁移

这些变更必须考虑 `dataVersion`、wrapper 和旧数据兼容：

- `BiliData` 持久化结构变化。
- 订阅联系人 subject 结构变化。
- 模板策略结构变化。
- 分组、过滤、黑名单持久化字段变化。

**正确做法**：

- 在 `BiliConfigManager.loadData()` 链路处理迁移。
- 运行期写入统一经 `BiliDataRuntimeCoordinator` 候选快照提交，不能先污染全局 `BiliData` 再尝试保存。
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
