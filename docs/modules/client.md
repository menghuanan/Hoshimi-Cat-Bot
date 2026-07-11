# Client 模块

_最后复核：2026-07-12_

## 模块定位

Client 模块封装 B 站 HTTP 请求、重试、超时、代理、连接池资源和运行期观测。它是 API 层和 Tasker 轮询链路的网络边界。

## 代码入口

- `src/main/kotlin/top/bilibili/client/BiliClient.kt`
- `src/main/kotlin/top/bilibili/api/*`
- 共享关闭入口：`closeUtilsClient()`、`closeServiceClient()`、`BiliCheckTasker.closeSharedClient()`
- 运行期观测入口：`BiliClient.runtimeSnapshot()`、`ProcessGuardian`

## 主要职责

- 创建 Ktor OkHttp `HttpClient`。
- 统一请求头和 Cookie。
- 按配置设置 timeout。
- 对可重试网络错误做有限重试。
- 延迟创建 retry slot，减少常驻 OkHttp 资源。
- 为 `ProcessGuardian` 提供 `BiliClientRuntimeSnapshot`。

## 关键流程

调用方通过 API 扩展函数进入 `BiliClient`，由 client 统一补齐请求头、cookie、timeout、trace 和重试策略，再把响应交回 API/data 层解析。新增网络路径必须保持 ownerTag 和 `ApiRequestTrace` 可观测，不能在调用方临时创建独立 HTTP 栈。

## 重试策略

- `request()` 默认最大重试次数：1 次；调用方显式传入的 `maxRetries` 仍受非负校验。
- 重试等待：3 秒。
- 可重试：`IOException`、`HttpRequestTimeoutException`。
- 超时不轮换 retry slot。
- 连接类错误才轮换 retry slot。
- retry slot 容量固定为 2，并按需创建；首个请求使用主 client，发生连接类重试时才轮换到底层独立 slot。

## 资源与生命周期

`BiliClient.close()` 会：

- 标记 `closed=true`。
- 清空 retry slots。
- 从活跃弱引用表移除实例。
- 关闭已创建的底层 `HttpClient`。

close 后 `ensureClientOpen()` 会阻断后续请求和 retry slot 创建。

`runtimeSnapshot()` 只汇总当前仍活跃的弱引用实例，按 `ownerTag` 暴露主连接池和已创建 retry slot 的连接、空闲连接、排队调用与运行调用数量；快照不包含 Cookie、请求体或响应内容。

## 配置与数据

Client 读取 `BiliConfig` 中的 cookie、代理、timeout 和运行参数，但不写入配置或业务数据。新增 client 参数时，必须同步确认配置默认值、迁移策略和 [`../development/change-classification.md`](../development/change-classification.md) 是否需要更新。

## 测试与验证

- 修改重试、timeout、cookie、代理或 ownerTag 后，运行 `BiliClientLogContextTest`、`BiliClientSourceRegressionTest` 或相关 API 调用测试。
- 修改连接池、retry slot 或 close 行为后，检查 `ProcessGuardian` 资源快照和 close 后阻断路径。
- 新增轮询 API 后，验证 `ApiRequestTrace` 和异常日志不会泄露敏感 cookie。

## 禁止事项

- 禁止 close 后复用 `BiliClient`。
- 禁止在高频路径为每个请求创建新 `BiliClient`。
- 禁止跳过 `ApiRequestTrace` 新增轮询 API。
- 禁止在 client 层修改订阅数据或发送平台消息。

## 查询 checklist

- [ ] 是否已阅读根目录 `AGENTS.md` 与 `docs/AGENTS.md`？
- [ ] 是否确认查询对象属于本模块，而不是相邻模块、历史计划或过期文档？
- [ ] 是否阅读本文档列出的代码入口、禁止事项和相关 domain/architecture 文档？
- [ ] 是否区分当前实现、阶段性计划和过期记录？
## 变更 checklist

- [ ] 新 API 是否使用 `ApiRequestTrace`？
- [ ] 超时和重试是否沿用 `BiliClient`？
- [ ] 是否需要更新 [`../domain/bilibili-api.md`](../domain/bilibili-api.md)？
- [ ] 是否影响 `ProcessGuardian` 的 ownerTag 资源快照？
- [ ] 是否运行 `BiliClientLogContextTest`、`BiliClientSourceRegressionTest` 或相关 API 测试？
## 新建 checklist

- [ ] 新文件是否优先归入本模块既有入口，而不是新增顶层包？
- [ ] 新函数、方法或逻辑块是否补充紧邻注释，说明用途、意图或关键约束？
- [ ] 新配置、数据结构、资源、协程、客户端、缓存、channel 或 native 对象是否有明确生命周期和归属边界？
- [ ] 新外部行为是否同步更新相关 domain、architecture、development 或 operations 文档？
- [ ] 新测试是否只验证源码行为或产物，不复制项目文档内容？
