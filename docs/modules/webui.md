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
- 将 React 前端构建产物打包到 `src/main/resources/webui/react`，供 Ktor 静态路由直接服务；当 `bot.yml.webui.static_dir` 指向有效外部目录时，HTML 路由优先读取外部文件并可回退打包资源，`/assets` 则整体切换到外部 assets 目录，单个缺失文件不会回退；前端源码与测试见 `webui-frontend` 模块。

## 当前路由矩阵

| 路由组 | 入口 | 认证要求 | 关键约束 |
| --- | --- | --- | --- |
| 健康检查 | `GET /api/health` | 无 session 要求 | 只返回 WebUI 基础骨架状态，不暴露运行态细节 |
| 认证 | `/api/auth/session`、`/api/auth/login`、`/api/auth/change-password`、`/api/auth/logout` | login 无 session；改密和登出需要 session | session 只经 HttpOnly cookie，CSRF 经可读 cookie 和 `X-CSRF-Token` 双提交 |
| 运行态 | `GET /api/runtime/summary` | 需要 session | 只读即时快照，文本会脱敏路径、内网地址和凭据键值 |
| 配置读取 | `GET /api/config/bili-config`、`/api/config/bili-data`、`/api/config/bot` | 需要 session | facade 输出字段级快照和 snapshot token，不向前端暴露可变对象 |
| 配置写入 | `POST /api/config/save-batch`、`GET /api/config/save-jobs/{jobId}`、兼容单文件保存路由 | session、CSRF、高风险确认 | 设置页批量保存进入 `WebUiConfigHotReloadCoordinator`；单文件路由保留兼容但仍按受控 facade/协调器边界处理 |
| 订阅概览与卡片生命周期 | `GET /api/subscriptions`、`POST /api/subscriptions`、`DELETE /api/subscriptions/{id}` | 读需要 session；创建和删除需要 session、CSRF、高风险确认 | facade 汇总动态、分组和番剧卡片；创建与整卡删除仍按配置 owner 写回 |
| 订阅编辑 | `/api/subscriptions/{id}/targets`、`/uids`、`/filters`、`/templates`、`/atall`、`/theme`、`POST .../templates/random` | 读需要 session；写需要 session、CSRF、高风险确认 | key 使用 path-safe 格式，随机模板开关与其它 mutation 一样进入持久化刷新链路 |
| 日志 | `/api/logs/sources`、`/api/logs/{sourceId}`、`/api/logs/{sourceId}/export`、`/api/logs/{sourceId}/clear` | 读需要 session；清空需要高风险确认 | sourceId 必须命中白名单，导出文件名只由 sourceId 派生 |
| 动作 | `/api/actions/reload-config`、`/api/actions/shutdown`、`/api/actions/request-restart` | session、CSRF、高风险确认 | HTTP 层只做 guard 和 facade 调用，风险结果必须写审计 |

## 认证与高风险确认

- `WebUiAuthService` 组合凭据存储、密码策略、token 生命周期、登录节流和高风险确认窗口，不承载 HTTP 路由细节。
- 登录成功只写 `hoshimi_cat_bot_webui_session` 与 `hoshimi_cat_bot_webui_csrf` cookie；route guard 不再信任 bearer header。
- unsafe HTTP 方法必须同时通过 session cookie、CSRF cookie 与 `X-CSRF-Token` 头校验。
- 首次默认密码或强制改密状态下，只允许进入改密相关路径；其他受保护路由返回 `password change required`。
- 高风险确认以当前密码校验为准，成功后只在当前 session token 上缓存短时授权；改密会清空 token 与确认窗口。
- 登录失败会同时推进单 IP 和全局 backoff，外部响应保持通用错误，避免泄露凭据状态。
- 凭据、token、鉴权状态和审计各自以私有锁保护复合操作，跨仓库调用固定遵守“凭据 → token → 鉴权 → 审计”。并发改密在凭据锁内重读并校验旧密码，因此只有首个有效请求成功。

## HTTP 边界

- 每个请求最长处理 30 秒，请求体上限 1 MiB。
- 安全响应头、Origin/CORS 校验和代理来源处理在 Ktor 安装阶段统一配置，路由不得自行放宽。
- 可信反向代理必须覆盖外部传入的 `Forwarded` 与 `X-Forwarded-*`，并限制可访问来源；不能把客户端自报代理头直接当成真实地址。

## 订阅编辑边界

| 卡片类型 | `itemId` | 可编辑子项 | 写入边界 |
| --- | --- | --- | --- |
| 单 UP 动态 | `dynamic:<uid>` | 推送群、过滤器、模板、@全体、主题色 | 动态订阅按联系人 subject 展开，过滤/模板/主题可按目标群聊收窄 |
| 分组 | `group:<name>` | 推送群、订阅 ID、过滤器、模板、@全体、主题色 | 分组订阅使用 `groupRef:<name>` 作为模板 scope，订阅 ID 可是 UID 或 ss/md/ep 番剧标识 |
| 番剧 | `bangumi:<seasonId>` | 推送群 | 番剧写入仍复用 PGC service，WebUI 不直接操作 B 站关注接口 |

