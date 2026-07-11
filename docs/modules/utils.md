# Utils 模块

## 模块定位

Utils 模块提供跨模块复用的轻量工具，包括联系人 subject、字体工具、图片缓存、图片预处理、JSON 工具和翻译工具。工具代码必须保持低依赖，不得成为隐藏的业务编排层。

## 代码入口

- `src/main/kotlin/top/bilibili/utils/ContactExt.kt`
- `src/main/kotlin/top/bilibili/utils/ContactSubject.kt`
- `src/main/kotlin/top/bilibili/utils/FontUtils.kt`
- `src/main/kotlin/top/bilibili/utils/General.kt`
- `src/main/kotlin/top/bilibili/utils/ImageCache.kt`
- `src/main/kotlin/top/bilibili/utils/ImagePreprocessing.kt`
- `src/main/kotlin/top/bilibili/utils/Json2DataClass.kt`
- `src/main/kotlin/top/bilibili/utils/JsonUtils.kt`
- `src/main/kotlin/top/bilibili/utils/translate/*`

## 主要职责

- 提供联系人 subject 与平台中立联系人之间的转换辅助。
- 管理字体加载、段落缓存和图片缓存辅助能力。
- 提供图片尺寸检测、预处理和降级链路辅助能力。
- 提供稳定的 JSON 配置和调试转换工具。
- 封装少量独立外部工具调用。

## 禁止事项

- 禁止在 utils 中直接写业务配置文件。原因：配置写入边界必须清晰。
- 禁止在 utils 中直接发送平台消息。原因：发送必须走 message gateway 和 capability service。
- 禁止在 utils 中持有未登记的长期协程、网络客户端或 native 资源。原因：停机无法追踪。
- 禁止在渲染热路径反复创建新的 `Json` 配置块。原因：会增加热路径分配和行为分歧。

当前兼容例外：`General.kt` 的 `actionNotify()` 仍直接调用 capability service 和 message gateway。该入口不得作为新代码范例，也不得继续扩展；迁移债务见 [`../bugs.md#bug-008-utils-管理员通知跨越平台发送边界`](../bugs.md#bug-008-utils-管理员通知跨越平台发送边界)。

## 关键流程

工具函数应由调用方决定业务语义。涉及联系人归一化时遵守 [`domain/platform-adapters.md`](../domain/platform-adapters.md)；涉及图片和字体时遵守 [`modules/draw.md`](draw.md) 与 [`modules/skia.md`](skia.md)；涉及 JSON 序列化时优先复用已有共享实例。

## 资源与生命周期

`FontUtils`、`ImageCache` 和图片预处理工具可能影响 native/heap 资源。新增缓存、全局 map 或外部客户端时，必须提供清理入口，并把清理入口接入对应 tasker、Skia 清理或 core 停机流程。

## 配置与数据

Utils 不拥有配置写入权。工具可以读取调用方传入的配置值，但不得自行决定迁移、保存或热重载策略。

## 测试与验证

- 修改联系人 subject、图片预处理、字体工具或 JSON 共享实例后，运行对应 utils、connector、draw 或 config 测试。
- 新增缓存、全局状态或外部 IO 后，验证清理入口、超时和错误处理路径。
- 修改热路径工具后，检查是否引入重复分配、隐藏业务依赖或未登记资源。

## 查询 checklist

- [ ] 是否已阅读根目录 `AGENTS.md` 与 `docs/AGENTS.md`？
- [ ] 是否确认工具函数是否存在隐藏副作用、缓存或外部 IO？
- [ ] 是否按功能同步阅读 connector、draw、skia、data 或 development 文档？
- [ ] 是否避免把某个工具的历史计划当作当前公共契约？

## 变更 checklist

- [ ] 是否保持工具低依赖，不反向依赖 service/tasker？
- [ ] 是否新增缓存或 native 资源？若是，是否有清理入口？
- [ ] 是否改变联系人 subject 格式？若是，是否更新平台适配和迁移文档？
- [ ] 是否改变图片预处理阈值或字体加载策略？若是，是否更新 draw/skia 文档？
- [ ] 是否运行对应 utils、draw、skia 或 connector 测试？

## 新建 checklist

- [ ] 新工具是否至少被两个模块复用，或确实不属于某个单一模块？
- [ ] 新工具是否无隐藏全局状态；若有，是否说明生命周期和清理方式？
- [ ] 新外部 IO 是否有超时、错误处理和调用方可控策略？
- [ ] 新公共函数是否补充紧邻注释，说明用途、意图或关键约束？
