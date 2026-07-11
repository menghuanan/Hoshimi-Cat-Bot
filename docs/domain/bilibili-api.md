# B 站 API 行为与限制

**覆盖度**：接口行为 ✅ | 常量清单 ✅ | 边界情况 ⚠️

本文记录当前代码中对 B 站接口的使用方式、已知行为和实现约束。接口行为可能随 B 站调整而变化；新增或修改接口时应优先查看当前实现和回归测试。

## 客户端入口

所有 B 站 HTTP 请求应通过 `BiliClient` 发起。

`BiliClient` 当前负责：

- 使用 Ktor OkHttp engine。
- 统一注入 `Origin`、`Referrer` 和浏览器 `User-Agent`。
- 使用 `BiliBiliBot.cookie` 和 `BiliBiliBot.uid` 拼接 Cookie。
- 按 `BiliConfigManager.config.checkConfig.timeout` 设置 socket/connect/request timeout。
- 支持代理列表轮换。
- 对 `IOException` 和 `HttpRequestTimeoutException` 做有限重试。
- 使用 `ApiRequestTrace` 输出来源、接口和失败原因。
- 暴露 retry slot、连接池和 dispatcher 运行态给 `ProcessGuardian`。

## 当前接口常量

接口地址集中在 `top.bilibili.api.Api.kt`。下面是当前文件中的完整常量表，不再使用 `GROUP_*` 这类模糊占位写法。

| 领域 | 常量 | 用途 |
| --- | --- | --- |
| 登录 | `LOGIN_QRCODE` | 生成网页登录二维码 |
| 登录 | `LOGIN_INFO` | 轮询二维码登录状态 |
| 动态 | `NEW_DYNAMIC` | 拉取全站/关注动态 feed |
| 动态 | `SPACE_DYNAMIC` | 拉取用户空间动态 |
| 动态 | `DYNAMIC_DETAIL` | 拉取动态详情 |
| 视频 | `VIDEO_DETAIL` | 拉取视频详情 |
| 专栏 | `ARTICLE_VIEW` | 拉取专栏视图信息，用于解析 dynId 等 |
| 专栏 | `ARTICLE_DETAIL` | 拉取旧专栏详情 |
| 专栏 | `ARTICLE_LIST` | 拉取专栏卡片列表 |
| 直播 | `LIVE_LIST` | 拉取关注直播列表 |
| 直播 | `LIVE_STATUS_BATCH` | 批量查询多个 UID 的直播状态 |
| 直播 | `LIVE_DETAIL` | 拉取单个直播间详情 |
| 搜索 | `SEARCH` | 搜索用户等实体 |
| 用户空间 | `USER_INFO` | 拉取用户基础信息 |
| 用户空间 | `USER_INFO_WBI` | 通过 WBI 接口拉取用户信息 |
| 用户空间 | `USER_ID` | 拉取当前登录用户 ID / nav 信息 |
| 用户空间 | `SPACE_SEARCH` | 拉取空间投稿搜索结果 |
| 关注 | `IS_FOLLOW` | 查询关注关系 |
| 关注 | `FOLLOW` | 修改关注关系 |
| 分组 | `GROUP_LIST` | 拉取关注分组列表 |
| 分组 | `CREATE_GROUP` | 创建关注分组 |
| 分组 | `DEL_FOLLOW_GROUP` | 删除关注分组 |
| 分组 | `ADD_USER` | 向分组添加用户 |
| PGC | `PGC_MEDIA_INFO` | 拉取媒体维度的番剧信息 |
| PGC | `PGC_INFO` | 拉取 season 维度番剧信息 |
| PGC | `FOLLOW_PGC` | 订阅番剧 |
| PGC | `UNFOLLOW_PGC` | 取消订阅番剧 |
| 短链 | `SHORT_LINK` | 短链跳转点击入口 |
| 外部资源 | `TWEMOJI` | Twemoji CDN 基础地址，供 emoji 资源渲染使用 |

维护要求：

- 新增或删除 `Api.kt` 常量时，必须同步更新此表。
- `TWEMOJI` 虽然不是 B 站接口，但它和其他 API 常量在同一个文件里，维护时不要遗漏。

## 动态接口约束

`getNewDynamic()` 和 `getDynamicDetail()` 会传入：

- `timezone_offset=-480`
- `features=itemOpusStyle`

