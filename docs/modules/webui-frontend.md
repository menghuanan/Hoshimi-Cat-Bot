# WebUI 前端模块

## 模块定位

`webui-frontend` 是独立的前端工程，负责把 WebUI 的登录、仪表盘、设置、订阅和日志页面渲染成可交互的 React 应用。它不直接接触 Kotlin 服务端对象，只通过稳定的 HTTP API、cookie 和前端状态管理与 WebUI 服务端协作。

## 代码入口

- `webui-frontend/package.json`
- `webui-frontend/vite.config.ts`
- `webui-frontend/playwright.config.ts`
- `webui-frontend/vitest.config.ts`
- `webui-frontend/src/main.tsx`
- `webui-frontend/src/App.tsx`
- `webui-frontend/src/pages/*`
- `webui-frontend/src/components/*`
- `webui-frontend/src/hooks/*`
- `webui-frontend/src/api/*`
- `webui-frontend/src/router/*`
- `webui-frontend/src/settings/*`
- `webui-frontend/src/subscriptions/*`
- `webui-frontend/src/contexts/*`
- `webui-frontend/src/utils/*`
- `webui-frontend/e2e/*`

## 主要职责

- 使用 Vite 构建 React 应用，并把产物输出到 `src/main/resources/webui/react`。
- 负责登录页、仪表盘页、设置页、订阅页和日志页的前端交互。
- 用统一的 `requestJson`、CSRF 头和错误归一逻辑访问后端 API。
- 维护本地主题、导航、确认弹窗和若干浏览器持久化状态。
- 组装设置、订阅和日志的页面级 payload，而不直接接触后端文件格式。
- 通过 Vitest 和 Playwright 验证前端契约、页面行为和 bundled runtime。

## 页面与状态入口

| 页面/能力 | 代码入口 | 后端契约 | 维护要点 |
| --- | --- | --- | --- |
| 登录与改密 | `pages/LoginPage.tsx`、`api/auth.ts`、`types/auth.ts` | `/api/auth/*` | 登录结果只依赖 cookie-backed session，不把 bearer token 存入浏览器状态 |
| 仪表盘 | `pages/DashboardPage.tsx`、`hooks/useRuntimeSummary.ts`、`types/runtime.ts` | `/api/runtime/summary` | 展示生命周期、账号、平台连接、推送统计、宿主 CPU/内存/磁盘和最近推送记录 |
| 设置 | `pages/SettingsPage.tsx`、`settings/*`、`api/settings.ts` | `/api/config/*` | schema 控制字段、分组、校验和 payload，保存需要确认密码 |
| 订阅 | `pages/SubscriptionsPage.tsx`、`components/subscriptions/*`、`hooks/useSubscriptions.ts`、`subscriptions/*` | `/api/subscriptions*` | 支持动态、分组和番剧卡片，以及 targets、uids、filters、templates、atall、theme 子编辑器 |
| 日志 | `pages/LogsPage.tsx`、`hooks/useLogs.ts`、`api/logs.ts`、`types/logs.ts` | `/api/logs/*` | sourceId 来自后端白名单，清空日志需要确认密码，自动刷新状态保存在 cookie |
| 页面壳与导航 | `components/Shell.tsx`、`router/webuiRouter.ts`、`contexts/WebUiNavigationContext.tsx` | Ktor 静态路由 | `/login` 是独立路径，其他页面可 hash 切换并支持直接刷新 |
| 高风险确认 | `contexts/ConfirmationContext.tsx`、`hooks/useHighRiskConfirmation.ts` | 所有写操作 DTO 的 `confirmationPassword` | 确认弹窗是前端交互壳，真正授权只以后端当前密码校验为准 |

## 请求与 payload 契约

- 所有 JSON 请求必须经过 `src/api/http.ts` 的统一入口，unsafe 方法自动携带 CSRF 头。
- `settings/settingsSchema.ts` 是设置页字段来源；`settings/settingsPayload.ts` 负责把表单值转换为后端 DTO。
- `subscriptions/subscriptionPayloads.ts` 是订阅写操作 payload 来源；新增订阅子编辑器时必须同步 hook、payload、类型和后端 DTO。
- `utils/errorMessages.ts` 负责把 HTTP、英文异常和密码策略错误归一成可见文案；页面不应直接展示原始 exception 对象。
- `utils/storage.ts` 只读取前端所需 cookie，例如 CSRF token；不能尝试读取 HttpOnly session cookie。

