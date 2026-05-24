# 当前迭代状态

_最后更新：2026-05-25_

## 进行中

- [ ] 建立 `docs/` 开发与维护规范体系。
- [ ] 继续验证 G1 周期性回收与 heap shrink 参数在长时间静默场景的效果。
- [ ] 继续评估 `-Xmx160m` 作为 Docker/裸机默认值的充分性。

## 当前实现基线

- 项目是 Kotlin/JVM 单模块应用，Java toolchain 17。
- 主入口是 `top.bilibili.MainKt`。
- 平台适配通过 `PlatformConnectorManager` 收口。
- 本地 WebUI 管理模块已加入当前基线，默认关闭，React 前端构建后打包到 `src/main/resources/webui/react`。
- Skia 当前实际主路径是 in-process drawing。
- Docker 和发行包默认使用 software rendering。
- Docker 默认内存限制为 512m，应用 heap 默认 `64m~160m`。

## 当前维护约束

- 根目录 `AGENTS.md` 现有内容不得删除、减少、覆盖或弱化。
- `src/test` 不得加入项目文档内容或文档片段。
- 修改源码注释时必须使用 UTF-8，并保留或等价修复既有注释意图。

## 需要后续确认

- worker process 仍为预留/未实现能力，当前仅支持 in-process，见 [`../bugs.md`](../bugs.md)。
