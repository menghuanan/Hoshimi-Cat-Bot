# Bootstrap 模块

## 模块定位

Bootstrap 模块负责进程启动、运行目录初始化、Skiko 初始化、主 bot 装配和停机入口注册。它只做组件装配和生命周期接线，不承载业务命令、平台协议或轮询逻辑。

## 代码入口

- `src/main/kotlin/top/bilibili/Main.kt`
- `src/main/kotlin/top/bilibili/Init.kt`
- `src/main/kotlin/top/bilibili/SkikoInitializer.kt`
- `src/main/kotlin/top/bilibili/JvmRuntimeDiagnostics.kt`
- `src/main/kotlin/top/bilibili/core/BiliBiliBot.kt`

## 主要职责

- 解析启动参数和运行环境。
- 初始化 Skiko、配置目录、日志和必要运行目录。
- 创建并启动 `BiliBiliBot`。
- 注册 shutdown hook 并触发有序停机。
- 在 Skiko 初始化后输出部署来源、关键 HotSpot VM option 和 Skia 实际资源缓存上限。
- 把启动阶段异常明确写入日志并维护生命周期状态；当前并非所有失败都会转换为非零进程退出状态。

## 禁止事项

- 禁止在启动入口实现业务命令分支。原因：命令权限、帮助文案和配置写入必须留在 service 层。
- 禁止在启动入口直接创建 vendor adapter 或底层 transport。原因：平台生命周期必须由 connector/core 统一管理。
- 禁止在启动阶段绕过 `ResourceSupervisor` 注册或关闭长期资源。原因：停机顺序依赖资源分区。
- 禁止把 Skiko 初始化失败静默吞掉。原因：后续绘图路径会以更隐蔽方式失败。

## 关键流程

启动链路按以下顺序理解：

1. `Main.main()` 初始化 Skiko 与绘图相关 native 环境。
2. 解析命令行参数并注册 shutdown hook。
3. `BiliBiliBot.start()` 加载主配置，再准备运行目录并加载 `bot.yml`。
4. 按配置启动可选 WebUI，装配 connector、message gateway 和事件收集器。
5. 启动平台连接并注册 `ResourceSupervisor` 分区。
6. 同步完成业务数据初始化，并逐个等待 Tasker 的 `init()` 确认；全部硬依赖成功后才进入运行态。
7. 运行态建立后异步执行可降级的运行期预热与首次运行检查。

`BiliBiliBot.start()` 通过 `BotStartResult` 同步返回启动结论。`Main.main()` 只在 `STARTED` 时等待当前线程；配置、平台、connector、业务数据或 Tasker 初始化失败都返回 `FAILED`，主入口以状态码 1 退出。运行期预热和首次提示属于可降级步骤，不阻断已经完成的核心启动。

修改启动流程时必须同步核对 [`modules/core.md`](core.md)、[`modules/config.md`](config.md)、[`modules/connector.md`](connector.md)、[`modules/tasker.md`](tasker.md) 和 [`operations/deployment.md`](../operations/deployment.md)。

## 资源与生命周期

Bootstrap 本身不应长期持有资源；它创建的长期资源必须交给 `BiliBiliBot`、`PlatformConnectorManager`、`BiliTasker` 或 `ResourceSupervisor` 管理。新增启动阶段资源时，必须说明关闭入口和停机阶段。

## 配置与数据

Bootstrap 可以触发配置加载，但不得绕过 `BiliConfigManager`、`ConfigManager` 或 `BotConfigFileStore` 写文件。新增启动参数或环境变量时，必须同步更新部署文档和配置变更分类。

## 测试与验证

- 修改启动顺序、初始化入口或装配依赖后，运行启动、配置初始化和平台装配相关测试。
- 修改 Docker、JVM 参数或资源初始化路径后，同步检查 [`../operations/deployment.md`](../operations/deployment.md) 与 [`../development/build-ci-release.md`](../development/build-ci-release.md)。
- 新增启动逻辑后，验证它不会绕过 config、connector、core 或 tasker 的生命周期入口。
- 修改启动失败处理后，验证主配置失败、平台配置无效和 connector 启动异常都会产生明确进程状态。

## 查询 checklist

- [ ] 是否已阅读根目录 `AGENTS.md` 与 `docs/AGENTS.md`？
- [ ] 是否确认查询对象是启动装配、环境初始化还是停机入口？
- [ ] 是否同时核对 core、config、connector、tasker 的职责边界？
- [ ] 是否避免把 `docs/plans/` 或过期部署文档当作当前启动事实？

## 变更 checklist

- [ ] 是否保持启动层只负责装配和生命周期？
- [ ] 是否同步检查 [`architecture/layer-contracts.md`](../architecture/layer-contracts.md) 的启动层边界？
- [ ] 是否影响 Docker、entrypoint、Gradle run 或裸机发行包？若是，是否更新 [`operations/deployment.md`](../operations/deployment.md)？
- [ ] 是否新增 shutdown hook、全局状态或 native 初始化？若是，是否登记关闭路径？
- [ ] 是否运行启动、配置或部署相关测试？

## 新建 checklist

- [ ] 新启动入口是否确实不能归入现有 `Main.kt`、`Init.kt` 或 `BiliBiliBot`？
- [ ] 新环境变量、启动参数或目录是否有默认值、兼容策略和部署说明？
- [ ] 新长期资源是否交给 core/resource 或对应模块管理？
- [ ] 新入口是否补充紧邻注释，说明启动意图和生命周期边界？
