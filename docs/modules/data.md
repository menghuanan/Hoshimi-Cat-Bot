# Data 模块

## 模块定位

Data 模块定义 B 站响应模型、业务消息模型和持久化数据结构。它是 API、service、draw、tasker 共享的模型层。

## 代码入口

- `src/main/kotlin/top/bilibili/data/*`
- `src/main/kotlin/top/bilibili/BiliData.kt`
- `src/main/kotlin/top/bilibili/BiliDataWrapper.kt`

## 主要职责

- 使用 kotlinx.serialization 映射 B 站 JSON 字段。
- 保存订阅、分组、过滤、模板策略等业务状态。
- 提供轻量派生属性，例如动态类型 fallback、直播时间统一字段。

## 序列化规则

- B 站字段不稳定时优先使用可空字段或安全默认值。
- 未知动态类型必须降级为 `DYNAMIC_TYPE_UNKNOWN`。
- 持久化结构变更必须考虑 `BiliDataWrapper` 和 legacy wrapper。

## 关键流程

API 层把 B 站响应解码为 data 模型，service/tasker/draw 只消费这些模型并做业务编排。持久化业务数据经 `BiliDataWrapper` 和 config manager 加载、迁移、保存，不能由 data 类自行触发 IO。

## 资源与生命周期

Data 模块不拥有协程、网络客户端、文件句柄、native 资源或缓存。新增派生属性必须保持纯计算或轻量默认值；若需要缓存，应放到 service、utils 或专门生命周期管理入口。

## 配置与数据

Data 定义可序列化结构和业务状态字段，但不拥有写入权。新增持久化字段必须说明默认值、兼容旧文件的行为、是否需要 dataVersion 迁移，以及是否影响模板占位符或动态类型文档。

## 测试与验证

- 修改 B 站响应模型后，运行对应 decode 测试或 API 回归测试。
- 修改持久化数据结构后，运行 `BiliDataWrapperFeatureTest` 和相关迁移测试。
- 修改动态类型、模板相关字段或展示字段后，检查 [`../domain/dynamic-types.md`](../domain/dynamic-types.md) 与 [`../domain/template-placeholders.md`](../domain/template-placeholders.md)。

## 禁止事项

- 禁止 data 层发起网络请求。
- 禁止 data 层发送平台消息。
- 禁止 data 层直接写文件。
- 禁止删除现有字段迁移能力而不提升版本和补迁移。

## 查询 checklist

- [ ] 是否已阅读根目录 `AGENTS.md` 与 `docs/AGENTS.md`？
- [ ] 是否确认查询对象属于本模块，而不是相邻模块、历史计划或过期文档？
- [ ] 是否阅读本文档列出的代码入口、禁止事项和相关 domain/architecture 文档？
- [ ] 是否区分当前实现、阶段性计划和过期记录？
## 变更 checklist

- [ ] 是否新增 `@SerialName`？
- [ ] 字段是否需要默认值以兼容旧响应？
- [ ] 是否影响 `BiliDataWrapper`？
- [ ] 是否需要 dataVersion 迁移？
- [ ] 是否运行 data 相关测试，如 `BiliDataWrapperFeatureTest`、`ArticleViewInfoDecodeTest`？
## 新建 checklist

- [ ] 新文件是否优先归入本模块既有入口，而不是新增顶层包？
- [ ] 新函数、方法或逻辑块是否补充紧邻注释，说明用途、意图或关键约束？
- [ ] 新配置、数据结构、资源、协程、客户端、缓存、channel 或 native 对象是否有明确生命周期和归属边界？
- [ ] 新外部行为是否同步更新相关 domain、architecture、development 或 operations 文档？
- [ ] 新测试是否只验证源码行为或产物，不复制项目文档内容？
