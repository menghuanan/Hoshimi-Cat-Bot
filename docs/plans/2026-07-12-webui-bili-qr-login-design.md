# WebUI B站扫码登录设计

## 目标

在设置页提供 B站账号扫码登录入口。WebUI 与聊天命令 `/login` 必须共享同一取码、轮询、全局互斥、取消和 Cookie 原子提交链路，不能维护两套可能相互覆盖凭据的登录状态机。

## 架构

`QrLoginCoordinator` 是唯一登录会话所有者，负责 180 秒有效期、3 秒轮询、状态转换、代际隔离和提交边界。它在 `BiliBiliBot` 根作用域中通过 `BusinessLifecycleManager` 运行短时 worker；命令端 `LoginService` 只负责平台联系人消息和 temp PNG，WebUI facade 只负责脱敏 DTO 映射。

浏览器通过以下受保护接口操作会话：

- `POST /api/bili-login/sessions` 创建二维码，会校验 session、CSRF 和当前 WebUI 密码。
- `GET /api/bili-login/sessions/{sessionId}` 读取脱敏状态。
- `DELETE /api/bili-login/sessions/{sessionId}` 取消仍在等待的会话。

创建 DTO 只返回 `sessionId`、状态、有效期、提示和 PNG Base64。二维码 URL、`qrcode_key`、Cookie 与回调 URL 不进入 HTTP 响应或审计。全局已有命令或 Web 会话时返回 `409` 和剩余秒数。

## 状态与取消

公开状态固定为 `WAITING_FOR_SCAN`、`WAITING_FOR_CONFIRMATION`、`COMMITTING`、`SUCCEEDED`、`EXPIRED`、`FAILED`、`CANCELLED`。普通等待态允许取消；进入 `COMMITTING` 后拒绝取消，确保候选配置持久化和运行态安装不会被拆开。终态只保留脱敏快照 5 分钟，活动记录中的 URL、key 和图片字节立即释放。

## 前端交互

设置页“B站账号”区域展示运行态登录状态和 UID。点击“扫码登录”或“重新登录”后立即打开弹窗；创建成功后显示 250×250 二维码并每 3 秒轮询。Escape、遮罩和取消按钮统一执行 DELETE，提交阶段锁定关闭。失败或过期可原位重试；成功后刷新运行态、显示 Toast，并在约 1.5 秒后自动关闭和清除图片字符串。

## 验证

后端测试覆盖协调器状态流、并发互斥、取消、提交边界、路由 guard、HTTP 状态和审计脱敏。前端使用 Vitest 覆盖 API、hook 和设置页交互，Playwright 覆盖打包产物中的完整扫码成功流程。静态资源只由 Vite 构建生成。