## 日常开发入口

- `cd webui-frontend; npm run dev`
- `cd webui-frontend; npm run lint`

## 关键流程

1. `src/main.tsx` 只挂载 React 根节点并引入全局样式。
2. `App.tsx` 通过导航和确认上下文分发到 `LoginPage`、`DashboardPage`、`SettingsPage`、`SubscriptionsPage` 和 `LogsPage`。
3. 页面通过 `src/hooks/*` 读取运行态、订阅、日志和设置数据，再调用 `src/api/*` 发起 JSON 请求。
4. `src/api/http.ts` 统一附带 Accept、CSRF 和 JSON 头，并在认证失效时跳回登录页。
5. `src/router/webuiRouter.ts` 只用路径与 hash 处理页面切换，避免引入更重的前端路由库；`/login` 保持独立路径，`/`、`/settings`、`/subscriptions` 和 `/logs` 可以直接刷新直达，壳内跳转则只改 hash。
6. `vite build` 生成的静态产物写入后端资源目录，随后由 Ktor 静态路由直接服务。

## 资源与生命周期

- `webui-frontend` 自己拥有 Node.js 依赖、TypeScript 编译、Vitest、Playwright 和 Vite 构建生命周期。
- `src/main/resources/webui/react` 是构建输出，不是前端源码。
- `localStorage`、cookie 和页面状态只保存前端运行所需的最小信息，例如主题偏好、自动刷新和 CSRF 读取结果。
- E2E 测试使用 `webuiApiMock` 固定前端契约，不依赖生产 WebUI 端口或真实凭据。

## 配置与数据

- `package.json` scripts 控制开发、构建、测试、预览和 E2E 入口。
- `vite.config.ts` 固定 `src` 为根目录，并把构建产物输出到后端静态资源目录。
- `playwright.config.ts` 只针对 bundled runtime 的 mock 路径运行测试。
- 前端页面只处理 API DTO、表单 payload 和浏览器状态，不写 `bot.yml`、`BiliConfig.yml` 或 `BiliData.yml`。
- 当前前端依赖 React 19、TypeScript 6、Vite 8、Tailwind CSS 4、Vitest 3 和 Playwright 1；升级这些工具时必须同步检查 lint、build、Vitest、Playwright 和后端静态资源打包。

## 测试与验证

- `cd webui-frontend; npm run dev`
- `cd webui-frontend; npm run lint`
- `cd webui-frontend; npm run test`
- `cd webui-frontend; npm run test:e2e`
- `cd webui-frontend; npm run build`
- `cd webui-frontend; npm run preview`

## 禁止事项

- 禁止把 `src/main/resources/webui/react` 当作源码目录手工编辑。
- 禁止在前端直接读取或写入后端文件。
- 禁止绕过 `requestJson` 自行拼接认证和 CSRF 头。
- 禁止把页面状态或测试 mock 混成后端运行时数据。
- 禁止在源码里引入和现有页面职责无关的路由或状态库，除非确实需要替换当前轻量方案。

## 查询 checklist

- [ ] 是否已阅读根目录 `AGENTS.md` 与 `docs/AGENTS.md`？
- [ ] 是否确认查询对象属于 `webui-frontend`，而不是后端 WebUI 或资源目录？
- [ ] 是否阅读了 `modules/webui.md`、`modules/resources.md` 和必要的 build 文档？
- [ ] 是否区分当前实现、阶段性计划和过期记录？

## 变更 checklist

- [ ] 是否明确本次改的是页面、hook、API、样式、测试还是构建配置？
- [ ] 是否保持请求契约、页面路由和后端 DTO 一致？
- [ ] 是否需要同步 `docs/modules/webui.md`、`docs/modules/resources.md` 或 `docs/development/build-ci-release.md`？
- [ ] 是否运行了对应的 lint、Vitest、Playwright 和 build 检查？

## 新建 checklist

- [ ] 新页面或 hook 是否优先归入现有目录，而不是再拆出新的顶层分区？
- [ ] 新函数、方法或逻辑块是否补充紧邻注释，说明用途、意图或关键约束？
- [ ] 新 API、payload、router、样式或测试是否保持与后端契约同步？
- [ ] 新静态产物是否仍由 Vite 构建生成，而不是手工维护？
- [ ] 新测试是否只验证前端行为和 bundled 产物，不复制项目文档内容？
