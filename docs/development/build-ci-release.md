# 构建、CI 与发布约束

## 文档定位

本文约束 Gradle 构建、Docker 构建、CI workflow、发布 workflow、entrypoint 和版本发布相关脚本的查询、修改和新增。部署运行细节继续以 [`../operations/deployment.md`](../operations/deployment.md) 和 [`../operations/memory-tuning.md`](../operations/memory-tuning.md) 为准。

## 文件入口

- `build.gradle.kts`
- `settings.gradle.kts`
- `gradle.properties`
- `gradlew`
- `gradlew.bat`
- `Dockerfile`
- `docker-compose.yml`
- `docker-entrypoint.sh`
- `.dockerignore`
- `.github/workflows/ci.yml`
- `.github/workflows/release.yml`

根目录说明、授权、编辑器和忽略规则的通用维护边界见 [`repository-files.md`](repository-files.md)；本文件只在这些文件影响构建、Docker、CI 或发布产物时作为额外约束。

## 主要职责

- 定义 Kotlin、Gradle、依赖、测试和打包任务。
- 定义 Docker 镜像构建、运行参数和 entrypoint 行为。
- 定义 CI 验证、发布产物和 DockerHub tag 规则。
- 保持本地、CI、Docker 和裸机发行包的行为一致。

## 禁止事项

- 禁止绕过 Gradle wrapper 改成依赖本机全局 Gradle。原因：CI 和开发环境会分裂。
- 禁止在 CI 或发布日志中输出 secrets、cookie、token 或完整签名参数。原因：workflow 日志会长期保留。
- 禁止修改 Docker/JVM 内存参数但不更新运维文档。原因：内存策略是当前部署稳定性边界。
- 禁止新增发布产物但不说明版本、tag 和兼容规则。原因：用户无法判断升级路径。

## 构建约束

- Kotlin、依赖和插件升级必须说明兼容影响，并优先运行最小相关测试，再运行完整测试。
- 新增 Gradle task 必须有清晰输入、输出和是否参与 CI/Release 的说明。
- 变更 `gradle.properties` 时必须确认 JVM 参数是否只影响构建期，还是也影响运行期。
- `webui-frontend` 是独立 Node/Vite 工程；`npm run build` 会把 React 静态产物输出到 `src/main/resources/webui/react`，随后再由 Gradle `processResources` 打入后端发行包。
- 前端依赖、TypeScript、Tailwind、Vite 或 Playwright 升级时，必须同时验证 `npm run lint`、`npm run test`、`npm run build` 和 `npm run test:e2e` 的影响。

## CI 约束

- CI workflow 必须保持可重复执行，不依赖本地未提交文件。
- 新增缓存必须有 key 失效策略，不能缓存 secrets 或临时诊断输出。
- 新增矩阵维度必须说明覆盖目的，避免无意义拉长反馈时间。

## Docker 与发布约束

- Dockerfile 和 entrypoint 变更必须同步核对 `JAVA_TOOL_OPTIONS`、jemalloc、NMT、Skia 软件渲染和平台库依赖。
- `docker-compose.yml` 变更必须考虑已有用户的 volume、环境变量和升级兼容。
- 发布 workflow 变更必须同步核对 release notes、tag 命名和产物命名。
- 裸机发布包若内置 `jlink` runtime，Windows/Linux 必须分别在对应平台 runner 上打包，再聚合到 GitHub Release。
- 若发布产物包含 WebUI 静态资源，必须确认 `src/main/resources/webui/react/index.html` 和 `assets/app.js`、`assets/app.css` 来自当前前端构建，而不是手工编辑或旧产物残留。

## 查询 checklist

- [ ] 是否已阅读根目录 `AGENTS.md` 与 `docs/AGENTS.md`？
- [ ] 是否确认查询对象是构建期、运行期、CI 还是发布期？
- [ ] 是否核对 [`../operations/deployment.md`](../operations/deployment.md) 与 [`../operations/memory-tuning.md`](../operations/memory-tuning.md)？
- [ ] 是否避免从历史计划或过期 Docker 文档推断当前 workflow？

## 变更 checklist

- [ ] 是否影响本地、CI、Docker、裸机发行包中的一个或多个环境？
- [ ] 是否同步更新部署、内存、发布或测试策略文档？
- [ ] 是否保留 secrets 屏蔽和最小权限原则？
- [ ] 是否运行最小相关 Gradle、Docker 或 workflow 静态检查？
- [ ] 是否确认变更不会把临时诊断文件打进发行包？

## 新建 checklist

- [ ] 新 task、workflow、镜像 stage 或发布产物是否有明确使用者？
- [ ] 新环境变量是否有默认值、文档和兼容策略？
- [ ] 新发布路径是否说明 tag、文件名、保留策略和失败回滚方式？
- [ ] 新构建逻辑是否能在干净 checkout 中复现？