过滤器 key、模板 key 都使用 `|` 分隔的无斜杠格式，供 REST path 安全传递。删除附属配置时必须同步回收空 UID 桶和空 subject 桶，避免页面误判仍有配置。

## 关键流程

1. `BotConfig.webui` 生成 `WebUiSettings`，决定是否启用、监听地址、端口和凭据文件位置。
2. `WebUiManager.start()` 先 bootstrap 凭据，再创建 `WebUiAuthService`、各类 facade 和路由树。
3. `installWebUiModule()` 安装 JSON 编解码、请求体边界和安全 hardening，再挂载静态页、认证、API、日志和动作路由。
4. 浏览器登录后只拿到 session/csrf cookie；后续 unsafe 请求需要 CSRF 头和高风险确认口令。
5. `WebUiConfigFacade`、`WebUiConfigWriteFacade`、`WebUiConfigHotReloadCoordinator`、`WebUiSubscriptionManagementFacade`、`WebUiRuntimeFacade` 和 `WebUiLogFacade` 分别负责快照读取、dry-run 写入、保存归并与热重载、订阅编辑、运行态汇总和日志窗口。
6. 停止时 `WebUiManager` 只关闭嵌入式服务器，不接管 bot 主生命周期。

## 资源与生命周期

- `WebUiManager` 只拥有嵌入式服务器生命周期，不持有 bot 主协程或平台 adapter。
- WebUI 配置热重载协调器归 `BiliBiliBot` 根生命周期所有，不随单个 `WebUiManager` 重建；WebUI host、port、enabled、token TTL 或 static_dir 变化会参与运行态 apply 事务。新 host/port 会先启动新入口、再延迟停止旧入口，启动失败时保存 job 必须失败且旧入口继续服务；同 host/port 的运行参数重启需要先释放旧端口，失败时尝试恢复旧入口。
- `WebUiCredentialStore` 负责 `webui-credentials.json` 的创建、读取、迁移和密码哈希。
- 已有凭据文件空白或损坏时保留原件与备份并禁用 WebUI；核心平台和 Tasker 继续启动。凭据写入使用候选编码/解码验证、备份和原子替换。
- 热重载 job 的终态最多保留 24 小时和 1000 条，按完成时间淘汰最旧终态；非终态不因容量被删除。
- 凭据文件默认落在 `config/webui-credentials.json`，路径由 `bot.yml` 的 `webui.credential_file` 决定。
- `WebUiLogFacade` 只读固定日志源，并按本次启动时间裁切旧日志窗口。
- React 静态产物由 `webui-frontend` 构建生成，`src/main/resources/webui/react` 不应手工维护。

## 配置与数据

- `bot.yml.webui.enabled`、`host`、`port`、`credential_file`、`token_ttl_seconds`、`static_dir` 控制 WebUI 运行参数。
- `static_dir` 只在指向有效目录时生效；HTML 入口未命中时可回退打包资源，`/assets` 启用外部目录后不会对单个缺失 asset 回退。
- `webui-credentials.json` 保存密码哈希、salt、tokenVersion 和强制改密状态。
- `BiliConfig.yml`、`BiliData.yml`、`bot.yml` 只允许通过各自 manager/facade 写回，不允许 WebUI 直接写文件。
- `logs/bilibili-bot.log`、`logs/error.log`、`logs/daemon/Daemon_*.log` 是当前固定日志来源，对应的 `GET /api/logs/{sourceId}/export` 和 `POST /api/logs/{sourceId}/clear` 也只针对这些白名单 sourceId。
- `WebUiConfigWriteFacade` 对三个配置文件使用快照 token 和 dry-run 构建候选 payload；设置页一次点击应通过 `POST /api/config/save-batch` 触发一个热重载 job，前端轮询 job 到 `APPLIED` 或 `FAILED`。
- 订阅编辑成功写入 `BiliData.yml` 后必须向同一协调器提交已持久化数据刷新信号，确保模板策略和运行态缓存同步清理。

## 测试与验证

- `./gradlew test --tests top.bilibili.webui.server.WebUiManagerTest`
- `./gradlew test --tests top.bilibili.webui.WebUiRouteSmokeTest`
- `./gradlew test --tests top.bilibili.webui.auth.WebUiAuthServiceTest`
- `./gradlew test --tests top.bilibili.webui.auth.WebUiTokenServiceTest`
- `./gradlew test --tests top.bilibili.webui.auth.WebUiCredentialStoreTest`
- `./gradlew test --tests top.bilibili.webui.service.WebUiSubscriptionManagementFacadeTest`
- `./gradlew test --tests top.bilibili.webui.service.WebUiConfigWriteFacadeTest`
- `./gradlew test --tests top.bilibili.webui.service.WebUiRuntimeFacadeTest`
- `./gradlew test --tests top.bilibili.webui.service.WebUiLogFacadeTest`
- `./gradlew test --tests top.bilibili.webui.service.WebUiAuditServiceTest`
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