**为什么**：代码注释明确说明这些参数用于固定接口时区和统一卡片结构，减少不同运行环境或不同动态样式导致的字段差异。

**维护要求**：新增动态接口调用时应保持同一参数口径，除非有明确测试证明新接口不需要。

动态列表接口先以 `JsonElement` 接收响应，再由 `decodeDynamicListSkippingInvalidItems()` 分两层处理：分页外层与元数据仍严格解码；`items` 逐条解码，单条失败时记录 source、下标、动态 ID、类型、作者和附加卡片类型摘要，然后跳过该 item。不得把这条容错扩大为吞掉整个响应外层错误，否则会把接口结构变化伪装成空列表。

## 直播接口约束

直播轮询使用两类路径：

- `getLive()` 拉取关注直播列表。
- `getLiveStatus(uids)` 批量查询订阅用户直播状态。

`getLiveStatus()` 已兼容接口无数据时返回空数组的情况：空 `JsonArray` 会转为空映射。

**维护要求**：调用方应把 `null` 视为请求或解析失败，把空映射视为接口成功但无数据。

## 链接解析行为

链接解析集中在 `ResolveLinkService`。

当前可识别类型：

- 视频：BV/av 或 `www.bilibili.com/video/...`
- 专栏：`cv`、桌面 read、移动 read
- 动态：`t.bilibili.com`、`m.bilibili.com`、`opus`
- 直播：`live.bilibili.com`
- 用户：`space.bilibili.com`
- PGC：`ss`、`ep`、`md`
- 短链接：`b23.tv`、`bili2233.cn`

处理顺序：

1. 短链接优先匹配并重定向。
2. 其他类型按正文出现位置排序。
3. 同一 `stableName:id` 去重。
4. opus 链接会标记为 `OpusWithCv` 兼容路径。
5. 绘图入口统一走 `ResolvedLinkInfo.drawGeneral()`。

## 链接解析限流

`LinkResolvePolicyService` 当前策略：

- 单消息最多批准 3 个链接。
- 同一用户 60 秒内最多批准 3 次解析。
- 同一群内同一链接 60 秒冷却。
- 过期状态会在每次策略应用前清理。

**维护要求**：消息入口不得绕过该服务直接绘图或发送链接解析结果。

## API 错误处理规则

`BiliClient` 的错误日志会把异常归类为：

- 请求超时。
- 域名解析失败。
- 连接失败。
- 网络异常。
- 未分类异常。

可重试错误为 `IOException` 和 `HttpRequestTimeoutException`。超时不会轮换 retry slot，连接失败类错误才轮换，以避免瞬时拥塞时创建额外 OkHttp 资源。

## Cookie 与登录约束

请求 Cookie 来自 `BiliBiliBot.cookie` 和 `BiliBiliBot.uid`。涉及登录、Cookie 解析或账号状态的变更必须检查：

- `LoginService`
- `StartupDataInitService`
- `BiliCookie`
- `LoginService.commitLoginConfigForGeneration()`
- `BiliConfigManager.persistConfigSnapshot()` / `installConfigRuntimeSnapshot()`

**原因**：Cookie 与 UID 影响所有后续 B 站 API 调用，错误保存会导致轮询、关注和链接解析同时失败。

二维码登录使用单一 active generation；进入提交阶段后新的登录流程不得替换它，迟到代际也不得覆盖后来者。Cookie 必须先写入候选 `BiliConfig` 快照，持久化成功后一次安装运行态；失败时旧运行配置保持不变。Cookie 解析与冷/热重载采用完整替换，输入缺少 `SESSDATA` 或 `bili_jct` 时对应旧字段会被清空，不能依赖部分字符串保留历史凭据。

## 已知 quirks

- 动态新类型必须降级为 `DYNAMIC_TYPE_UNKNOWN`，不能让 `Enum.valueOf` 失败中断解析。
- 动态列表中的单条坏 item 会被跳过，但分页外层损坏仍应让当前请求失败；回归入口是 `DynamicListSafeDecodeTest`。
- 动态列表中直播相关类型会在动态轮询里过滤，直播推送由直播任务处理。
- PGC 动态通过 `bangumi` 订阅路由，而普通动态通过 `dynamic` 订阅路由。
- 专栏链接优先尝试解析到动态/opus 展示，失败后才走专栏详情卡片。
- B 站字段大量可空，新增模型字段应默认可空或提供安全默认值。
