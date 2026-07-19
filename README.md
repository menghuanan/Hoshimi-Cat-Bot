# Hoshimi-Cat-Bot v1.8

[![Docker Hub](https://img.shields.io/docker/v/menghuanan/hoshimi-cat-bot?label=Docker%20Hub&logo=docker)](https://hub.docker.com/r/menghuanan/hoshimi-cat-bot)
[![Docker Pulls](https://img.shields.io/docker/pulls/menghuanan/hoshimi-cat-bot)](https://hub.docker.com/r/menghuanan/hoshimi-cat-bot)
[![License](https://img.shields.io/badge/license-AGPL--3.0-blue.svg)](LICENSE)

由 [bilibili-dynamic-mirai-plugin](https://github.com/Colter23/bilibili-dynamic-mirai-plugin) 改造而来。  
代码部分由 [claude](https://github.com/claude) 主刀构建改造后的主体框架， GPT-5系列模型（GPT-5.2-Codex、GPT-5.3-Codex、GPT-5.4、GPT-5.5） 协助完善功能细则与日常修复bug。  
这是一个独立运行的 QQ 动态机器人，当前推荐通过 OneBot11 连接 NapCat / llbot / 通用 OneBot11 实现，同时内置本机 WebUI 管理界面。

## 文档目录

- [预览效果](#预览效果)
- [项目结构](#项目结构)
- [当前支持的协议](#当前支持的协议)
- [快速开始](#快速开始)
- [主要功能](#主要功能)
- [配置说明](#配置说明)
- [WebUI 配置](#webui-配置)
- [Docker 部署](#docker-部署)
- [开发说明](#开发说明)
- [与原项目的区别](#与原项目的区别)
- [故障排查](#故障排查)
- [常见问题](#常见问题)
- [免责声明](#免责声明)
- [许可证](#许可证)
- [联系方式](#联系方式)

## 预览效果

<img src="docs/dynamic.png" width="400" alt="预览图片1">

<details>
<summary>点击查看更多</summary>
<img src="docs/live.png" width="400" alt="预览图片2">

<img src="docs/Video.png" width="400" alt="预览图片3">

<img src="docs/bangumi.png" width="400" alt="预览图片4">
</details>

## 项目结构

```
hoshimi-cat-bot/
├── src/main/kotlin/top/bilibili/     # Kotlin 源代码
│   ├── api/                          # B站 API 接口
│   ├── client/                       # HTTP 客户端
│   ├── config/                       # Bot 配置与平台配置
│   ├── connector/                    # 平台连接器与 OneBot11 vendor 实现
│   ├── core/                         # 核心启动与资源生命周期
│   ├── data/                         # 数据模型
│   ├── draw/                         # 图片渲染
│   ├── service/                      # 业务服务与命令处理
│   ├── skia/                         # Skia 资源管理
│   ├── tasker/                       # 定时任务与守护任务
│   ├── webui/                        # WebUI 认证、路由、服务与运行时管理
│   ├── utils/                        # 工具类
│   ├── BiliConfig.kt                 # 主配置模型
│   ├── BiliData.kt                   # 运行数据模型
│   ├── SkikoInitializer.kt           # Skiko 初始化
│   └── Main.kt                       # 程序入口
├── src/main/resources/               # 资源文件
│   ├── font/                         # 字体文件
│   ├── icon/                         # 图标文件
│   ├── image/                        # 帮助图、内置图片
│   ├── webui/react/                  # WebUI 打包后的静态资源
│   └── logback.xml                   # 日志配置
├── webui-frontend/                   # WebUI 前端工程（React + Vite）
├── docs/                             # 补充文档
├── gradle/                           # Gradle Wrapper
├── build.gradle.kts                  # Gradle 构建脚本
├── settings.gradle.kts               # Gradle 设置
├── gradle.properties                 # Gradle 属性
├── gradlew                           # Gradle 脚本（Linux/Mac）
├── gradlew.bat                       # Gradle 脚本（Windows）
├── Dockerfile                        # Docker 镜像构建文件
├── docker-compose.yml                # Docker Compose 配置
├── docker-entrypoint.sh              # Docker 启动脚本
├── .env.example                      # 环境变量示例
├── .gitignore                        # Git 忽略文件
├── README.md                         # 项目说明
└── LICENSE                           # 许可证

```

## 当前支持的协议

当前 README 对外说明 OneBot11 接入路径。`platform.type` 固定为 `onebot11`，实际实现由 `platform.adapter` 选择：

```yaml
platform:
  type: onebot11
  adapter: napcat  # 可选: napcat / llbot / onebot11
```

| 平台类型 | `adapter` | <nobr>当前定位</nobr> | 适用场景 | 能力边界 | 配置要求 |
| --- | --- | --- | --- | --- | --- |
| `onebot11` | `napcat` | <nobr>默认推荐</nobr> | NapCat WebSocket 接入 | 文本、图片、回复、链接解析；支持群可达性与 `@全体` 运行时探测 | 配置 `platform.onebot11`，图片建议使用 `send_mode: base64` |
| `onebot11` | `llbot` | <nobr>已适配</nobr> | LuckyLilliaBot 接入 | 文本、图片、回复、链接解析；支持群可达性与 `@全体` 运行时探测 | 配置 `platform.onebot11`，图片建议使用 `send_mode: base64` |
| `onebot11` | `onebot11` | <nobr>通用兼容</nobr> | 标准 OneBot11 或兼容实现 | 文本、远程图片、回复、链接解析；`@全体` 默认不声明，本地/二进制图片按能力 guard 降级 | 配置 `platform.onebot11`，未覆盖能力会显式降级 |

### 选型建议

- 优先选择 `onebot11 + napcat`：这是当前默认推荐方案，现有能力验证最完整。
- 如果你的上游就是 LuckyLilliaBot，选择 `onebot11 + llbot` 更合适。
- 如果你接的是其他 OneBot11 实现，先用 `onebot11 + onebot11`，再按实际能力决定是否需要 vendor 适配。

### 配置约束

- `adapter` 建议显式填写，取值仅支持 `napcat`、`llbot`、`onebot11`；未知值会按当前实现归一到通用 `onebot11`。
- 三种 OneBot11 适配器共用 `platform.onebot11` 配置块，差异主要体现在运行时能力声明与探测行为。



## 快速开始

### 1. 获取平台发行包

> ⚠️ **部署前请确认：**
> 
> 请务必使用最新稳定版本的源码或可执行文件，以避免遇到已知问题。

#### 方法 一：从 [Releases](https://github.com/menghuanan/hoshimi-cat-bot/releases) 下载
请根据你的系统下载对应的平台发行包：
1. 在项目主页点击Releases标签。
2. Windows x64 下载 `hoshimi-cat-bot-windows-x64-v<版本>.zip`。
3. Linux x64 下载 `hoshimi-cat-bot-linux-x64-v<版本>.tar.gz`。
4. 解压后直接通过包内启动脚本运行，发行包已内置精简 runtime，无需额外安装 JDK。

#### 方法 二：本地自行编译
> **在本地编译之前需要先安装并配置好 JDK 17 及以上版本。**

> **平台支持说明：** 当前版本仅支持 Windows x64 与 Linux x64，暂未适配 macOS。
```powershell
# Windows
.\gradlew.bat windowsReleaseDistZip
```
```bash
# Linux
chmod +x gradlew
./gradlew linuxReleaseDistTar
```
```powershell
# Windows 交叉编译 Linux 发行包（需先下载并解压 Linux x64 JDK 17）
.\gradlew.bat linuxReleaseDistTar -PlinuxJdkHome=C:\path\to\jdk-17-linux-x64
```
发行包位于 `build/distributions/`：
- Windows：`hoshimi-cat-bot-windows-x64-v<版本>.zip`
- Linux：`hoshimi-cat-bot-linux-x64-v<版本>.tar.gz`

Windows 交叉编译 Linux 包时，`linuxJdkHome` 必须指向已解压的 Linux x64 JDK 17 根目录（目录下应有 `release` 和 `jmods`）；也可以用 `LINUX_JDK_HOME` 环境变量替代 Gradle 参数。

### 2. 运行 Bot

#### 方式一：使用发行包启动脚本

发行包启动脚本会优先使用包内内置 runtime，并补齐 JVM、编码、时区与软件渲染参数。

Windows 解压并启动：
```powershell
Expand-Archive -Force hoshimi-cat-bot-windows-x64-v<版本>.zip .
cd hoshimi-cat-bot-windows-x64-v<版本>
.\bin\start.bat
```

Linux 解压并启动：
```bash
tar -xzf hoshimi-cat-bot-linux-x64-v<版本>.tar.gz
cd hoshimi-cat-bot-linux-x64-v<版本>
chmod +x bin/start.sh
./bin/start.sh
```

Linux 启动脚本会优先复用已经包含 `libjemalloc.so.2` 的 `LD_PRELOAD`；如果没有，则从常见系统路径和 `ldconfig -p` 中查找 jemalloc。若仍未找到且当前是交互式终端，脚本会询问是否通过系统官方包管理器安装；非交互场景会直接提示手动安装并退出。默认 `MALLOC_CONF` 与 Docker 镜像保持一致，如需覆盖请在启动前显式设置环境变量。发行包本身已内置精简 runtime，不需要再单独下载 JDK。

#### 方式二：使用 Docker Hub 镜像（推荐）

```bash
# 拉取镜像
docker pull menghuanan/hoshimi-cat-bot:latest

# 启动容器
docker run -d --name hoshimi-cat-bot \
  --restart unless-stopped \
  --network bridge \
  -v ./config:/app/config \
  -v ./data:/app/data \
  -v ./temp:/app/temp \
  -v ./logs:/app/logs \
  menghuanan/hoshimi-cat-bot:latest
```

详细的 Docker 部署说明请查看 [Docker 部署](#docker-部署) 章节。

### 3. 配置文件

#### 首次运行时，程序会自动创建配置文件目录结构：

```
config/
├── bot.yml              # Bot 基础配置（平台与 WebUI 相关配置）
├── BiliData.yml         # 订阅数据和推送配置
├── BiliConfig.yml       # 配置文件
└── webui-credentials.json # WebUI 本地认证凭据（首次使用自动创建）

data/
├── font/                # 字体文件目录
├── cache/               # 绘图/用户/表情等缓存目录
├── image_cache/         # 图片缓存目录
├── exception/           # JSON 解析失败样本目录
└── cookies.json         # 登录后保存的 Cookie（按需生成）

temp/                    # 临时文件目录（二维码、帮助图、缓存等）

logs/
├── bilibili-bot.log     # 主日志文件
├── error.log            # 错误日志文件
└── daemon/              # 守护进程监控日志目录
    └── Daemon_YYYY-MM-DD.log  # 每日监控日志
```
#### 注意！以下是必须修改的配置项：
- 首次后需要在运行目录配置 `/config/bot.yml` 与 `/config/BiliConfig.yml` 文件后再重新启动bot。
- `/config/bot.yml` 通过平台化结构配置连接器；`type: onebot11` 下可选择 `napcat`、`llbot` 或通用 `onebot11`。
```bash
docker-compose down
```

> 先停止正在运行的容器，再编辑挂载到宿主机的配置文件，避免运行中的旧进程在退出时把配置写回旧值。

```yaml
platform:
  type: onebot11
  adapter: napcat  # 可选: napcat / llbot / onebot11
  onebot11:
    host: "NapCat / llbot / OneBot11 WebSocket 服务器地址"
    port: 3001
    token: ""
    use_tls: false
    send_mode: "base64"  # 图片发送模式：file 或 base64
    heartbeat_interval: 30000
    reconnect_interval: 5000
    message_format: "array"
    max_reconnect_attempts: -1
    connect_timeout: 10000
```
#### 如果不清楚两种图片发送模式的区别，建议直接使用 `base64`，兼容性更好，也能避免路径或权限问题。

- `/config/BiliConfig.yml` 配置管理员信息
```yaml
admin: 123456789
# 可选：显式使用平台联系人格式；填写后优先于 admin
admin_contact: "onebot11:private:123456789"
```
### 4. onebot平台 配置

新建 WebSocket 服务器，按以下参数填写：

- **名称**：`自定义名称`  
  （随便填写，仅用于区分）

- **主机**：`127.0.0.1`  
  （默认即可。如 onebot平台 与 bot 不在同一台机器，请填写对应服务器 IP）

- **端口**：`3001`  
  （默认 3001，建议修改为非常用端口以提升安全性）

- **消息格式**：`Array`  
  （保持默认）

- **令牌**：留空  
  （默认留空；如填写，需在 bot.yml 中同步配置）

- **心跳间隔**：`30000`  
  （保持默认）

以上配置如果出现无法连接等异常情况，请优先使用onebot平台的默认配置，如果还是无法接通请提交Issues。

## 主要功能

### 1. 链接解析
- 在群聊中按 `At` / `Always` / `Never` 策略响应 B 站链接
- 支持视频 BV/av、专栏 cv、动态/opus、直播间、用户空间、番剧 ss/md/ep 与 `b23.tv` / `bili2233.cn` 短链
- 支持从 OneBot11 文本、JSON 小程序/卡片片段中提取 B 站跳转链接
- 可生成图文卡片；关闭链接解析绘图后会回退为标准链接文本
- 内置去重与限流：同一消息最多处理 3 个链接，同一用户每分钟最多 3 次解析

### 2. 动态订阅
- 订阅 B站用户的动态
- 自动检测新动态并推送到群聊/私聊
- 支持按联系人 / 分组配置 UID 多模板策略与随机模板切换
- 支持直播开播/关播通知
- 支持番剧订阅、按动态类型或正则过滤、主题色绑定和 `@全体` 策略

### 3. 管理命令（按权限划分）

`BiliConfig.yml` 中的 `admin` / `admin_contact` 为超级管理员。群普通管理员需要由超级管理员使用 `/bili admin add <联系人>` 添加，仅能在对应群使用部分 `/bili` 命令。

#### 基础命令
- `/login` 或 `登录` - B站扫码登录（仅超管，群聊/私聊均可）
- `/check` - 手动触发动态检查（仅超管，仅群聊）
- `/bili help` - 显示帮助（超管、群普通管理员）

#### 快捷命令（仅超级管理员）
- `/add <UID>` - 快速订阅当前群聊或当前私聊
- `/del <UID>` - 快速取消当前群聊或当前私聊中的订阅
- `/list` - 查看当前群聊或当前私聊的订阅列表
- `/black <联系人>` - 快速将用户加入链接解析黑名单（仅群聊）
- `/unblock <联系人>` - 快速将用户移出链接解析黑名单（仅群聊）
- `/black list` - 查看链接解析黑名单（仅群聊）

#### 高级命令（/bili）
[查看 `/bili` 帮助大图](docs/help.png)

  <img src="docs/help.png" width="420" alt="高级命令预览">

以下文字清单以当前代码为准，帮助图片可能未包含最新命令。

<details>
<summary>查看完整 /bili 命令清单</summary>

    顶级命令别名:
    /bili remove = /bili rm
    /bili list = /bili ls
    /bili template = /bili tpl
    /bili atall = /bili aa
    /bili config = /bili cfg
    /bili blacklist = /bili bl

    订阅管理（超管 / 群普通管理员）:
    /bili add <UID|ss|md|ep> [群聊联系人] - 添加订阅；[群聊联系人] 仅超管可用
    /bili remove|rm <UID|ss|md|ep> [群聊联系人] - 移除订阅；[群聊联系人] 仅超管可用
    /bili list|ls - 查看当前会话的订阅
    /bili list|ls <UID|ss|md|ep> - 查看某个订阅推送到哪些群（仅超管）

    分组管理（仅超管）:
    /bili groups - 查看所有分组
    /bili group create <分组名> - 创建分组
    /bili group delete|del <分组名> - 删除分组
    /bili group add <分组名> <群聊联系人> - 将群加入分组
    /bili group remove|rm <分组名> <群聊联系人> - 从分组移除群
    /bili group list|ls [分组名] - 查看全部分组或单个分组详情
    /bili group subscribe|sub <分组名> <UID|ss|md|ep> - 为分组批量添加订阅
    /bili group unsubscribe|unsub <分组名> <UID|ss|md|ep> - 为分组批量取消订阅

    过滤器管理（超管 / 群普通管理员）:
    /bili filter add <UID> type <black|white> <动态|转发动态|视频|音乐|专栏|直播>
    /bili filter add <UID> regex <black|white> <正则表达式>
    /bili filter list|ls <UID> - 查看过滤器
    /bili filter del|delete|rm <UID> <索引> - 删除过滤器（如 t0, r1）
    模式还支持: blacklist / whitelist / 黑名单 / 白名单

    模板管理（超管 / 群普通管理员）:
    /bili template|tpl add <d|l|le> <模板名> <uid> [group <分组名>] - 追加模板到当前联系人或指定分组的 UID 策略
    /bili template|tpl del|delete|rm <d|l|le> <模板名> <uid> [group <分组名>] - 从当前联系人或指定分组的 UID 策略中删除模板
    /bili template|tpl list|ls <d|l|le> [uid] [group <分组名>] - 查看当前作用域的模板策略摘要
    /bili template|tpl on <d|l|le> <uid> [group <分组名>] - 开启随机模板
    /bili template|tpl off <d|l|le> <uid> [group <分组名>] - 关闭随机模板
    /bili template|tpl preview|pv <d|l|le> <模板名> - 发送模板预览
    /bili template|tpl explain|exp <d|l|le> - 查看模板占位符说明
    类型说明: d=动态, l=开播, le=下播
    说明: 模板策略统一保存在 `contact:<subject>` / `groupRef:<groupName>` 作用域下；开启随机至少需要 2 个有效模板，随机结果会按作用域隔离，并在同一分组单次推送内保持一致

    At全体管理（超管 / 群普通管理员，功能仅群聊生效）:
    /bili atall|aa add <类型> <uid> - 添加 @全体 策略
    /bili atall|aa <类型> <uid> - 兼容旧写法，等价于 add
    /bili atall|aa del|remove|rm <类型> <uid> - 删除 @全体 策略
    /bili atall|aa list|ls [uid] - 查看 @全体 策略
    类型支持: 全部/all/a, 全部动态/dynamic/d, 直播/live/l, 视频/video/v, 音乐/music/m, 专栏/article

    配置与主题色（超管 / 群普通管理员，主题色仅超管）:
    /bili config|cfg [uid] - 查看当前会话配置概览，可选查看指定 UID
    /bili color <uid|用户名> <HEX颜色> - 设置当前会话内该订阅的主题色（仅超管）
    /bili config|cfg color <uid|用户名> <HEX颜色> - 通过 config 入口设置主题色（仅超管）

    群普通管理员管理（仅超管）:
    /bili admin add <联系人> - 添加本群普通管理员（仅群聊）
    /bili admin remove|rm <联系人> - 移除本群普通管理员（仅群聊）
    /bili admin list|ls - 查看本群普通管理员（仅群聊）
    /bili admin all - 查看全部群的普通管理员

    链接解析黑名单（仅超管）:
    /bili blacklist|bl add <联系人> - 添加到链接解析黑名单
    /bili blacklist|bl remove|rm|del <联系人> - 从黑名单移除
    /bili blacklist|bl list|ls - 查看黑名单列表

    其他:
    /bili help - 显示此帮助
</details>

## 配置说明
<details>
<summary>点击展开配置说明</summary>

### bot.yml 示例

```yaml
platform:
  type: onebot11
  adapter: napcat            # 可选: napcat / llbot / onebot11
  onebot11:
    host: "127.0.0.1"        # NapCat / llbot / OneBot11 WebSocket 主机地址
    port: 3001               # NapCat / llbot / OneBot11 WebSocket 端口
    token: ""                # WebSocket 访问令牌（如有）
    use_tls: false           # 是否使用 TLS 加密
    send_mode: "base64"      # 图片发送模式：file 或 base64
    heartbeat_interval: 30000
    reconnect_interval: 5000
    message_format: "array"
    max_reconnect_attempts: -1
    connect_timeout: 10000
webui:
  enabled: false
  host: "127.0.0.1"
  port: 18080
  credential_file: "webui-credentials.json"
  token_ttl_seconds: 3600
  static_dir: ""
targets: []                  # 预留字段，当前版本未启用
admins: []                   # 群普通管理员配置
first_run_flag: 0            # 首次运行标记，程序自动维护
```

### WebUI 配置

WebUI 默认只监听本机 `127.0.0.1:18080`。如果要启用界面，请打开 `config/bot.yml`，把 `webui.enabled` 改成 `true`，并优先保留默认的主机和端口。

```yaml
webui:
  enabled: true
  host: "127.0.0.1"
  port: 18080
  credential_file: "webui-credentials.json"
  token_ttl_seconds: 3600
  static_dir: ""
```

保存后重启程序，然后在本机浏览器访问 `http://127.0.0.1:18080/`。首次启用时程序会在日志中输出一次初始密码，并要求登录后修改密码；凭据默认保存到 `config/webui-credentials.json`。

当前 WebUI 提供：

- 首页运行态概览：生命周期、账号、平台连接、推送统计、宿主资源与最近推送记录。
- 系统配置：读取并批量保存 `BiliConfig.yml`、`BiliData.yml`、`bot.yml`，保存写操作需要当前密码确认，并通过热重载 job 应用。
- 订阅管理：编辑动态、分组、番剧卡片，支持推送群、过滤器、模板、随机模板、`@全体` 和主题色子项。
- 日志：查看、导出和清空固定白名单日志源；清空日志需要当前密码确认。
- 账号：修改 WebUI 密码和退出登录。

如果必须把 WebUI 暴露到非本机地址，优先放在反向代理、内网穿透或 VPN 后面；直接把 `host` 改为 `0.0.0.0` 属于高风险配置，WebUI 保存时会要求额外确认。

### BiliData.yml 示例

```yaml
dataVersion: 4

# 动态订阅数据
dynamic:
  # UID: 订阅信息
  123456:
    name: "用户名"
    contacts:
      - "onebot11:group:987654321"
      - "onebot11:private:123456789"
    sourceRefs:
      - "direct:onebot11:group:987654321"
      - "groupRef:ops"
    banList: {}

# 模板策略（v4 起只保留 policy-only 结构，不再持久化旧 dynamicPushTemplate* 字段）
dynamicTemplatePolicyByScope:
  "contact:onebot11:group:987654321":
    123456:
      templates:
        - "OneMsg"
        - "TwoMsg"
      randomEnabled: true
  "groupRef:ops":
    123456:
      templates:
        - "DrawOnly"
      randomEnabled: false
```

- `dataVersion` 由程序自动维护。旧版本 `BiliData.yml` 在启动时会按版本迁移到当前结构，并在写回后移除旧模板绑定字段。
- `*TemplatePolicyByScope` 是当前唯一的模板持久化来源；直接联系人作用域使用 `contact:<subject>`，分组作用域使用 `groupRef:<groupName>`。

### BiliConfig.yml 示例

```yaml
admin: 0                        # 管理员 QQ 号（旧字段，默认映射到 onebot11:private:<QQ号>）
admin_contact: ""               # 可选：平台联系人格式，如 onebot11:private:123456789
enableConfig:                   # 启用配置
  debugMode: false              # 启用调试模式
  drawEnable: true              # 启用绘制功能
  pushDrawEnable: true          # 启用推送绘图；关闭后推送会走文本回退
  notifyEnable: true            # 启用通知功能
  liveCloseNotifyEnable: true   # 启用直播关播通知
  lowSpeedEnable: true          # 启用低速模式
  translateEnable: false        # 启用翻译功能
  proxyEnable: false            # 启用代理功能
  cacheClearEnable: true        # 启用缓存清除功能
accountConfig:                  # 账号配置
  cookie: ""                    # B站账号 Cookie
  autoFollow: true              # 自动关注用户
  followGroup: "Bot关注"        # 自动关注分组
checkConfig:                    # 检查配置
  lowSpeedTime: "22-8"          # 低速模式时间范围（24小时格式）
  lowSpeedRange: "60-240"       # 低速模式范围（秒）
  normalRange: "30-120"         # 正常模式范围（秒）
  checkReportInterval: 10       # 检查报告间隔（分钟）
  timeout: 10                   # 检查超时（秒）
pushConfig:
  messageInterval: 100          # 消息间隔（毫秒）
  pushInterval: 500             # 推送间隔（毫秒）
  toShortLink: false            # 是否转换为短链接
imageConfig:
  quality: "1000w"              # 图片质量（1000w/750w）  
  theme: "v3"
  font: ""
  defaultColor: "#d3edfa"
  cardOrnament: "FanCard"
  timeDisplayMode: "ABSOLUTE"   # ABSOLUTE / RELATIVE
  colorGenerator:
    hueStep: 30
    lockSB: true
    saturation: 0.25
    brightness: 1.0
  badgeEnable:
    left: true
    right: false
templateConfig:
  defaultDynamicPush: "OneMsg"
  defaultLivePush: "OneMsg"
  defaultLiveClose: "SimpleMsg"
  dynamicPush:
    "DrawOnly": "{draw}"
    "TextOnly": "{name}@{type}\n{link}\n{content}\n{images}"
    "OneMsg": "{draw}\n{name}@{type}\n{link}"
    "TwoMsg": "{draw}\r{name}@{uid}@{type}\n{time}\n{link}"
  livePush:
    "DrawOnly": "{draw}"
    "TextOnly": "{name}@直播\n{link}\n标题: {title}"
    "OneMsg": "{draw}\n{name}@直播\n{link}"
    "TwoMsg": "{draw}\r{name}@{uid}@直播\n{title}\n{time}\n{link}"
  liveClose:
    "SimpleMsg": "{name} 直播结束啦!\n直播时长: {duration}"
    "ComplexMsg": "{name} 直播结束啦!\n标题: {title}\n直播时长: {duration}"
  footer:
    dynamicFooter: ""
    liveFooter: ""
    footerAlign: "LEFT"
cacheConfig:
  downloadOriginal: true
  expires:
    "DRAW": 7
    "IMAGES": 7
    "EMOJI": 7
    "USER": 7
    "OTHER": 7
proxyConfig:
  proxy: []
translateConfig:               # 翻译配置
  cutLine: "\n\n〓〓〓 翻译 〓〓〓\n"     # 翻译结果分隔线
  baidu:                       # 百度翻译配置
    APP_ID: ""                 # 百度翻译 APP_ID
    SECURITY_KEY: ""           # 百度翻译 SECURITY_KEY
linkResolveConfig:             # 链接解析配置
  triggerMode: "At"            # 触发模式：At/Always/Never
  drawEnable: true             # 链接解析是否生成图片卡片
  returnLink: false            # 是否返回链接

```
</details>

## Docker 部署

### 方式一：使用 Docker Hub 镜像（推荐）

无需编译，直接从 Docker Hub 拉取预构建镜像快速部署。

**Docker Hub 仓库：** https://hub.docker.com/r/menghuanan/hoshimi-cat-bot

#### 1. 拉取镜像

```bash
docker pull menghuanan/hoshimi-cat-bot:latest
```

#### 2. 创建配置目录

```bash
mkdir -p config data temp logs
```

#### 3. 使用 docker run 启动

```bash
docker run -d \
  --name hoshimi-cat-bot \
  --restart unless-stopped \
  --network bridge \
  -v ./config:/app/config \
  -v ./data:/app/data \
  -v ./temp:/app/temp \
  -v ./logs:/app/logs \
  menghuanan/hoshimi-cat-bot:latest
```

#### 4. 使用 docker-compose（推荐）

创建 `docker-compose.yml` 文件：

```yaml
services:
  hoshimi-cat-bot:
    image: menghuanan/hoshimi-cat-bot:latest
    pull_policy: always
    container_name: hoshimi-cat-bot
    restart: unless-stopped
    environment:
      - TZ=Asia/Shanghai
    volumes:
      - ./config:/app/config
      - ./data:/app/data
      - ./temp:/app/temp
      - ./logs:/app/logs
    network_mode: "bridge"  # 使用 bridge 网络模式
    deploy:
      resources:
        limits:
          cpus: '2'
          memory: 512m
        reservations:
          cpus: '0.5'
    mem_limit: 512m
    shm_size: 256m
    logging:
      driver: "json-file"
      options:
        max-size: "100m"
        max-file: "5"
```

启动容器：

```bash
docker-compose up -d
```

#### 4.1 Docker 默认 JVM 诊断参数说明

- Docker 镜像默认使用 `eclipse-temurin:17-jdk` 运行时，以保证容器内 `jcmd` 与 Native Memory Tracking 可用。
- `JAVA_TOOL_OPTIONS` 默认包含 `-XX:NativeMemoryTracking=summary`，用于长期保留低开销的 NMT 摘要，便于守护日志稳定输出 `Native Memory Summary`。
- `JAVA_TOOL_OPTIONS` 默认将 `io.netty.allocator.type` 设为 `unpooled`，适配低并发机器人场景并减少池化管理开销。
- `JAVA_TOOL_OPTIONS` 默认将 `java.awt.headless` 设为 `true`，在纯软件渲染模式下避免容器尝试连接 X11 display。
- `NativeMemoryTracking=detail` 仅建议在专项排障时临时启用；它会带来更高的运行时开销，问题定位完成后应恢复到默认的 `summary`。
- `docker-entrypoint.sh` 仍会补充 `-Dfile.encoding=UTF-8` 和 `-Duser.timezone=Asia/Shanghai`，不要在 `docker-compose.yml` 中用 `JAVA_OPTS` 覆盖整组默认参数。

#### 5. 配置 onebot11 连接

首次运行后，编辑 `config/bot.yml`：

```yaml
platform:
  type: onebot11
  adapter: napcat  # napcat / llbot / onebot11
  onebot11:
    host: "onebot11 WebSocket 主机地址"
    port: 3001
    token: ""
    use_tls: false
    send_mode: "base64"
    heartbeat_interval: 30000
    reconnect_interval: 5000
    message_format: "array"
    max_reconnect_attempts: -1
    connect_timeout: 10000
```

重启容器：

```bash
docker-compose down
docker-compose up -d
```

#### 6. 查看日志

```bash
docker-compose logs -f
# 或
docker logs -f hoshimi-cat-bot
```

### 方式二：从源码构建部署

如果需要自定义修改，可以从源码构建镜像。

1. **配置 onebot平台 连接**
   - 修改 `config/bot.yml` 中的 host 为 `host.docker.internal`（如果 onebot平台 在宿主机）
   - 或保持 `127.0.0.1`（如果 onebot平台 也在容器内）

2. **先构建可运行 JAR**
   当前 `Dockerfile` 会直接复制 `build/libs/hoshimi-cat-bot-*.jar`，因此需要先执行：
   ```bash
   # Windows
   .\gradlew.bat shadowJar

   # Linux
   ./gradlew shadowJar
   ```

3. **构建镜像**
   ```bash
   docker compose build
   ```

4. **启动容器**
   ```bash
   docker compose up -d
   ```

5. **查看日志**
   ```bash
   docker compose logs -f
   ```

### 容器配置

- **运行时基础镜像**: eclipse-temurin:17-jdk
- **源码构建环境**: JDK 17 及以上版本
- **内存分配器**: jemalloc（默认 `dirty_decay_ms=2000`、`muzzy_decay_ms=2000`，约 2 秒加速归还内存）
- **JVM 启动参数**: 默认 `-Xms64m -Xmx160m`，其余 GC/Netty/Skiko 优化参数由 `JAVA_TOOL_OPTIONS` 注入
- **Netty 分配器**: unpooled（低并发场景优先降低池化管理开销）
- **AWT 模式**: headless=true（纯软件渲染，不依赖容器内 display 服务）
- **网络模式**: bridge（默认）
- **健康检查**: 每60秒检查一次进程状态
- **日志限制**: 100MB × 5 文件（自动轮转）
- **重启策略**: unless-stopped

**网络说明：**
- 默认使用 `bridge` 网络模式，适合大多数场景
- 如果 NapCat 在宿主机运行，需要在 `config/bot.yml` 中配置 `host: "host.docker.internal"`
- 如果 NapCat 也在 Docker 中运行，建议使用自定义网络连接两个容器（参考 docker-compose.yml 注释）

## 开发说明

### 技术栈
- Kotlin 2.0.0
- Ktor 3.0.3（HTTP / WebSocket 客户端与 WebUI 服务端）
- React 19.2.6（WebUI 前端）
- TypeScript 6.0.2（WebUI 前端）
- Vite 8.0.12（WebUI 构建）
- Tailwind CSS 4.3.0（WebUI 样式）
- Vitest 3.2.4 + Playwright 1.56.0（WebUI 测试）
- Skiko 0.8.15（图片渲染）
- kotlinx.serialization 1.6.3（JSON 处理）
- kotlinx.coroutines 1.8.0（协程）
- KAML 0.61.0（YAML 配置解析）
- ZXing 3.5.0（二维码生成）
- Logback 1.4.14 + SLF4J 2.0.9（日志）
- OneBot v11 协议（NapCat / llbot / OneBot11）

### 项目特点
- 独立运行，不依赖 Mirai 框架
- 使用 onebot11 作为 QQ 机器人框架
- 基于 WebSocket 通信
- 使用 Skiko 进行高质量图片渲染
- 内置本机 WebUI 管理界面
- 支持 Docker 部署

## 与原项目的区别

本项目由原版 Mirai 插件演进而来，但当前实现已经不是 Mirai Console 插件：

1. **运行形态不同**
   - 原版通过 Mirai / MCL 安装为插件，并依赖 mirai-skia-plugin、chat-command 与 Mirai 权限系统。
   - 当前项目是独立 Kotlin/JVM 应用，主类为 `top.bilibili.MainKt`，可通过平台发行包、Docker 或源码构建运行。

2. **平台接入不同**
   - 原版直接运行在 Mirai 的 QQ 联系人、权限和消息模型上。
   - 当前项目使用平台中立的 Connector 层，对外推荐 OneBot11 接入 NapCat / llbot / 通用 OneBot11，并通过能力 guard 处理图片、回复、`@全体` 和链接解析能力差异。

3. **配置与数据路径不同**
   - 原版配置位于 Mirai 插件目录，包含 `BiliConfig.yml`、`ImageQuality.yml`、`ImageTheme.yml` 等插件配置。
   - 当前项目使用运行目录下的 `config/BiliConfig.yml`、`config/BiliData.yml`、`config/bot.yml`，并将订阅联系人统一为 `onebot11:group:<id>` / `onebot11:private:<id>` 这类 subject。

4. **保留并扩展的核心能力**
   - 保留动态/直播检测、番剧订阅、扫码登录、自动关注、过滤器、模板、主题色、缓存清理和 Skia/Skiko 绘图。
   - 扩展了联系人/分组作用域模板策略、随机模板、链接解析限流、运行态观测、资源生命周期管理和平台能力降级。

5. **新增 WebUI 与部署能力**
   - 当前项目内置 React + Ktor WebUI，可管理运行态、配置、订阅和日志，写操作使用 session、CSRF 与确认密码保护。
   - 当前项目提供 Dockerfile、docker-compose、Windows/Linux jlink 发行包和持久化运行方式，不再要求用户先部署 Mirai 环境。

## 故障排查

### 启用 DEBUG 日志

如果遇到问题需要提交 Bug 报告，可以启用 DEBUG 级别日志来获取更详细的信息：

1. **配置文件启用 Debug**

   编辑挂载目录中的 `config/BiliConfig.yml`：

   ```yaml
   enableConfig:
     debugMode: true
   ```

   裸机部署请先停止程序，编辑运行目录中的同名配置文件后，再通过 `bin/start.bat` 或 `bin/start.sh` 重新启动。

1. **Docker 部署启用 Debug**

   重启容器：

   ```bash
   docker-compose down
   # 或
   docker-compose up -d
   ```
2. **查看日志**

   - 控制台会显示 DEBUG 级别的详细日志
   - 日志文件位于 `logs/bilibili-bot.log`
   - 错误日志位于 `logs/error.log`

**注意**: DEBUG 日志会输出大量信息，仅在排查问题时启用，日常使用建议保持 INFO 级别。

## 常见问题

- **Q: 为什么 bot 发送图片失败？**
  - A: 如果使用 `file` 模式发送图片失败，请改为：`send_mode: "base64"`。
    `file` 模式依赖本地文件路径，在 Docker 或跨机器环境下容易因路径映射或权限问题导致失败。

- **Q: 为什么 bot 对命令没有反应？**
  - A: 请确保 `config/BiliConfig.yml` 中已正确配置 `admin`，并确认你使用的是管理员账号。

- **Q: 如果 NapCat 和 bot 部署在不同环境（不同服务器/不同容器）怎么办？**
  - A: 
    1. 先确保两者可以正常网络通信（检查端口、防火墙、IP 地址）。
    2. 强烈建议将 `config/bot.yml` 中的 `send_mode` 设置为：`send_mode: "base64"`。
       因为 `file` 模式无法跨机器访问本地文件。

- **Q: 为什么bot不解析链接？**
  - A: 需要修改`BiliConfig.yml`中`triggerMode`字段，修改成`Always`即可实现自动解析链接。

  **Q: 在使用QQ官方适配器时，为什么bot不解析小程序？**
  - A: 因为官方消息渠道没有小程序相关的能力。

- **Q: 没有更多问题了吗？**
  - A: 目前使用人数不多，暂时没有更多问题了。

## 免责声明

本项目仅提供技术框架，不提供任何数据服务。

所有数据来源、账号登录及相关行为均由使用者自行负责，
开发者不参与、不存储任何用户数据。

用户基于本项目进行的内容获取、处理及传播行为，
均由使用者自行承担责任，与开发者无关。

使用本项目时，请遵守相关平台的服务条款及法律法规。

## 许可证

本项目基于 AGPL-3.0 许可证开源。

## 联系方式

如果您有任何建议或功能请求和问题，欢迎在 Issue 中提出。

如果你希望贡献代码或参与项目的开发，可以加入我的 QQ 群：[点击链接加入群聊](https://qm.qq.com/q/VfN3EQggQI)



