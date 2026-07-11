# AGENTS.md — AI 开发者总纲

本文件是 `docs/` 文档体系的入口。仓库根目录的 `AGENTS.md` 仍然是仓库级强制规范，必须原样保留并优先遵守；本文件只补充项目开发与维护时的阅读顺序、约束索引和任务检查清单。

## 阅读本文档的优先级规则

1. 根目录 `AGENTS.md` 中的强制规则：**仓库级绝对约束，不得删除、减少、绕过或覆盖**。
2. [`development/red-lines.md`](development/red-lines.md) 中的禁止项：**绝对约束，无论任何理由不得违反**。
3. [`architecture/invariants.md`](architecture/invariants.md) 中的系统不变量：违反前必须显式说明并经人类确认。
4. 本文档的三态入口、模块索引和对应模块 checklist：**命中范围后必须执行**；若无法执行，必须说明原因并经人类确认。
5. 本文档其余内容：强烈建议，有充分理由可偏离但须说明原因，并把冲突记录到 [`bugs.md`](bugs.md)。

## 任务开始前必须确认的问题清单

- [ ] 是否已读取根目录 `AGENTS.md`，并确认本轮不会删除或覆盖其中任何现有内容？
- [ ] 本次是查询、变更还是新建？是否按下方“三态入口”选择了对应文档？
- [ ] 任务属于哪个模块、根目录文件或文档分区？对应入口文档是否已阅读？
- [ ] 是否命中模块索引？若命中，是否执行了对应模块的查询、变更或新建 checklist？
- [ ] 变更是否涉及 Skia 资源、字体、图片绘制或缓存？必读 [`modules/skia.md`](modules/skia.md)。
- [ ] 变更是否影响配置、数据迁移或热重载？必读 [`development/change-classification.md`](development/change-classification.md)。
- [ ] 变更是否跨越平台适配边界？必读 [`architecture/layer-contracts.md`](architecture/layer-contracts.md) 和 [`domain/platform-adapters.md`](domain/platform-adapters.md)。
- [ ] 变更是否新增或修改 Tasker？必读 [`modules/tasker.md`](modules/tasker.md)，并检查 `TaskResourcePolicyRegistry` 覆盖。
- [ ] 变更是否影响部署、JVM 参数、Docker 或内存策略？必读 [`operations/deployment.md`](operations/deployment.md) 和 [`operations/memory-tuning.md`](operations/memory-tuning.md)。
- [ ] 是否正在调查启动失败、消息不推送、平台断连、队列背压、内存或 WebUI 故障？必读 [`operations/troubleshooting.md`](operations/troubleshooting.md)。
- [ ] 是否需要更新 [`context/current-state.md`](context/current-state.md) 或 [`context/known-issues.md`](context/known-issues.md)？

## 项目核心约束摘要

