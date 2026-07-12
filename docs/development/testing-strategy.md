# 测试策略

本文记录当前项目测试分层和修改后的验证建议。

## 测试边界

- 测试只能验证源码行为、配置产物、打包产物或运行约束，不得复制 `docs` 文档正文、表格字段或历史计划片段到 `src/test`。
- 新增测试前先判断归属模块，并阅读对应 `docs/modules/*.md` 的查询、变更和新建 checklist。
- 回归测试命名应表达守护的行为边界，不要把一次性 bug 描述写成长期测试契约。
- 手动或高成本测试必须放入明确的 manual/diagnostics 分组，避免进入默认快速反馈链路。
- 测试 fixture 只能放与测试直接相关的最小样本，不得复制生产配置中的敏感 cookie、token 或真实管理员列表。

## 测试入口

常规测试：

```powershell
./gradlew.bat test
```

Skia native memory 证据测试：

```powershell
./gradlew.bat skiaNativeMemoryEvidenceTest
```

## 测试分层

### 配置与迁移

代表测试：

- `BiliConfigManagerNamespaceMigrationTest`
- `BiliDataWrapperFeatureTest`
- `BotConfigFileStoreTest`
- `PlatformConfigCompatibilityTest`
- `SubjectColorMigrationRemovalRegressionTest`

修改配置模型、数据版本、平台配置保存时必须运行相关测试。

### 平台连接

代表测试：

- `PlatformConnectorManagerTest`
- `CapabilityGuardTest`
- `PlatformModelSmokeTest`
- `ConnectorBoundaryRegressionTest`
- `OneBot11AdapterTest`
- `LlBotAdapterTest`
- `QQOfficialAdapterTest`

修改 connector、capability、message model 时必须运行。

### Tasker 与生命周期

代表测试：

- `BiliTaskerRegressionTest`
- `TaskLifecycleBoundaryRegressionTest`
- `TaskSelfHealingTest`
- `ProcessGuardianRecoveryTest`
- `ProcessGuardianResourceObservabilityTest`

新增 Tasker、worker 或资源策略时必须运行。

### Core 与资源监督

代表测试：

- `ResourceSupervisorTest`
- `TaskResourcePolicyRegistryTest`
- `ResourceManagementRegressionGuardTest`
- `BiliCommandProcessorTest`

修改 core、资源分区、channel、命令入口或停机顺序时必须运行。若当前不存在精确测试，应选择最接近的 tasker/lifecycle 测试，并在变更说明中列出未覆盖风险。

### Skia 与绘图

代表测试：

- `ParagraphBuilderLifecycleRegressionTest`
- `SkiaDrawSceneCoverageTest`
- `SkiaGradientRuntimeRegressionTest`
- `SkiaNativeMemoryEvidenceTest`
- `ContentDescLayoutTest`
- `PgcCardLayoutRegressionTest`

修改绘图、字体、缓存和 Skia 生命周期时必须运行。

### 服务与命令

代表测试：

- `CommandRegressionGuardTest`
- `TemplateRuntimeCoordinatorTest`
- `TemplateSelectionServiceTest`
- `TemplateRenderServiceTest`
- `LinkResolvePolicyServiceTest`
- `ResolveLinkServiceOrderingTest`
- `AtAllServiceFeatureTest`

修改命令、模板、链接解析、发送链路时必须运行。

### 部署与发布

代表测试：

- `DockerRuntimeConfigRegressionTest`
- `DockerfileLoginDependencyRegressionTest`
- `LinuxLauncherDependencyPromptTest`
- `PlatformReleasePackagingRegressionTest`
- `ReleaseWorkflowTagPolicyTest`
- `CiWorkflowObservabilityTest`

修改 Dockerfile、entrypoint、Gradle 发布任务或 CI 时必须运行。

### Utils 与 Resources

代表测试：

- `ImagePreprocessPolicyTest`
- `LoadFontsRegressionTest`
- `ContactIdNamespaceTest`
- `DrawCacheKeyNormalizationTest`
- 资源加载或绘图 smoke test

修改 `utils/*`、字体、图片、SVG、日志配置或资源路径时必须运行相关测试。`ImageCache` 与 `JsonUtils` 当前没有一一对应的专用测试类；修改它们时应运行调用链最接近的 draw/service/config 测试，并把直接覆盖缺口写入变更说明。若新增资源影响发行包，必须同时检查打包产物。

### WebUI 服务端

代表测试：

- `WebUiAuthServiceTest`
- `WebUiTokenServiceTest`
- `WebUiCredentialStoreTest`
- `WebUiPasswordPolicyTest`
- `WebUiRouteSmokeTest`
- `WebUiAuthRoutesTest`
- `WebUiManagerTest`
- `WebUiSubscriptionManagementFacadeTest`
- `WebUiConfigWriteFacadeTest`
- `WebUiRuntimeFacadeTest`
- `WebUiLogFacadeTest`
- `WebUiAuditServiceTest`

新增 WebUI facade 时应优先补同层单元测试，再补路由测试。修改认证、CSRF、高风险确认、日志白名单或配置写入时必须运行对应服务端测试；普通保存测试必须覆盖刷新后的 cookie session，并断言 payload 不包含登录密码。

### WebUI 前端

代表测试：

- `webui-frontend/src/**/*.test.ts`
- `webui-frontend/src/**/*.test.tsx`
- `webui-frontend/e2e/*`

Vitest 和 Testing Library 验证 hook、payload、错误文案和页面契约。Playwright 验证 bundled runtime，默认通过 `webuiApiMock` 固定后端契约，不依赖生产端口或真实凭据。修改前端 API DTO、payload、router 或静态资源输出时，应至少运行 `npm run test` 和 `npm run build`；涉及页面流和 bundled runtime 时再运行 `npm run test:e2e`。

## 查询 checklist

- [ ] 是否已确认测试属于哪个模块或运行环境？
- [ ] 是否只读取当前维护文档，不从过期文档推断当前测试要求？
- [ ] 是否确认测试 fixture 没有复制项目文档或敏感生产数据？

## 变更 checklist

- [ ] 是否优先运行与本次变更最相关的最小测试集合？
- [ ] 是否避免为了通过测试而固化文档正文或历史计划内容？
- [ ] 是否新增、修改或删除测试时同步更新模块文档中的测试建议？
- [ ] 是否确认手动测试不会进入默认 CI 快速路径？

## 新建 checklist

- [ ] 新测试是否有明确守护行为和失败信号？
- [ ] 新 fixture 是否是最小样本，并且不包含 docs 正文或敏感信息？
- [ ] 新测试分组是否符合配置、平台、core、tasker、skia、service、utils、resources 或部署发布分层？
- [ ] 新高成本测试是否有单独 Gradle task 或明确排除策略？

## 文档变更验证

文档-only 变更至少执行：

- 文件存在性检查。
- UTF-8 读取检查。
- `git diff -- docs` 人工确认。

不要把 `docs` 内容复制进 `src/test`。
当前维护文档位于被 `.gitignore` 忽略的 docs 分区时，仍要按 UTF-8 读取、修改和检查链接；不能因为未跟踪就跳过验证。
