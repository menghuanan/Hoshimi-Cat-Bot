# Config 模块

## 模块定位

Config 模块负责配置文件、业务数据、平台配置和迁移。当前项目同时存在旧主配置体系和 v1.8 平台配置体系，必须明确写入边界。

## 代码入口

- `src/main/kotlin/top/bilibili/BiliConfigManager.kt`
- `src/main/kotlin/top/bilibili/BiliConfig.kt`
- `src/main/kotlin/top/bilibili/BiliData.kt`
- `src/main/kotlin/top/bilibili/BiliDataWrapper.kt`
- `src/main/kotlin/top/bilibili/config/ConfigManager.kt`
- `src/main/kotlin/top/bilibili/config/BotConfigFileStore.kt`
- `src/main/kotlin/top/bilibili/config/NapCatConfig.kt`

## 主要职责

- 管理主业务配置、持久化业务数据和平台接入配置的读写边界。
- 执行 legacy 数据位置、联系人 subject、模板策略和平台配置迁移。
- 为业务层提供已加载配置和数据对象，避免业务模块直接操作 YAML 文件。
- 维持配置默认值、dataVersion 和运行期热更新语义一致。

## 文件归属

| 文件 | 管理入口 | 内容 |
| --- | --- | --- |
| `config/BiliConfig.yml` | `BiliConfigManager` | 主业务配置 |
| `config/BiliData.yml` | `BiliConfigManager` | 订阅、分组、模板策略等业务数据 |
| `config/bot.yml` | `ConfigManager` / `BotConfigFileStore` | 平台接入配置、WebUI 启动参数、targets、admins、first_run_flag |

## 当前配置模型重点

| 模型 | 代码入口 | 当前职责 |
| --- | --- | --- |
| `BiliConfig` | `BiliConfig.kt` | 主业务开关、账号 cookie、检查间隔、推送模板、图片质量、主题色、缓存、链接解析等 |
| `BiliData` | `BiliData.kt` | 动态订阅、分组、番剧、过滤器、模板策略、@全体、链接解析黑名单、版本号 |
| `BiliDataWrapper` | `BiliDataWrapper.kt` | 当前与 legacy 数据 wrapper 的序列化/反序列化和迁移桥接 |
| `BotConfig` | `config/NapCatConfig.kt` | 平台选择、OneBot11/NapCat/LlBot/QQ 官方配置、targets、admins、WebUI 参数 |
| `WebUiConfig` | `webui/config/WebUiConfig.kt` | `bot.yml.webui` 运行参数标准化，包括 enabled、host、port、凭据文件、token TTL 和外部静态目录 |

`BotConfig.selectedAdapterKind()` 和 `BotConfig.validateSelectedPlatform()` 是平台配置能否启动的关键入口；新增平台字段不能只更新 YAML 模型，还必须更新默认值、校验、标准化写回和平台文档。

## 迁移规则

`BiliConfigManager`：

- 创建 `config` 和 `data` 目录。
- 读取旧数据位置 `data/BiliData.yml` 并迁移到 `config/BiliData.yml`。
- 基于 `dataVersion` 选择 legacy wrapper 或当前 wrapper。
- 迁移联系人 subject、sourceRefs、模板策略、链接解析黑名单。
- 对于只剩 `contacts` 的旧订阅记录，优先按现有分组成员关系重建 `groupRef` 来源，再为剩余联系人补 `direct` 来源。
- 迁移完成后写回当前版本。

`BotConfigFileStore`：

- 缺失 `bot.yml` 时创建默认文件。
- 兼容 legacy 顶层 `napcat` 块。
- 写回时只保留标准 `platform` 结构。
- 写回时同时保留 `webui` 运行参数，避免 WebUI 启动配置丢失。
- 标准化写回时应保留 `targets`、`admins`、`first_run_flag` 和 `webui`，避免平台迁移覆盖运行面配置。

## 关键流程

启动阶段先由配置管理器创建目录和默认文件，再加载旧结构并按版本迁移，最后写回当前结构。运行期变更必须通过对应 manager 或协调器写入，不能让 service、tasker 或 connector 直接保存 YAML。

## 资源与生命周期

Config 模块拥有配置文件和业务数据文件的写入权，但不拥有长期协程、网络客户端或 native 资源。文件写入必须保持原子语义和编码一致，运行期缓存由调用方或协调器负责刷新。

## 配置与数据

`BiliConfig.yml` 与 `BiliData.yml` 归 `BiliConfigManager`，`bot.yml` 归 `ConfigManager`/`BotConfigFileStore`。新增字段必须说明默认值、是否持久化、是否参与迁移、是否影响热重载，以及旧配置缺失时的降级行为。

## 测试与验证

- 修改主配置或业务数据结构后，运行 `BiliConfigManagerNamespaceMigrationTest`、`BiliDataWrapperFeatureTest` 或相关迁移测试。
- 修改平台配置后，运行 `BotConfigFileStoreTest`、`PlatformConfigCompatibilityTest` 或 connector 初始化测试。
- 修改模板策略持久化后，运行 `TemplateRuntimeCoordinatorTest` 和相关 service 测试。

## 禁止事项

- 禁止业务模块直接写配置 YAML。
- 禁止把 legacy `napcat` 块继续写回标准 `bot.yml`。
- 禁止运行中随意全量迁移 `BiliData` 结构。
- 禁止修改配置数据类签名后不更新 KDoc 或迁移逻辑。

## 查询 checklist

- [ ] 是否已阅读根目录 `AGENTS.md` 与 `docs/AGENTS.md`？
- [ ] 是否确认查询对象属于本模块，而不是相邻模块、历史计划或过期文档？
- [ ] 是否阅读本文档列出的代码入口、禁止事项和相关 domain/architecture 文档？
- [ ] 是否区分当前实现、阶段性计划和过期记录？
## 变更 checklist

- [ ] 明确本次改的是 `BiliConfig.yml`、`BiliData.yml` 还是 `bot.yml`？
- [ ] 是否需要 dataVersion 递增？
- [ ] 是否需要 legacy wrapper？
- [ ] 是否会影响 `TemplateRuntimeCoordinator.snapshotPolicies()` 保存？
- [ ] 是否更新 [`../development/change-classification.md`](../development/change-classification.md)？
- [ ] 是否运行 `BiliConfigManagerNamespaceMigrationTest`、`BotConfigFileStoreTest`、`PlatformConfigCompatibilityTest`？
## 新建 checklist

- [ ] 新文件是否优先归入本模块既有入口，而不是新增顶层包？
- [ ] 新函数、方法或逻辑块是否补充紧邻注释，说明用途、意图或关键约束？
- [ ] 新配置、数据结构、资源、协程、客户端、缓存、channel 或 native 对象是否有明确生命周期和归属边界？
- [ ] 新外部行为是否同步更新相关 domain、architecture、development 或 operations 文档？
- [ ] 新测试是否只验证源码行为或产物，不复制项目文档内容？
