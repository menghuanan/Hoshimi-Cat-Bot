# 已知问题、处理状态与临时绕过

本文记录当前实现中已识别的问题、处理状态与临时绕过。确认修复后应同步更新本文件和 [`../bugs.md`](../bugs.md)。

## KI-001: Skia worker process 配置尚未落地

**现象**：`SkiaConfig` 暴露 worker process 配置，但 `SkiaManager` 当前实际模式为 `IN_PROCESS`，`WORKER_PROCESS` 分支未实现。

**当前状态**：文档描述已收敛为 worker process 是预留/未实现能力，当前仅支持 in-process + software rendering + cleanup tasker。

**后续处理**：实现 worker process 前，不要把该能力写成已支持。

## KI-002: SendTasker 乱码注释已修复

**现象**：`SendTasker.kt` 中曾有疑似乱码注释。

**当前状态**：已按 UTF-8 读取并修复为正常中文注释，未修改发送逻辑。

**后续处理**：无。

## KI-003: 平台迁移期仍有 deprecated Long 入口

**现象**：`BiliBiliBot`、`PlatformAdapter`、`PlatformCapabilityService` 中仍保留群号/私聊 Long 兼容入口。

**临时绕过**：新代码一律使用 `PlatformContact`。

**后续处理**：确认无旧调用链后，再移除 deprecated 入口并更新测试。
