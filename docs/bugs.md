# 文档与实现待确认事项

_最后更新：2026-04-22_

## BUG-001: Skia worker process 配置与实际实现不一致（文档已收敛）

**现象**：`SkiaConfig.enableWorkerProcess` 默认值为 `true`，并存在 worker 进程相关配置项；但 `SkiaManager.currentMode` 当前固定初始化为 `IN_PROCESS`，`WORKER_PROCESS` 分支会抛出 `UnsupportedOperationException("Worker process mode not implemented yet")`。

**处理**：文档描述统一为：worker process 为预留/未实现能力，当前仅支持 in-process。

**后续**：如需启用 worker process，必须补齐 worker process 实现、切换逻辑和对应测试后再更新文档。

## BUG-002: SendTasker 存在疑似乱码注释（已修复）

**现象**：`src/main/kotlin/top/bilibili/tasker/SendTasker.kt` 中曾存在乱码注释，疑似原意为“发送间隔”。

**处理**：已按 UTF-8 读取并将乱码注释修复为正常中文说明，未修改发送逻辑。

## BUG-003: 当前文档体系新入口与根 AGENTS.md 可能被误解为同级（已修复）

**现象**：本轮新增 `docs/AGENTS.md`，但根目录已存在仓库级 `AGENTS.md`。

**影响**：AI 或人工开发者可能只读 `docs/AGENTS.md` 而跳过根目录强制规范。

**处理**：`docs/AGENTS.md` 与根目录 `AGENTS.md` 均已明确声明根目录 `AGENTS.md` 优先，且不得删除、覆盖或弱化。
