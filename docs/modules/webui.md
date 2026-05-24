# WebUI 模块

## 模块定位

WebUI 服务端模块负责把 Ktor 服务端、静态 React shell、认证会话、配置读写、订阅编辑、日志查看和高风险动作入口组装成一个受控的运维界面。配套前端工程见 [`modules/webui-frontend.md`](webui-frontend.md)。它处在启动层、配置层、服务层和资源层之间，不直接承担 B 站业务逻辑。

## 代码入口

- `src/main/kotlin/top/bilibili/webui/server/WebUiManager.kt`
- `src/main/kotlin/top/bilibili/webui/routes/*`
- `src/main/kotlin/top/bilibili/webui/auth/*`
- `src/main/kotlin/top/bilibili/webui/service/*`
- `src/main/kotlin/top/bilibili/webui/model/*`
- `src/main/kotlin/top/bilibili/webui/config/WebUiConfig.kt`
- `src/main/resources/webui/react/*`
- `src/test/kotlin/top/bilibili/webui/*`

## 主要职责

- 启动和停止嵌入式 Ktor CIO 服务器，并托管静态管理页。
- 提供登录、改密、会话探针、健康检查、运行态、配置、订阅、日志和动作 API。
- 使用 HttpOnly session cookie、CSRF cookie 和短时高风险确认保护写操作。
- 通过 facade 读取 `BiliConfig.yml`、`BiliData.yml`、`bot.yml` 的快照，并按文件边界写回。
- 只暴露固定白名单日志源，日志查看、导出和清空都只接受这些 sourceId，不接受任意文件路径；清空动作还必须经过高风险确认并写入审计。
- 记录认证、配置保存和高风险动作审计。
- 将 React 前端构建产物打包到 `src/main/resources/webui/react`，供 Ktor 静态路由直接服务；当 `bot.yml.webui.static_dir` 指向有效外部目录时，`WebUiConfig.toSettings()` 会优先解析该目录，`WebUiStaticRoutes` 也会优先从那里提供 `/login`、`/` 和 `/assets`，便于独立部署和前端调试；前端源码与测试见 `webui-frontend` 模块。

## 关键流程

1. `BotConfig.webui` 生成 `WebUiSettings`，决定是否启用、监听地址、端口和凭据文件位置。
2. `WebUiManager.start()` 先 bootstrap 凭据，再创建 `WebUiAuthService`、各类 facade 和路由树。
3. `installWebUiModule()` 安装 JSON 编解码、请求体边界和安全 hardening，再挂载静态页、认证、API、日志和动作路由。
4. 浏览器登录后只拿到 session/csrf cookie；后续 unsafe 请求需要 CSRF 头和高风险确认口令。
5. `WebUiConfigFacade`、`WebUiConfigWriteFacade`、`WebUiSubscriptionManagementFacade`、`WebUiRuntimeFacade` 和 `WebUiLogFacade` 分别负责快照读取、受控写入、运行态汇总和日志窗口。
6. 停止时 `WebUiManager` 只关闭嵌入式服务器，不接管 bot 主生命周期。

## 资源与生命周期

- `WebUiManager` 只拥有嵌入式服务器生命周期，不持有 bot 主协程或平台 adapter。
- `WebUiCredentialStore` 负责 `webui-credentials.json` 的创建、读取、迁移和密码哈希。
- 凭据文件默认落在 `config/webui-credentials.json`，路径由 `bot.yml` 的 `webui.credential_file` 决定。
- `WebUiLogFacade` 只读固定日志源，并按本次启动时间裁切旧日志窗口。
- React 静态产物由 `webui-frontend` 构建生成，`src/main/resources/webui/react` 不应手工维护。

## 配置与数据

- `bot.yml.webui.enabled`、`host`、`port`、`credential_file`、`token_ttl_seconds`、`static_dir` 控制 WebUI 运行参数。
- `static_dir` 只在指向有效目录时生效；此时 WebUI 会优先从外部静态目录读取登录页、主壳页和 assets，未命中时回退到打包资源。
- `webui-credentials.json` 保存密码哈希、salt、tokenVersion 和强制改密状态。
- `BiliConfig.yml`、`BiliData.yml`、`bot.yml` 只允许通过各自 manager/facade 写回，不允许 WebUI 直接写文件。
- `logs/bilibili-bot.log`、`logs/error.log`、`logs/daemon/Daemon_*.log` 是当前固定日志来源，对应的 `GET /api/logs/{sourceId}/export` 和 `POST /api/logs/{sourceId}/clear` 也只针对这些白名单 sourceId。
- `WebUiConfigWriteFacade` 对 `BiliConfig.yml` 和 `bot.yml` 使用快照 token，对 `BiliData.yml` 使用受控即时保存，避免跨文件误写。

## 测试与验证

- `./gradlew test --tests top.bilibili.webui.server.WebUiManagerTest`
- `./gradlew test --tests top.bilibili.webui.routes.WebUiRouteSmokeTest`
- `./gradlew test --tests top.bilibili.webui.auth.WebUiAuthServiceTest`
- `./gradlew processResources`

## 禁止事项

- 禁止直接写 `BiliConfig.yml`、`BiliData.yml`、`bot.yml` 或 `webui-credentials.json`。
- 禁止通过任意路径读取、导出或清空日志；日志导出和清空只允许固定白名单日志源，清空还必须经过高风险确认和审计。
- 禁止绕过 session、CSRF、确认密码或审计直接执行高风险动作。
- 禁止把平台 vendor DTO 或业务可变对象直接暴露给前端。
- 禁止手工修改 `src/main/resources/webui/react` 里的前端产物。
- 禁止在这个模块里维护前端页面、hook、payload、router 或 e2e 测试。

## 查询 checklist

- [ ] 是否已阅读根目录 `AGENTS.md` 与 `docs/AGENTS.md`？
- [ ] 是否确认查询对象属于 WebUI，而不是相邻的 config、service、resources 或 architecture 文档？
- [ ] 是否阅读了对应的 architecture、config、resources 或 development 文档？
- [ ] 是否区分当前实现、阶段性计划和过期记录？

## 变更 checklist

- [ ] 是否明确本次改的是 `webui` 模块、`bot.yml.webui` 还是前端静态产物？
- [ ] 是否保留现有注释和安全边界描述？
- [ ] 是否需要同步 `docs/architecture/layer-contracts.md`、`docs/architecture/overview.md`、`docs/modules/config.md` 或 `docs/modules/resources.md`？
- [ ] 是否运行了对应的 Gradle 和 frontend 测试？

## 新建 checklist

- [ ] 新文件是否优先归入 `top/bilibili/webui` 既有入口，而不是再拆出新的顶层包？
- [ ] 新函数、方法或逻辑块是否补充紧邻注释，说明用途、意图或关键约束？
- [ ] 新配置、数据结构、资源、协程、客户端、缓存、channel 或 native 对象是否有明确生命周期和归属边界？
- [ ] 新外部行为是否同步更新相关 architecture、config、resources 或 development 文档？
- [ ] 新测试是否只验证源码行为或产物，不复制项目文档内容？