- 配置写入有明确归属：`BiliConfigManager` 管理 `BiliConfig.yml` 和 `BiliData.yml`，`ConfigManager`/`BotConfigFileStore` 管理 `bot.yml`。不要让业务模块直接写配置文件。见 [`architecture/invariants.md#inv-001`](architecture/invariants.md#inv-001-biliconfigmanager-与-configmanager-是配置和数据写入边界)。
- 平台接入必须经由 `PlatformConnectorManager`、`PlatformAdapter`、`PlatformContact`、`OutgoingPart` 等平台中立模型。业务层不得依赖 NapCat、LlBot 或 OneBot11 vendor DTO。见 [`development/red-lines.md#rl-004`](development/red-lines.md#rl-004-禁止向平台中立接口泄露-vendor-类型)。
- Skia 绘制必须经 `SkiaManager.executeDrawing` 创建 `DrawingSession`，会话内创建的 native 资源必须被追踪并关闭；Skiko 对象级生命周期见 [`development/skiko-object-lifecycle.md`](development/skiko-object-lifecycle.md)。见 [`architecture/decisions/adr-002-skia-lifecycle.md`](architecture/decisions/adr-002-skia-lifecycle.md)。
- Tasker 必须受 `BiliTasker` 生命周期管理，新增任务必须加入 `TaskBootstrapService` 启动列表并声明 `TaskResourcePolicyRegistry` 策略。见 [`modules/tasker.md`](modules/tasker.md)。
- 停机顺序由 `ResourceSupervisor` 分阶段管理，入口、worker、channel、依赖和根协程作用域不得随意改变回收顺序。见 [`architecture/invariants.md#inv-005`](architecture/invariants.md#inv-005-停机必须按资源分区阶段逆依赖收敛)。
- 模板策略读写必须经 `TemplateRuntimeCoordinator`，避免策略表和运行态缓存不同步。见 [`modules/service.md`](modules/service.md)。
- 文档内容不得加入 `src/test` 测试模块；如果需要用测试守护行为，只验证源码或产物，不复制项目文档片段。见根目录 `AGENTS.md` 和 [`development/red-lines.md#rl-012`](development/red-lines.md#rl-012-禁止把项目文档内容塞入测试模块)。

## 查询、变更、新建三态入口

- 查询项目行为时，先读根目录 `AGENTS.md`、本文档、对应模块文档的“查询 checklist”，再读取该模块指向的 domain、architecture 或 operations 文档；不要从 `docs/plans/` 或 `docs/过期文档/` 推断当前实现。
- 修改已有代码、测试、脚本、配置或文档时，先读对应模块文档的“变更 checklist”，再按影响面读取红线、不变量、层边界、测试策略和运维文档。
- 新增源码文件时，必须优先归入已有模块；若需要新增顶层包、架构层、常驻资源、配置文件或运行脚本，必须同步新增或更新对应 `docs/modules/*.md`、[`architecture/layer-contracts.md`](architecture/layer-contracts.md) 和必要的 red-line/invariant。
- 新增顶层包、根目录文件或新的 `docs` 分区前，必须先判断能否归入既有模块或分区；确需新增时，先更新本文件模块索引和对应维护文档，再落新文件。
- 新增文档时，必须判断它是当前维护文档、阶段性计划、发布说明还是历史记录；当前约束写入 `docs/architecture`、`docs/development`、`docs/domain`、`docs/modules` 或 `docs/operations`，阶段性执行过程写入 `docs/plans`，过时内容不得作为维护入口。
- `docs/release/` 只用于项目所有者维护的版本追溯；AI 不得修改、新增或删除 release 文档，除非用户在当前对话中明确要求。
- 新增测试时，只能验证源码、配置产物或运行行为，不得把项目文档内容复制到 `src/test`；详见 [`development/testing-strategy.md`](development/testing-strategy.md)。

## 文档分区边界

- 当前维护约束只以 `docs/AGENTS.md`、`architecture/`、`development/`、`domain/`、`modules/`、`operations/`、`context/` 和根目录维护文档为入口。
- `docs/release/` 只作为版本追溯入口，由项目所有者维护；不得把 release 文档当作模块、架构或运维约束来源。
- `docs/plans/` 只保存阶段性设计、实施计划和测试报告；除非用户明确要求执行某个计划，否则不得把 plans 当作当前规则来源。
- `docs/规则/` 只能作为当前入口显式引用后的专项补充；未被当前入口引用时，不得优先于模块文档、红线或不变量。
- `docs/过期文档/` 只能作为历史背景，不得覆盖当前维护文档。
- 详细规则见 [`development/documentation-maintenance.md`](development/documentation-maintenance.md)。

## 模块索引

| 范围 | 代码或文件入口 | 必读文档 | 额外约束 |
| --- | --- | --- | --- |
| 启动与装配 | `Main.kt`、`Init.kt`、`SkikoInitializer.kt` | [`modules/bootstrap.md`](modules/bootstrap.md) | [`architecture/layer-contracts.md`](architecture/layer-contracts.md)、[`operations/deployment.md`](operations/deployment.md) |
| Core 生命周期与资源 | `core/*` | [`modules/core.md`](modules/core.md) | [`architecture/invariants.md`](architecture/invariants.md)、[`operations/monitoring.md`](operations/monitoring.md) |
| API | `api/*` | [`modules/api.md`](modules/api.md) | [`domain/bilibili-api.md`](domain/bilibili-api.md) |
| Client | `client/*` | [`modules/client.md`](modules/client.md) | [`domain/bilibili-api.md`](domain/bilibili-api.md)、[`operations/monitoring.md`](operations/monitoring.md) |
| Config | `BiliConfig*`、`config/*` | [`modules/config.md`](modules/config.md) | [`development/change-classification.md`](development/change-classification.md) |
| Connector | `connector/*` | [`modules/connector.md`](modules/connector.md) | [`domain/platform-adapters.md`](domain/platform-adapters.md)、[`architecture/decisions/adr-001-platform-adapter.md`](architecture/decisions/adr-001-platform-adapter.md) |
| Data | `data/*`、`BiliData*` | [`modules/data.md`](modules/data.md) | [`domain/dynamic-types.md`](domain/dynamic-types.md)、[`domain/template-placeholders.md`](domain/template-placeholders.md) |
| Draw | `draw/*` | [`modules/draw.md`](modules/draw.md) | [`modules/skia.md`](modules/skia.md)、[`domain/dynamic-types.md`](domain/dynamic-types.md) |
| Service | `service/*` | [`modules/service.md`](modules/service.md) | [`domain/template-placeholders.md`](domain/template-placeholders.md)、[`domain/platform-adapters.md`](domain/platform-adapters.md) |
| WebUI 服务端 | `src/main/kotlin/top/bilibili/webui/*`、`src/main/resources/webui/react/*` | [`modules/webui.md`](modules/webui.md) | [`architecture/layer-contracts.md`](architecture/layer-contracts.md)、[`modules/config.md`](modules/config.md)、[`modules/resources.md`](modules/resources.md) |
| WebUI 前端 | `webui-frontend/*` | [`modules/webui-frontend.md`](modules/webui-frontend.md) | [`modules/webui.md`](modules/webui.md)、[`modules/resources.md`](modules/resources.md)、[`development/build-ci-release.md`](development/build-ci-release.md) |
| Skia | `skia/*`、Skia 相关工具 | [`modules/skia.md`](modules/skia.md) | [`development/skiko-object-lifecycle.md`](development/skiko-object-lifecycle.md)、[`architecture/decisions/adr-002-skia-lifecycle.md`](architecture/decisions/adr-002-skia-lifecycle.md)、[`operations/memory-tuning.md`](operations/memory-tuning.md) |
| Tasker | `tasker/*` | [`modules/tasker.md`](modules/tasker.md) | [`architecture/invariants.md`](architecture/invariants.md)、[`operations/monitoring.md`](operations/monitoring.md) |
| Utils | `utils/*` | [`modules/utils.md`](modules/utils.md) | [`modules/draw.md`](modules/draw.md)、[`modules/connector.md`](modules/connector.md)、[`development/coding-standards.md`](development/coding-standards.md) |
| Resources | `src/main/resources/*` | [`modules/resources.md`](modules/resources.md) | [`modules/draw.md`](modules/draw.md)、[`modules/skia.md`](modules/skia.md) |
| Tests | `src/test/*` | [`development/testing-strategy.md`](development/testing-strategy.md) | 根目录 `AGENTS.md`、[`development/red-lines.md`](development/red-lines.md) |
| Build/CI/Release | Gradle、`gradle/wrapper/*`、Docker、`.github/workflows/*` | [`development/build-ci-release.md`](development/build-ci-release.md) | [`operations/deployment.md`](operations/deployment.md)、[`operations/memory-tuning.md`](operations/memory-tuning.md) |
| 运行故障排查 | 启动、日志、Tasker、平台、队列、Skia、RSS、WebUI | [`operations/troubleshooting.md`](operations/troubleshooting.md) | [`operations/monitoring.md`](operations/monitoring.md)、[`operations/incident-log.md`](operations/incident-log.md)、[`context/known-issues.md`](context/known-issues.md) |
| 术语与上下文 | 跨模块术语、运行状态、已知问题 | [`context/glossary.md`](context/glossary.md) | [`context/current-state.md`](context/current-state.md)、[`context/known-issues.md`](context/known-issues.md) |
| 仓库执行规范 | 根目录 `AGENTS.md` | 根目录 `AGENTS.md` | [`development/documentation-maintenance.md`](development/documentation-maintenance.md) |
| 仓库根目录文件 | `README*`、`LICENSE`、`.editorconfig`、`.gitattributes`、`.gitignore`、`.env.example`、`.dockerignore` | [`development/repository-files.md`](development/repository-files.md) | [`development/documentation-maintenance.md`](development/documentation-maintenance.md)、[`development/build-ci-release.md`](development/build-ci-release.md) |
| Docs 维护 | `docs/*` | [`development/documentation-maintenance.md`](development/documentation-maintenance.md) | 根目录 `AGENTS.md`、本文档、[`modules/_template.md`](modules/_template.md) |

## 推荐阅读顺序

1. 根目录 `AGENTS.md`
2. 本文件
3. [`development/red-lines.md`](development/red-lines.md)
4. [`architecture/invariants.md`](architecture/invariants.md)
5. [`architecture/overview.md`](architecture/overview.md)
6. 与任务相关的 [`modules/`](modules/) 文档
7. 与任务相关的 [`domain/`](domain/) 或 [`operations/`](operations/) 文档
8. [`context/current-state.md`](context/current-state.md) 与 [`context/known-issues.md`](context/known-issues.md)

调查运行故障时，在读取模块文档后直接进入 [`operations/troubleshooting.md`](operations/troubleshooting.md)，再按症状读取监控、部署或内存文档。

## 当前迭代焦点

见 [`context/current-state.md`](context/current-state.md)。

## 长期文档覆盖图

长期维护文档按当前代码入口划分如下。迭代进度只在 [`context/current-state.md`](context/current-state.md) 维护，避免入口页复制一次性状态。

| 子系统 | 当前代码入口 | 长期维护文档 | 补齐重点 |
| --- | --- | --- | --- |
| 后端主程序 | `src/main/kotlin/top/bilibili` | `architecture/*`、`modules/*`、`domain/*`、`operations/*` | 启动、配置、平台、服务、Tasker、Skia、WebUI 和资源生命周期 |
| WebUI 服务端 | `src/main/kotlin/top/bilibili/webui` | `modules/webui.md`、`modules/config.md`、`architecture/layer-contracts.md` | 路由矩阵、cookie/CSRF/确认密码、配置写入、订阅编辑、日志白名单和审计 |
| WebUI 前端 | `webui-frontend/src`、`webui-frontend/e2e` | `modules/webui-frontend.md`、`development/build-ci-release.md` | 页面、hook、payload、router、主题、确认弹窗、Vitest/Playwright 与 Vite 构建输出 |
| 平台适配 | `connector/*`、`config/NapCatConfig.kt` | `modules/connector.md`、`domain/platform-adapters.md`、ADR-001 | OneBot11 通用层、NapCat、LlBot、QQ 官方、能力 guard 和观测快照 |
| 资源与绘图 | `draw/*`、`skia/*`、`src/main/resources/*` | `modules/draw.md`、`modules/skia.md`、`modules/resources.md` | DrawingSession、图片降级、字体/图标/静态产物和 native 生命周期 |
| 后台任务 | `tasker/*`、`TaskBootstrapService`、`TaskResourcePolicyRegistry` | `modules/tasker.md`、`architecture/invariants.md`、`operations/monitoring.md` | 启动顺序、worker 自愈、队列容量、推送统计、清理和守护 |

`docs/plans/`、`docs/release/`、`docs/规则/` 和 `docs/过期文档/` 不作为当前规则改写来源；若需要追溯历史，只能回到当前维护文档确认是否已经落地。
