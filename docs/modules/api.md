# API 模块

## 模块定位

API 模块定义 B 站接口常量和 `BiliClient` 扩展函数，负责把业务参数转换为 HTTP 请求并解析为 data 模型。

## 代码入口

- `src/main/kotlin/top/bilibili/api/Api.kt`
- `src/main/kotlin/top/bilibili/api/Dynamic.kt`
- `src/main/kotlin/top/bilibili/api/Live.kt`
- `src/main/kotlin/top/bilibili/api/User.kt`
- `src/main/kotlin/top/bilibili/api/Pgc.kt`
- `src/main/kotlin/top/bilibili/api/General.kt`

## 主要职责

- 集中维护 B 站 API URL。
- 为 `BiliClient` 提供领域扩展函数。
- 补充接口参数和 trace。
- 把响应解析为 `data` 模型。

## 参数约束

动态接口应保持：

- `timezone_offset=-480`
- `features=itemOpusStyle`

直播状态接口应区分 `null` 和空结果。

## 禁止事项

- 禁止 API 模块修改订阅数据。
- 禁止 API 模块发送平台消息。
- 禁止绕过 `BiliClient` 使用新的 HTTP 客户端。

## 关键流程

API 调用由 service 或 tasker 发起，经 `BiliClient` 发送请求，再由本模块把响应解码为 `data` 模型。新增接口必须先确认 B 站返回结构、错误码和重试语义，再决定是否需要更新 [`../domain/bilibili-api.md`](../domain/bilibili-api.md)。

## 资源与生命周期

API 模块不持有长期网络客户端、协程、缓存或文件句柄。网络连接生命周期归属 `BiliClient`；新增临时资源必须在调用作用域内关闭，不能把资源泄漏给调用方隐式管理。

## 配置与数据

API 模块只读取调用方传入的 cookie、参数和 `BiliClient` 配置结果，不拥有配置写入权。新增响应字段必须落在 `data` 模块，并考虑可空、默认值和未知类型降级。

## 测试与验证

- 修改 API 参数或响应模型后，运行对应 API decode、client trace 或 service/tasker 调用测试。
- 新增接口后，至少验证 `ApiRequestTrace`、错误响应和可重试异常路径。
- 涉及领域语义变化时，同步检查 [`../domain/bilibili-api.md`](../domain/bilibili-api.md)。

## 查询 checklist

- [ ] 是否已阅读根目录 `AGENTS.md` 与 `docs/AGENTS.md`？
- [ ] 是否确认查询对象属于本模块，而不是相邻模块、历史计划或过期文档？
- [ ] 是否阅读本文档列出的代码入口、禁止事项和相关 domain/architecture 文档？
- [ ] 是否区分当前实现、阶段性计划和过期记录？
## 变更 checklist

- [ ] 新接口常量是否加入 `Api.kt`？
- [ ] 扩展函数是否包含必要 KDoc？
- [ ] 是否传入 `ApiRequestTrace`？
- [ ] 是否更新 [`../domain/bilibili-api.md`](../domain/bilibili-api.md)？
- [ ] 是否补充解析测试或客户端日志测试？
## 新建 checklist

- [ ] 新文件是否优先归入本模块既有入口，而不是新增顶层包？
- [ ] 新函数、方法或逻辑块是否补充紧邻注释，说明用途、意图或关键约束？
- [ ] 新配置、数据结构、资源、协程、客户端、缓存、channel 或 native 对象是否有明确生命周期和归属边界？
- [ ] 新外部行为是否同步更新相关 domain、architecture、development 或 operations 文档？
- [ ] 新测试是否只验证源码行为或产物，不复制项目文档内容？
