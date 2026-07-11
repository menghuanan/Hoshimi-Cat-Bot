# 当前迭代状态

_最后更新：2026-07-11_

## 本轮已完成

- [x] 按当前源码入口补齐并复核 `docs/` 长期维护文档，建立运行故障排查入口，并保持 plans/release/过期资料与当前规则分区隔离。
- [x] 修复群管理员跨群修改分组模板策略与启动失败进程不退出的 P0 问题。
- [x] 修复 Skia purge 闸门、业务队列/Skia native 观测盲区，并移除未实现的 worker process 配置。

## 进行中

- [ ] G1 周期回收、heap shrink 与默认 `-Xmx160m` 当前运行观测正常，继续积累长时间静默和峰值场景数据；Metaspace 因约 42 MB 使用量接近原 48 MB 上限，已单独提高到 56 MB。

## 当前实现基线

- 项目是 Kotlin/JVM 单模块应用，Java toolchain 17。
- 主入口是 `top.bilibili.MainKt`。
- 平台适配通过 `PlatformConnectorManager` 收口。
- 平台 adapter 当前覆盖 Generic OneBot11、NapCat、LlBot 和 QQ 官方机器人；业务层应使用 `PlatformContact`、`OutgoingPart` 和 capability guard。
- QQ 官方入站当前只向业务链放行精确 `/login` 和 B 站链接候选；群聊 `/login` 还必须来自 AT 事件，其他命令和普通文本在 connector 层拒绝。
- 本地 WebUI 管理模块已加入当前基线，默认关闭，提供 cookie-backed session、CSRF、高风险确认、配置读写、订阅编辑、日志白名单、运行态 Dashboard 和高风险动作入口。
- React WebUI 前端位于 `webui-frontend`，构建后打包到 `src/main/resources/webui/react`，页面覆盖登录、仪表盘、设置、订阅和日志，并使用全局 Toast、确认上下文和 Portal 弹窗统一反馈。
- 动态列表按 item 粒度容错解码：单条坏数据会记录摘要并跳过，分页外层结构仍严格解码；已撤销且只剩空字段的预约附加卡片不会绘制空白图。
- Skia 当前实际主路径是 in-process drawing。
- `ProcessGuardian` 直接采集三条 core 业务队列与 `SendTasker` 队列填充率，并记录 Skia Graphics native resource cache 字节数。
- Docker 和发行包默认使用 software rendering。
- Docker 默认内存限制为 512m，应用 heap 默认 `64m~160m`。
- Docker Metaspace 上限为 56m；本轮未提高 heap 上限，也未调整 G1 周期回收和 heap shrink 参数。

## 当前维护约束

- 根目录 `AGENTS.md` 现有内容不得删除、减少、覆盖或弱化。
- `src/test` 不得加入项目文档内容或文档片段。
- 修改源码注释时必须使用 UTF-8，并保留或等价修复既有注释意图。
- `docs/plans`、`docs/release`、`docs/规则` 和 `docs/过期文档` 不作为当前实现入口；长期规则应落在当前维护分区。

## 已知问题入口

工程偏差和处理状态维护在 [`../bugs.md`](../bugs.md)；运行症状、影响与临时绕过维护在 [`known-issues.md`](known-issues.md)。两页使用 BUG/KI 编号互链。
