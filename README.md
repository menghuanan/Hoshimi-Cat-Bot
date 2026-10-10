# Hoshimi-Cat-Bot

[![Docker Hub](https://img.shields.io/docker/v/menghuanan/hoshimi-cat-bot?label=Docker%20Hub&logo=docker)](https://hub.docker.com/r/menghuanan/hoshimi-cat-bot)
[![Docker Pulls](https://img.shields.io/docker/pulls/menghuanan/hoshimi-cat-bot)](https://hub.docker.com/r/menghuanan/hoshimi-cat-bot)
[![License](https://img.shields.io/badge/license-AGPL--3.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Windows%20x64%20%7C%20Linux%20x64-lightgrey)](#42-操作系统适配说明)

一个独立运行的 B 站动态订阅机器人：低延迟检测 UP 主动态、直播开播与番剧更新，用 Skia 渲染成图文卡片后推送到 QQ，并内置本机 WebUI 管理界面。

## 目录

1. [项目概览](#1-项目概览)
2. [功能亮点 / 截图](#2-功能亮点--截图)
3. [快速开始](#3-快速开始)
4. [安装说明](#4-安装说明)
5. [使用示例](#5-使用示例)
6. [配置选项](#6-配置选项)
7. [贡献指南](#7-贡献指南)
8. [许可证](#8-许可证)
9. [致谢 / 相关项目](#9-致谢--相关项目)

---

## 1. 项目概览

### 1.1 应用场景

| 场景 | 说明 |
| --- | --- |
| 群内 UP 主搬运 | 一个群订阅多个 B 站 UP 主，新动态、新视频自动推送到群 |
| 直播开播提醒 | 订阅的主播开播/下播时推送通知，可配合 `@全体` 策略 |
| 番剧更新跟进 | 按 `ss` / `md` / `ep` 订阅番剧，更新后推送 |
| 群内链接解析 | 群成员发送 B 站链接时自动生成预览卡片，支持 `At` / `Always` / `Never` 三种触发策略 |
| 多群统一运营 | 用「分组」把多个群组织在一起，一次操作批量增删订阅 |
| 私聊推送 | 订阅结果推送到私聊，用于个人追更 |
| 自建轻量部署 | 单机 Docker 或裸机发行包部署，无需额外搭建机器人框架 |

### 1.2 项目结构

```text
hoshimi-cat-bot/
├── src/main/kotlin/top/bilibili/     # Kotlin 主程序
│   ├── api/                          # B 站 API 接口
│   ├── client/                       # HTTP 客户端与重试策略
│   ├── config/                       # bot.yml 配置管理与平台配置模型
│   ├── connector/                    # 平台中立连接层与 OneBot11 vendor 实现
│   ├── core/                         # 启动装配与资源生命周期
│   ├── data/                         # 数据模型与序列化兼容
│   ├── delivery/                     # 推送投递
│   ├── draw/                         # 图片渲染
│   ├── service/                      # 业务服务与命令处理
│   ├── skia/                         # Skia/Skiko 资源管理
│   ├── tasker/                       # 轮询任务与守护任务
│   ├── utils/                        # 工具类
│   ├── webui/                        # WebUI 认证、路由、配置写入与服务端
│   ├── BiliConfig.kt                 # BiliConfig.yml 主配置模型
│   ├── BiliConfigManager.kt          # 配置与数据读写边界
│   ├── BiliData.kt / BiliDataWrapper.kt  # 运行数据模型与序列化包装
│   ├── Main.kt                       # 程序入口
│   └── SkikoInitializer.kt           # Skiko 初始化
├── src/main/resources/               # 资源文件
│   ├── font/                         # 字体
│   ├── icon/                         # 图标
│   ├── image/                        # 帮助图与内置图片
│   ├── webui/react/                  # WebUI 打包后的静态资源
│   └── logback.xml                   # 日志配置
├── webui-frontend/                   # WebUI 前端工程（React + Vite + TypeScript）
├── docs/                             # 图片资源与项目文档
├── gradle/                           # Gradle Wrapper
├── build.gradle.kts                  # Gradle 构建脚本
├── Dockerfile                        # Docker 镜像构建文件
├── docker-compose.yml                # Docker Compose 配置
├── docker-entrypoint.sh              # Docker 启动脚本
├── .env.example                      # 环境变量示例
├── README.md                         # 项目说明（本文件）
├── README-MIRAI-ORIGINAL.md          # 上游项目原始说明文档
└── LICENSE                           # AGPL-3.0 许可证全文
```

---

## 2. 功能亮点 / 截图

### 2.1 核心亮点

1. **动态、直播、番剧三种订阅对象统一管理**
   同一份运行数据同时支撑动态订阅、直播开播/下播通知与番剧订阅；订阅可以绑定到群聊、私聊，也可以通过「分组」批量下发。每个订阅支持独立的过滤器、模板策略、主题色与 `@全体` 策略。

2. **按联系人 / 分组作用域的多模板与随机模板**
   模板策略以 `contact:<联系人>` 和 `groupRef:<分组名>` 为作用域持久化。一个 UID 可以绑定多个模板并开启随机切换；开启随机后至少需要 2 个有效模板，分组内单次推送的所有联系人会使用同一份随机结果，避免同一次推送出现风格不一致。

3. **B 站链接智能解析**
   在群聊中按 `At` / `Always` / `Never` 策略响应链接，覆盖视频 `BV`/`av`、专栏 `cv`、动态与 `opus`、直播间、用户空间、番剧 `ss`/`md`/`ep`，以及 `b23.tv`、`bili2233.cn` 短链；同时支持从 OneBot11 的文本消息与 JSON 小程序/卡片片段中提取跳转链接。解析结果可生成图文卡片，也可以关闭绘图回退为纯文本。

4. **平台中立适配与能力降级**
   业务代码只面向 `PlatformAdapter`、`PlatformContact`、`OutgoingPart` 等平台中立模型。当前支持 OneBot11 通用实现、NapCat、llbot 三种适配器，并对图片发送、消息回复、`@全体`、群可达性等差异做运行时能力探测，不满足的能力会显式降级而不是抛异常。

5. **开箱即用的部署与运维能力**
   提供 Windows x64 / Linux x64 的 jlink 精简运行时发行包（免装 JDK）、Docker Hub 预构建镜像与 Docker Compose 模板；内置 WebUI 提供运行态概览、配置批量保存、订阅编辑、日志查看与导出，写操作受会话、CSRF 与当前密码确认保护。

### 2.2 运行截图

| 功能 | 截图 | 说明 |
| --- | --- | --- |
| 动态推送 | <img src="docs/dynamic.png" width="320" alt="动态推送效果"> | 头像、昵称、动态类型、正文、配图与跳转链接 |
| 视频链接解析 | <img src="docs/Video.png" width="320" alt="视频链接解析效果"> | 封面、标题、UP 主、播放数据与简介 |
| 直播开播推送 | <img src="docs/live.png" width="320" alt="直播开播推送效果"> | 直播间封面、标题、分区与开播状态 |
| 番剧订阅推送 | <img src="docs/bangumi.png" width="320" alt="番剧订阅推送效果"> | 剧集封面、标题、更新集数与上线时间 |
| 帮助图 | <img src="docs/help.png" width="320" alt="命令帮助图"> | 汇总当前可用的 `/bili` 子命令，由 `/bili help` 触发 |

> 提示：WebUI 管理界面当前没有随仓库提供截图资源。界面能力与页面清单见 [5.8 通过 WebUI 管理](#58-示例-8通过-webui-管理运行态与配置)。

---

## 3. 快速开始

本节给出一条「五分钟内跑起来」的最短路径。完整的环境要求与部署方式见 [4. 安装说明](#4-安装说明)。

### 3.1 准备工作

- 已安装 Docker 与 Docker Compose 的 Windows x64 或 Linux x64 主机
- 一个已经能正常收发消息的 OneBot11 实现（推荐 NapCat）
- 一个用于测试的 QQ 群或私聊会话

### 3.2 四步启动

**第 1 步：创建数据目录并启动容器**

```bash
# 在任意空目录下执行，四个目录分别持久化配置、数据、临时文件与日志
mkdir -p config data temp logs

# 使用仓库根目录自带的 docker-compose.yml
docker compose up -d
```

首次启动会自动生成 `config/bot.yml`、`config/BiliConfig.yml`、`config/BiliData.yml` 等配置文件，并创建 `data/`、`temp/`、`logs/` 下的子目录。

**第 2 步：停止容器后再改配置**

```bash
docker compose down
```

必须先停容器再改配置，否则运行中的旧进程会在退出时把内存里的旧配置写回磁盘，覆盖你的修改。

**第 3 步：填写平台连接与管理员**

编辑 `config/bot.yml`，把 `host` 改成你的 OneBot11 WebSocket 服务地址：

```yaml
platform:
  type: onebot11
  adapter: napcat          # 可选: napcat / llbot / onebot11
  onebot11:
    host: "127.0.0.1"      # OneBot11 WebSocket 服务器地址
    port: 3001             # 与 OneBot11 侧配置的端口保持一致
    token: ""              # 若 OneBot11 侧设置了令牌，此处必须一致
    send_mode: "base64"    # 建议固定为 base64，跨机器与容器场景兼容性最好
```

编辑 `config/BiliConfig.yml`，填写超级管理员：

```yaml
admin: 123456789           # 你的 QQ 号，用于接收命令执行权限
```

**第 4 步：重新启动并确认**

```bash
docker compose up -d
docker compose logs -f
```

当日志中不再出现连接重试、并且能看到平台连接建立与轮询任务启动的记录时，说明启动完成。

### 3.3 核心功能验证

按顺序完成以下三项验证即可确认基础链路可用：

| 验证项 | 操作 | 预期结果 |
| --- | --- | --- |
| 命令链路 | 用超级管理员账号在群里发送 `/bili help` | 机器人回复帮助图 |
| 订阅链路 | 发送 `/add <UID>`（如 `/add 2`） | 提示订阅成功，并返回当前会话的订阅列表 |
| 检测链路 | 发送 `/check`（仅群聊可用） | 立刻触发一次动态检查，有新内容时推送卡片 |
| 解析链路 | 在群里 `@机器人` 并发送一条 B 站视频链接 | 回复该视频的预览卡片（默认触发模式为 `At`） |

如果以上任一项没有反应，先确认 `admin` 是否为当前使用的账号，再参考 [5.9 常见问题与排查](#59-示例-9常见问题与排查)。

---

## 4. 安装说明

### 4.1 最低环境要求

| 项目 | 最低要求 | 说明 |
| --- | --- | --- |
| 操作系统 | Windows x64 / Linux x64 | macOS 暂未适配，无对应的 Skiko 运行时依赖 |
| JDK（源码构建） | JDK 17（完整 JDK，需包含 `jmods`） | 交叉打包 Linux 发行包时需要额外的 Linux x64 JDK 17 |
| JDK（运行发行包） | 不需要 | 发行包内置 jlink 精简运行时 |
| Node.js（源码构建） | Node.js 22 | `processResources` 会触发 WebUI 前端构建，缺少 Node 会导致构建失败 |
| npm | 随 Node.js 22 提供 | 前端依赖安装与构建 |
| Gradle | 无需单独安装 | 使用仓库自带的 Gradle Wrapper（Gradle 8.5） |
| Docker | Docker Engine 20.10+ 与 Compose V2 | 用于容器部署 |
| 内存 | 容器场景 512MB 起 | 镜像默认 JVM 参数为 `-Xms64m -Xmx160m` |
| 磁盘 | 建议预留 2GB 以上 | 发行包内 runtime 与图片缓存会持续占用空间 |
| 系统依赖（Linux 裸机） | `libjemalloc.so.2` | 启动脚本会检查并按需提示安装 |

### 4.2 操作系统适配说明

| 操作系统 | 发行包 | Docker | 源码构建 | 备注 |
| --- | --- | --- | --- | --- |
| Windows x64 | `hoshimi-cat-bot-windows-x64-v<版本>.zip` | 支持 | 支持 | 启动入口为 `bin\start.bat` |
| Linux x64 | `hoshimi-cat-bot-linux-x64-v<版本>.tar.gz` | 支持 | 支持 | 启动入口为 `bin/start.sh`，需要 jemalloc |
| Windows 交叉打包 Linux | 支持 | 支持 | 支持 | 需要 `-PlinuxJdkHome` 指向已解压的 Linux x64 JDK 17 |
| macOS | 不支持 | 支持（容器内为 Linux） | 不支持 | 缺少 macOS 的 Skiko 运行时依赖 |

### 4.3 生产环境发行包部署

发行包内置了 jlink 生成的精简运行时，不需要在目标机器上另外安装 Java。

**第 1 步：下载发行包**

前往 [Releases](https://github.com/menghuanan/Hoshimi-Cat-Bot/releases) 下载与系统匹配的包：

- Windows x64：`hoshimi-cat-bot-windows-x64-v1.8.16.zip`
- Linux x64：`hoshimi-cat-bot-linux-x64-v1.8.16.tar.gz`

**第 2 步：解压并启动**

Windows（PowerShell）：

```powershell
Expand-Archive -Force hoshimi-cat-bot-windows-x64-v1.8.16.zip .
cd hoshimi-cat-bot-windows-x64-v1.8.16
.\bin\start.bat
```

Linux（bash）：

```bash
tar -xzf hoshimi-cat-bot-linux-x64-v1.8.16.tar.gz
cd hoshimi-cat-bot-linux-x64-v1.8.16
chmod +x bin/start.sh
./bin/start.sh
```

**第 3 步：处理 Linux 的 jemalloc 依赖**

Linux 启动脚本会按以下顺序处理内存分配器：

1. 若 `LD_PRELOAD` 中已包含 `libjemalloc.so.2`，直接复用。
2. 否则从 `/usr/lib/x86_64-linux-gnu/`、`/usr/lib/aarch64-linux-gnu/`、`/usr/lib64/`、`/usr/lib/` 以及 `ldconfig -p` 中查找。
3. 仍未找到时，交互式终端会询问是否通过系统包管理器（`apt-get` / `dnf` / `yum`）安装；非交互场景直接报错退出。

也可以手动先行安装：

```bash
# Debian / Ubuntu
sudo apt-get update && sudo apt-get install -y libjemalloc2

# RHEL / CentOS / Fedora
sudo dnf install -y jemalloc
```

**第 4 步：确认运行参数**

发行包启动脚本已经补齐 JVM 内存、编码、时区与软件渲染参数，正常情况下不需要额外调整。关键参数如下：

| 参数 | 值 | 作用 |
| --- | --- | --- |
| `-Xms64m -Xmx160m` | 固定 | 堆内存上下限 |
| `-XX:G1PeriodicGCInterval=60000` | 固定 | 周期性 GC，降低空闲期常驻内存 |
| `-Dfile.encoding=UTF-8` | 固定 | 避免中文路径与消息乱码 |
| `-Duser.timezone=Asia/Shanghai` | 固定 | 保证推送时间与低速时段判断正确 |
| `-Dskiko.renderApi=SOFTWARE` | 固定 | 纯软件渲染，不依赖显卡与显示服务 |
| `MALLOC_CONF` | `background_thread:true,dirty_decay_ms:2000,muzzy_decay_ms:2000,narenas:1,tcache:false` | jemalloc 参数，约 2 秒加速归还内存 |

### 4.4 生产环境 Docker 部署

**使用 Docker Hub 镜像（推荐）**

```bash
# 拉取镜像
docker pull menghuanan/hoshimi-cat-bot:latest

# 创建持久化目录
mkdir -p config data temp logs

# 启动容器
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

**使用 docker-compose（推荐用于长期运行）**

仓库根目录的 `docker-compose.yml` 已经包含内存限制、日志轮转与停机宽限期配置，直接使用即可：

```bash
docker compose up -d
docker compose logs -f
```

镜像的关键约束如下：

| 项目 | 值 |
| --- | --- |
| 运行时基础镜像 | `eclipse-temurin:17-jdk` |
| 内存分配器 | jemalloc，`dirty_decay_ms=2000`、`muzzy_decay_ms=2000` |
| JVM 默认参数 | `-Xms64m -Xmx160m`，其余由 `JAVA_TOOL_OPTIONS` 注入 |
| Native Memory Tracking | 默认 `summary`；仅专项排障时临时改成 `detail` |
| Netty 分配器 | `unpooled`，适配低并发机器人场景 |
| AWT 模式 | `headless=true`，纯软件渲染 |
| 容器内存限制 | 512MB（`mem_limit` 与 `deploy.resources.limits.memory` 保持一致） |
| 共享内存 | `shm_size: 256m` |
| 停机宽限期 | `stop_grace_period: 110s`，需大于 entrypoint 100 秒等待与 JVM 90 秒停机预算 |
| 健康检查 | 每 60 秒执行一次 `pgrep` 进程检查 |
| 日志轮转 | `json-file`，单文件 100MB × 5 |
| 重启策略 | `unless-stopped` |

> **不要**在 `docker-compose.yml` 中设置 `JAVA_OPTS`。镜像通过 `JAVA_TOOL_OPTIONS` 注入了 NMT、Netty、headless 等一整套参数，覆盖后会丢失诊断与优化配置。需要调整内存时优先修改 JVM 堆参数本身。
>
> `NativeMemoryTracking=detail` 仅建议在专项排障时临时启用，它会带来更高的运行时开销，问题定位完成后应恢复到默认的 `summary`。`docker-entrypoint.sh` 还会额外补充 `-Dfile.encoding=UTF-8` 与 `-Duser.timezone=Asia/Shanghai`。

**容器内的平台地址**

| OneBot11 实现位置 | `config/bot.yml` 中的 `host` |
| --- | --- |
| 运行在宿主机 | `host.docker.internal` |
| 运行在同一个容器内 | `127.0.0.1` |
| 运行在另一个容器中 | 该容器的服务名或自定义网络内的地址 |

跨容器或跨机器场景请务必把 `send_mode` 设为 `base64`，`file` 模式依赖接收端能访问同一份本地文件路径。

### 4.5 生产环境源码构建

**第 1 步：准备环境**

安装 JDK 17（完整 JDK）与 Node.js 22，然后确认：

```bash
java -version   # 需要 17 或更高
node -v         # 需要 v22
npm -v
```

**第 2 步：构建可运行 JAR**

```bash
# Windows
.\gradlew.bat shadowJar

# Linux
chmod +x gradlew
./gradlew shadowJar
```

产物位于 `build/libs/hoshimi-cat-bot-<版本>.jar`。注意 `shadowJar` 会依次触发 WebUI 前端的 `npm install` 与 `npm run build`，并把产物输出到 `src/main/resources/webui/react`。

**第 3 步（可选）：构建平台发行包**

```bash
# Windows 发行包
.\gradlew.bat windowsReleaseDistZip

# Linux 发行包
./gradlew linuxReleaseDistTar

# Windows 交叉打包 Linux 发行包
.\gradlew.bat linuxReleaseDistTar -PlinuxJdkHome=C:\path\to\jdk-17-linux-x64
```

发行包输出到 `build/distributions/`：

- Windows：`hoshimi-cat-bot-windows-x64-v<版本>.zip`
- Linux：`hoshimi-cat-bot-linux-x64-v<版本>.tar.gz`

`linuxJdkHome` 必须指向已解压的 Linux x64 JDK 17 根目录（目录下应存在 `release` 与 `jmods`），也可以用 `LINUX_JDK_HOME` 环境变量替代该参数。

**第 4 步：构建 Docker 镜像**

`Dockerfile` 直接复制 `build/libs/hoshimi-cat-bot-*.jar`，所以必须先完成第 2 步：

```bash
docker compose build
docker compose up -d
```

### 4.6 开发环境搭建

**第 1 步：克隆并初始化**

```bash
git clone https://github.com/menghuanan/Hoshimi-Cat-Bot.git
cd Hoshimi-Cat-Bot
```

**第 2 步：后端编译与测试**

```bash
# 仅编译 Kotlin 与测试源码（会触发前端构建）
./gradlew compileKotlin compileTestKotlin

# 运行后端测试
./gradlew test
```

**第 3 步：前端依赖与本地调试**

```bash
cd webui-frontend

npm ci            # 按 package-lock.json 安装依赖
npm run lint      # ESLint 检查
npm test          # Vitest 单元测试
npm run dev       # 启动 Vite 开发服务器（127.0.0.1）
npm run test:e2e  # 构建后运行 Playwright 端到端测试
```

**第 4 步：启用本机 WebUI**

默认情况下 WebUI 是关闭的。编辑 `config/bot.yml`：

```yaml
webui:
  enabled: true
  host: "127.0.0.1"
  port: 18080
```

保存后重启程序，在浏览器访问 `http://127.0.0.1:18080/`。首次启用时程序会在日志中输出一次初始密码，并要求登录后立即修改；凭据保存在 `config/webui-credentials.json`。

**开发环境下的编码与换行约束**

- 所有文本文件使用 UTF-8 编码，具体范围见 `.editorconfig`。
- Kotlin 与 Kotlin DSL 文件统一 4 空格缩进、最大行宽 120。
- `.bat` 使用 CRLF，`.sh` 与 `.gradle.kts` 使用 LF，由 `.gitattributes` 固定。

### 4.7 首次运行生成的目录结构

```text
config/
├── bot.yml                 # 平台与 WebUI 配置
├── BiliConfig.yml          # 业务配置（管理员、检测、推送、绘图、模板等）
├── BiliData.yml            # 订阅数据与模板策略
└── webui-credentials.json  # WebUI 本地凭据（启用 WebUI 后生成）

data/
├── font/                   # 字体文件目录
├── cache/                  # 绘图、用户、表情等缓存
├── image_cache/            # 图片缓存
├── exception/              # JSON 解析失败样本
└── cookies.json            # 扫码登录后保存的 Cookie

temp/                       # 二维码、帮助图等临时文件

logs/
├── bilibili-bot.log        # 主日志
├── error.log               # 错误日志
└── daemon/
    └── Daemon_YYYY-MM-DD.log  # 每日守护与资源监控日志
```

---

## 5. 使用示例

以下示例中的 `<UID>` 表示 B 站用户 UID，`<联系人>` 表示平台联系人，例如 `onebot11:group:123456789` 或 `onebot11:private:123456789`。

### 5.1 示例 1：订阅一个 UP 主的动态

**基础用法（超级管理员，在当前会话生效）**

```text
/add 2
```

执行结果：机器人提示订阅成功，并把 UID `2` 加入当前群聊或当前私聊的订阅列表。之后该 UP 主动态、视频更新时，会自动推送到当前会话。

**查看当前会话的订阅**

```text
/list
```

**取消订阅**

```text
/del 2
```

**为指定群添加订阅（跨会话操作）**

```text
/bili add 2 onebot11:group:123456789
```

执行结果：UID `2` 的动态推送到群 `123456789`，与当前会话无关。`[群聊联系人]` 参数仅超级管理员可用。

**订阅番剧**

```text
/bili add ss12345
/bili add md12345
/bili add ep12345
```

`ss` / `md` / `ep` 分别对应番剧的季、媒体与剧集标识，推送时会额外带上剧集更新信息。

### 5.2 示例 2：用分组批量管理多个群

当需要把同一批 UP 主推送到多个群时，逐个群执行 `/bili add` 会很繁琐，改用分组可以一次操作全部完成。

```text
# 1. 创建一个分组
/bili group create 主站运营

# 2. 把多个群加入分组（可重复执行）
/bili group add 主站运营 onebot11:group:123456789
/bili group add 主站运营 onebot11:group:987654321

# 3. 为分组批量添加订阅
/bili group subscribe 主站运营 2
/bili group subscribe 主站运营 1234567

# 4. 查看分组详情
/bili group list 主站运营
```

执行结果：两个群都会收到 UID `2` 与 `1234567` 的更新推送。后续从分组移除某个 UID 时，只需要执行一次：

```text
/bili group unsubscribe 主站运营 2
```

**分组与直接订阅的关系**

订阅数据会记录来源，`direct:<联系人>` 表示由 `/bili add` 建立的直接订阅，`groupRef:<分组名>` 表示由分组批量建立。因此在 `/bili list` 中可以看到同一个 UID 同时来自两条路径：

```yaml
dynamic:
  2:
    name: "bishi"
    contacts:
      - "onebot11:group:123456789"
    sourceRefs:
      - "direct:onebot11:group:123456789"
      - "groupRef:主站运营"
    banList: {}
```

### 5.3 示例 3：链接解析

**切换触发模式**

编辑 `config/BiliConfig.yml`：

```yaml
linkResolveConfig:
  triggerMode: "At"      # At: 需要 @机器人；Always: 发现链接就解析；Never: 关闭
  drawEnable: true       # 是否生成图文卡片，关闭后回退为文本链接
  returnLink: false      # 是否在回复中额外返回原始链接
```

| 触发模式 | 行为 | 适用场景 |
| --- | --- | --- |
| `At` | 仅在消息中 `@机器人` 时解析 | 默认值，避免打扰群聊 |
| `Always` | 检测到 B 站链接即解析 | 链接搬运活跃的群 |
| `Never` | 完全关闭链接解析 | 只保留订阅推送 |

**支持的链接类型**

| 类型 | 示例形式 |
| --- | --- |
| 视频 | `BV1xx411c7mD`、`av12345` |
| 专栏 | `cv123456` |
| 动态 / opus | `t.bilibili.com/123456789`、`www.bilibili.com/opus/123456789` |
| 直播间 | `live.bilibili.com/12345` |
| 用户空间 | `space.bilibili.com/2` |
| 番剧 | `ss12345`、`md12345`、`ep12345` |
| 短链 | `b23.tv/xxxxxx`、`bili2233.cn/xxxxxx` |

**解析限流规则**

为避免刷屏，单个群内同一链接 60 秒内只解析一次，同一用户 60 秒内最多触发 3 次解析，单条消息最多通过 3 个链接。超出时机器人会给出频率提示而不是静默丢弃。

**把用户加入链接解析黑名单**

```text
/black onebot11:private:123456789    # 仅群聊可用
/black list                          # 查看黑名单
/unblock onebot11:private:123456789  # 移出黑名单
```

### 5.4 示例 4：用过滤器控制推送内容

过滤器按 UID 维度生效，支持「动态类型」和「正则表达式」两类，各自可以选择黑名单或白名单模式。

**按类型过滤**

```text
# 不推送该 UID 的转发动态
/bili filter add 2 type black 转发动态

# 只推送该 UID 的视频与动态
/bili filter add 2 type white 视频
/bili filter add 2 type white 动态
```

可选类型：`动态`、`转发动态`、`视频`、`音乐`、`专栏`、`直播`。模式也支持写作 `blacklist` / `whitelist` / `黑名单` / `白名单`。

**按正则过滤**

```text
# 屏蔽正文包含「抽奖」二字的动态
/bili filter add 2 regex black 抽奖

# 只保留正文包含「新曲」的动态
/bili filter add 2 regex white 新曲
```

**查看与删除**

```text
/bili filter list 2        # 查看索引列表
/bili filter del 2 t0      # 删除第 0 条类型过滤器
/bili filter del 2 r1      # 删除第 1 条正则过滤器
```

### 5.5 示例 5：自定义推送模板

**第 1 步：在配置中定义模板**

编辑 `config/BiliConfig.yml`：

```yaml
templateConfig:
  defaultDynamicPush: "OneMsg"      # 动态推送的默认模板
  defaultLivePush: "OneMsg"         # 开播推送的默认模板
  defaultLiveClose: "SimpleMsg"     # 下播推送的默认模板
  dynamicPush:
    "DrawOnly": "{draw}"
    "TextOnly": "{name}@{type}\n{link}\n{content}\n{images}"
    "OneMsg": "{draw}\n{name}@{type}\n{link}"
    "TwoMsg": "{draw}\r{name}@{uid}@{type}\n{time}\n{link}"
  livePush:
    "DrawOnly": "{draw}"
    "OneMsg": "{draw}\n{name}@直播\n{link}"
  liveClose:
    "SimpleMsg": "{name} 直播结束啦!\n直播时长: {duration}"
  footer:
    dynamicFooter: ""
    liveFooter: ""
    footerAlign: "LEFT"
```

`{draw}` 会被替换为渲染好的图片，`\n` 表示同一条消息内换行，`\r` 表示拆分为两条消息发送。

**第 2 步：为具体 UID 绑定模板**

```text
# 为当前会话的 UID 2 追加 TwoMsg 模板
/bili template add d TwoMsg 2

# 为分组「主站运营」中的 UID 2 追加模板
/bili template add d TwoMsg 2 group 主站运营

# 查看当前作用域的模板策略
/bili template list d 2

# 发送模板预览，确认排版后再正式启用
/bili template preview d TwoMsg
```

类型缩写：`d` = 动态，`l` = 开播，`le` = 下播。命令别名：`/bili template` 可写作 `/bili tpl`。

**第 3 步：开启随机模板**

```text
/bili template on d 2     # 开启随机
/bili template off d 2    # 关闭随机
```

开启随机后同一个 UID 需要至少 2 个有效模板。随机结果按作用域隔离，并且在同一分组的单次推送内保持一致，也就是说分组内的多个群不会收到风格不同的卡片。

**第 4 步：查看占位符说明**

```text
/bili template explain d
```

### 5.6 示例 6：配置 @全体 策略

`@全体` 策略按「类型 + UID」绑定，只对群聊生效。

```text
# 该 UID 开播时 @全体
/bili atall aa add 直播 2

# 该 UID 发布视频时 @全体
/bili atall aa add 视频 2

# 兼容旧写法，等价于 add
/bili atall aa 全部动态 2

# 查看与删除
/bili atall aa list 2
/bili atall aa del 直播 2
```

支持的类型：`全部` / `all` / `a`，`全部动态` / `dynamic` / `d`，`直播` / `live` / `l`，`视频` / `video` / `v`，`音乐` / `music` / `m`，`专栏` / `article`。

需要注意：`@全体` 是否真正生效取决于上游平台能力。NapCat 与 llbot 支持运行时探测，通用 OneBot11 适配器默认不声明该能力，此时会静默降级为普通推送。

### 5.7 示例 7：为订阅设置主题色

主题色按「会话 + UID」生效，只影响该会话内对应订阅的卡片配色。

```text
# 直接设置
/bili color 2 "#8ab4f8"

# 通过用户名设置
/bili color bishi "#8ab4f8"

# 通过 config 入口设置（等价写法）
/bili config color 2 "#8ab4f8"
```

查看当前会话的配置概览：

```text
/bili config
/bili config 2
```

### 5.8 示例 8：通过 WebUI 管理运行态与配置

**登录**

启用 WebUI 后访问 `http://127.0.0.1:18080/`，使用日志中输出的初始密码登录并立即修改。

**页面能力**

| 页面 | 能力 |
| --- | --- |
| 首页 | 生命周期状态、账号信息、平台连接状态、推送统计、宿主资源占用与最近推送记录 |
| 系统配置 | 读取并批量保存 `BiliConfig.yml`、`BiliData.yml`、`bot.yml`；保存需要当前密码确认，并通过热重载任务应用到运行态 |
| 订阅管理 | 编辑动态、分组、番剧卡片，支持推送群、过滤器、模板、随机模板、`@全体` 与主题色的子项维护 |
| 日志 | 查看、导出、清空固定白名单日志源；清空操作需要当前密码确认 |
| 账号 | 修改 WebUI 密码与退出登录 |

**安全边界**

WebUI 默认只监听 `127.0.0.1:18080`。如果需要暴露到非本机地址，优先放在反向代理、内网穿透或 VPN 之后。直接把 `host` 改成 `0.0.0.0` 属于高风险配置，WebUI 保存时会要求额外确认。

### 5.9 示例 9：常见问题与排查

**启用 DEBUG 日志**

编辑 `config/BiliConfig.yml`：

```yaml
enableConfig:
  debugMode: true
```

裸机部署需要先停止程序，改完配置后再通过 `bin/start.bat` 或 `bin/start.sh` 重启；Docker 部署执行：

```bash
docker compose down
docker compose up -d
```

DEBUG 日志会输出大量信息，仅建议在排查问题时临时启用，日常使用保持 INFO 级别。

**日志位置**

| 文件 | 内容 |
| --- | --- |
| 控制台 | DEBUG 级别全量日志 |
| `logs/bilibili-bot.log` | 主日志 |
| `logs/error.log` | 错误日志 |
| `logs/daemon/Daemon_YYYY-MM-DD.log` | 每日守护与资源监控日志 |

**高频问题对照表**

| 现象 | 原因 | 处理方式 |
| --- | --- | --- |
| 发送图片失败 | 使用了 `file` 发送模式 | 把 `send_mode` 改为 `base64`；`file` 模式依赖接收端能访问同一份本地文件路径，在 Docker 或跨机器场景下容易因路径映射与权限失败 |
| 命令没有反应 | 发送者不是超级管理员 | 确认 `config/BiliConfig.yml` 中的 `admin` 是当前使用的账号 |
| 链接不解析 | 触发模式为 `At` | 在群里 `@机器人` 后发送链接，或把 `triggerMode` 改为 `Always` |
| 容器内连不上 OneBot11 | 网络地址配置错误 | 按 [4.4 容器内的平台地址](#44-生产环境-docker-部署) 调整 `host`，并检查端口与防火墙 |
| 启动脚本报 jemalloc 缺失 | Linux 未安装内存分配器 | 执行 `sudo apt-get install -y libjemalloc2` 或 `sudo dnf install -y jemalloc` |
| 剪贴板/小程序链接不解析 | 上游渠道没有小程序能力 | 官方消息渠道不携带小程序片段，属于预期行为 |

### 5.10 完整命令速查

**基础命令**

| 命令 | 权限 | 说明 |
| --- | --- | --- |
| `/login` 或 `登录` | 超级管理员 | B 站扫码登录，群聊与私聊均可 |
| `/check` | 超级管理员 | 手动触发一次动态检查，仅群聊 |
| `/bili help` | 超级管理员、群普通管理员 | 显示帮助图 |

**快捷命令（仅超级管理员）**

| 命令 | 说明 |
| --- | --- |
| `/add <UID>` | 订阅当前群聊或当前私聊 |
| `/del <UID>` | 取消当前群聊或当前私聊的订阅 |
| `/list` | 查看当前会话的订阅列表 |
| `/black <联系人>` | 加入链接解析黑名单，仅群聊 |
| `/unblock <联系人>` | 移出链接解析黑名单，仅群聊 |
| `/black list` | 查看链接解析黑名单，仅群聊 |

**顶级命令别名**

```text
/bili remove   = /bili rm
/bili list     = /bili ls
/bili template = /bili tpl
/bili atall    = /bili aa
/bili config   = /bili cfg
/bili blacklist= /bili bl
```

**订阅管理（超级管理员、群普通管理员）**

```text
/bili add <UID|ss|md|ep> [群聊联系人]            添加订阅；[群聊联系人] 仅超管可用
/bili remove|rm <UID|ss|md|ep> [群聊联系人]      移除订阅；[群聊联系人] 仅超管可用
/bili list|ls                                    查看当前会话的订阅
/bili list|ls <UID|ss|md|ep>                     查看某个订阅推送到哪些群（仅超管）
```

**分组管理（仅超级管理员）**

```text
/bili groups                                      查看所有分组
/bili group create <分组名>                       创建分组
/bili group delete|del <分组名>                   删除分组
/bili group add <分组名> <群聊联系人>             将群加入分组
/bili group remove|rm <分组名> <群聊联系人>       从分组移除群
/bili group list|ls [分组名]                      查看全部分组或单个分组详情
/bili group subscribe|sub <分组名> <UID|ss|md|ep> 为分组批量添加订阅
/bili group unsubscribe|unsub <分组名> <UID|ss|md|ep> 为分组批量取消订阅
```

**过滤器管理（超级管理员、群普通管理员）**

```text
/bili filter add <UID> type <black|white> <动态|转发动态|视频|音乐|专栏|直播>
/bili filter add <UID> regex <black|white> <正则表达式>
/bili filter list|ls <UID>
/bili filter del|delete|rm <UID> <索引>           索引形如 t0、r1
模式写法: blacklist / whitelist / 黑名单 / 白名单
```

**模板管理（超级管理员、群普通管理员）**

```text
/bili template|tpl add <d|l|le> <模板名> <uid> [group <分组名>]
/bili template|tpl del|delete|rm <d|l|le> <模板名> <uid> [group <分组名>]
/bili template|tpl list|ls <d|l|le> [uid] [group <分组名>]
/bili template|tpl on <d|l|le> <uid> [group <分组名>]
/bili template|tpl off <d|l|le> <uid> [group <分组名>]
/bili template|tpl preview|pv <d|l|le> <模板名>
/bili template|tpl explain|exp <d|l|le>
类型说明: d=动态, l=开播, le=下播
```

**@全体管理（超级管理员、群普通管理员，仅群聊生效）**

```text
/bili atall|aa add <类型> <uid>
/bili atall|aa <类型> <uid>              兼容旧写法，等价于 add
/bili atall|aa del|remove|rm <类型> <uid>
/bili atall|aa list|ls [uid]
```

**配置与主题色**

```text
/bili config|cfg [uid]                            查看当前会话配置概览（超管、群普通管理员）
/bili color <uid|用户名> <HEX颜色>                设置主题色（仅超管）
/bili config|cfg color <uid|用户名> <HEX颜色>     通过 config 入口设置主题色（仅超管）
```

**群普通管理员管理（仅超级管理员）**

```text
/bili admin add <联系人>        添加本群普通管理员，仅群聊
/bili admin remove|rm <联系人>  移除本群普通管理员，仅群聊
/bili admin list|ls             查看本群普通管理员，仅群聊
/bili admin all                 查看全部群的普通管理员
```

**链接解析黑名单（仅超级管理员）**

```text
/bili blacklist|bl add <联系人>
/bili blacklist|bl remove|rm|del <联系人>
/bili blacklist|bl list|ls
```

---

## 6. 配置选项

### 6.1 配置文件总览

| 文件 | 作用 | 写入方 |
| --- | --- | --- |
| `config/bot.yml` | 平台连接与 WebUI 启动参数 | `ConfigManager` / `BotConfigFileStore` |
| `config/BiliConfig.yml` | 管理员、检测、推送、绘图、模板、缓存、代理、翻译、链接解析 | `BiliConfigManager` |
| `config/BiliData.yml` | 订阅数据、分组、番剧、过滤器、模板策略、主题色、`@全体` 策略 | `BiliConfigManager` |
| `config/webui-credentials.json` | WebUI 本地认证凭据 | WebUI 运行时 |

> 不要让业务模块或外部脚本直接改写这些文件。程序在运行期持有配置快照，直接改盘会在进程退出时被覆盖；正确做法是先停程序（或使用 WebUI 的配置保存功能），再修改文件。

### 6.2 bot.yml

**`platform` 节点**

| 参数名 | 类型 | 默认值 | 取值范围 | 说明 |
| --- | --- | --- | --- | --- |
| `platform.type` | 枚举 | `onebot11` | `onebot11`、`qq_official` | 平台类型 |
| `platform.adapter` | 字符串 | `onebot11` | `napcat`、`llbot`、`onebot11` | OneBot11 适配器选择；未知值会归一为通用 `onebot11` |

`platform.type` 目前对外只说明 OneBot11 接入路径，实际行为由 `platform.adapter` 决定。三种适配器的差异如下：

| 平台类型 | `adapter` | 当前定位 | 适用场景 | 能力边界 | 配置要求 |
| --- | --- | --- | --- | --- | --- |
| `onebot11` | `napcat` | 默认推荐 | NapCat WebSocket 接入 | 文本、图片、回复、链接解析；支持群可达性与 `@全体` 运行时探测 | 配置 `platform.onebot11`，图片建议使用 `send_mode: base64` |
| `onebot11` | `llbot` | 已适配 | LuckyLilliaBot 接入 | 文本、图片、回复、链接解析；支持群可达性与 `@全体` 运行时探测 | 配置 `platform.onebot11`，图片建议使用 `send_mode: base64` |
| `onebot11` | `onebot11` | 通用兼容 | 标准 OneBot11 或兼容实现 | 文本、远程图片、回复、链接解析；`@全体` 默认不声明，本地/二进制图片按能力 guard 降级 | 配置 `platform.onebot11`，未覆盖能力会显式降级 |

选型建议：

- 优先选择 `onebot11 + napcat`，这是当前默认推荐方案，能力验证最完整。
- 如果上游就是 LuckyLilliaBot，选择 `onebot11 + llbot` 更合适。
- 如果接的是其他 OneBot11 实现，先用 `onebot11 + onebot11`，再按实际能力决定是否需要 vendor 适配。

三种适配器共用 `platform.onebot11` 配置块，差异主要体现在运行时能力声明与探测行为上。

**`platform.onebot11` 节点（三种适配器共用）**

| 参数名 | 类型 | 默认值 | 取值范围 | 说明 |
| --- | --- | --- | --- | --- |
| `host` | 字符串 | `127.0.0.1` | 非空主机名或 IP | WebSocket 服务器地址 |
| `port` | 整数 | `3001` | `1` ~ `65535` | WebSocket 端口 |
| `token` | 字符串 | 空 | — | 访问令牌，需与 OneBot11 侧一致 |
| `use_tls` | 布尔 | `false` | `true` / `false` | 是否使用 `wss` 协议 |
| `send_mode` | 字符串 | `base64` | `file`、`base64` | 图片发送模式；跨机器或容器部署请使用 `base64` |
| `heartbeat_interval` | 长整数 | `30000` | 毫秒 | 心跳间隔 |
| `reconnect_interval` | 长整数 | `5000` | 毫秒 | 断线重连间隔 |
| `message_format` | 字符串 | `array` | `array`、`string` | 消息段格式，保持 `array` |
| `max_reconnect_attempts` | 整数 | `-1` | `-1` 表示无限重试 | 最大重连次数 |
| `connect_timeout` | 长整数 | `10000` | 毫秒 | 连接超时 |

**`platform.qq_official` 节点**

| 参数名 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `app_id` | 字符串 | 空 | QQ 官方机器人 AppID |
| `app_secret` | 字符串 | 空 | QQ 官方机器人 AppSecret |
| `bot_token` | 字符串 | 空 | QQ 官方机器人 Token |

**`webui` 节点**

| 参数名 | 类型 | 默认值 | 取值范围 | 说明 |
| --- | --- | --- | --- | --- |
| `enabled` | 布尔 | `false` | `true` / `false` | 是否启用 WebUI |
| `host` | 字符串 | `127.0.0.1` | 非空主机名或 IP | 监听地址，改为 `0.0.0.0` 属于高风险配置 |
| `port` | 整数 | `18080` | `1` ~ `65535` | 监听端口，非法值会回退为 `18080` |
| `credential_file` | 字符串 | `webui-credentials.json` | 文件名或绝对路径 | 凭据文件位置，相对路径基于 `config/` 解析 |
| `token_ttl_seconds` | 长整数 | `3600` | 大于 0 | 会话 Token 有效期，非正值回退为 `3600` |
| `static_dir` | 字符串 | 空 | 目录路径 | 外部静态资源目录；为空时使用内置打包资源 |

**其他节点**

| 参数名 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `targets` | 列表 | `[]` | 预留字段，当前版本未启用 |
| `admins` | 列表 | `[]` | 群普通管理员配置，可写 `group_id` / `user_ids` 或 `group_contact` / `user_contacts` |
| `first_run_flag` | 整数 | `0` | 首次运行标记，由程序自动维护，请勿手动修改 |
| `napcat` | 对象 | — | 旧版 OneBot11 配置节点，仅为兼容旧配置保留；`platform.onebot11` 为空时才会回退读取 |

**完整示例**

```yaml
platform:
  type: onebot11
  adapter: napcat
  onebot11:
    host: "127.0.0.1"
    port: 3001
    token: ""
    use_tls: false
    send_mode: "base64"
    heartbeat_interval: 30000
    reconnect_interval: 5000
    message_format: "array"
    max_reconnect_attempts: -1
    connect_timeout: 10000
  qq_official:
    app_id: ""
    app_secret: ""
    bot_token: ""

webui:
  enabled: false
  host: "127.0.0.1"
  port: 18080
  credential_file: "webui-credentials.json"
  token_ttl_seconds: 3600
  static_dir: ""

targets: []
admins: []
first_run_flag: 0
```

### 6.3 BiliConfig.yml

**顶级节点**

| 参数名 | 类型 | 默认值 | 取值范围 | 说明 |
| --- | --- | --- | --- | --- |
| `admin` | 长整数 | `0` | QQ 号 | 超级管理员，会被映射为 `onebot11:private:<QQ号>` |
| `admin_contact` | 字符串 | 空 | 平台联系人 | 显式联系人格式，填写后优先于 `admin` |

**`enableConfig` 功能开关**

| 参数名 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `debugMode` | 布尔 | `false` | 输出 DEBUG 级别日志 |
| `drawEnable` | 布尔 | `true` | 启用绘图功能 |
| `pushDrawEnable` | 布尔 | `true` | 推送时使用绘图；关闭后推送走文本回退 |
| `notifyEnable` | 布尔 | `true` | 启用通知功能 |
| `liveCloseNotifyEnable` | 布尔 | `true` | 启用直播下播通知 |
| `lowSpeedEnable` | 布尔 | `true` | 启用低速模式 |
| `translateEnable` | 布尔 | `false` | 启用翻译功能 |
| `proxyEnable` | 布尔 | `false` | 启用代理 |
| `cacheClearEnable` | 布尔 | `true` | 启用缓存清理 |

**`accountConfig` 账号配置**

| 参数名 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `cookie` | 字符串 | 空 | B 站账号 Cookie，扫码登录后自动写入 |
| `autoFollow` | 布尔 | `true` | 自动关注订阅的用户 |
| `followGroup` | 字符串 | `Bot关注` | 自动关注时使用的分组名 |

**`checkConfig` 检测配置**

| 参数名 | 类型 | 默认值 | 取值范围 | 说明 |
| --- | --- | --- | --- | --- |
| `lowSpeedTime` | 字符串 | `22-8` | `开始小时-结束小时` | 低频时段，`22-8` 表示 22 点到次日 8 点，支持跨午夜 |
| `lowSpeedRange` | 字符串 | `60-240` | `最小-最大`，单位秒 | 低频时段的检测间隔区间 |
| `normalRange` | 字符串 | `30-120` | `最小-最大`，单位秒 | 正常时段的检测间隔区间 |
| `checkReportInterval` | 整数 | `10` | 分钟 | 检查报告输出间隔 |
| `timeout` | 整数 | `10` | 秒 | 单次检测超时 |

**`pushConfig` 推送配置**

| 参数名 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `messageInterval` | 长整数 | `100` | 同一会话内多条消息之间的间隔（毫秒） |
| `pushInterval` | 长整数 | `500` | 不同推送目标之间的间隔（毫秒） |
| `toShortLink` | 布尔 | `false` | 是否把链接转换为短链 |

**`imageConfig` 图片配置**

| 参数名 | 类型 | 默认值 | 取值范围 | 说明 |
| --- | --- | --- | --- | --- |
| `quality` | 字符串 | `1000w` | `1000w`、`750w` | 图片质量档位 |
| `theme` | 字符串 | `v3` | 主题名 | 卡片主题 |
| `font` | 字符串 | 空 | 字体名 | 自定义字体，为空时使用内置字体 |
| `defaultColor` | 字符串 | `#d3edfa` | HEX 颜色 | 默认主题色 |
| `cardOrnament` | 字符串 | `FanCard` | 装饰名 | 卡片装饰样式 |
| `timeDisplayMode` | 枚举 | `ABSOLUTE` | `ABSOLUTE`、`RELATIVE` | 时间显示为绝对时间或相对时间 |
| `colorGenerator.hueStep` | 整数 | `30` | 色相步长 | 自动配色时的色相间隔 |
| `colorGenerator.lockSB` | 布尔 | `true` | — | 固定饱和度与明度 |
| `colorGenerator.saturation` | 浮点数 | `0.25` | `0` ~ `1` | 自动配色的饱和度 |
| `colorGenerator.brightness` | 浮点数 | `1.0` | `0` ~ `1` | 自动配色的明度 |
| `badgeEnable.left` | 布尔 | `true` | — | 显示左侧徽章 |
| `badgeEnable.right` | 布尔 | `false` | — | 显示右侧徽章 |

**`templateConfig` 模板配置**

| 参数名 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `defaultDynamicPush` | 字符串 | `OneMsg` | 动态推送默认模板名 |
| `defaultLivePush` | 字符串 | `OneMsg` | 开播推送默认模板名 |
| `defaultLiveClose` | 字符串 | `SimpleMsg` | 下播推送默认模板名 |
| `dynamicPush` | 映射 | 见下方示例 | 动态推送模板集合，键为模板名，值为模板文本 |
| `livePush` | 映射 | 见下方示例 | 开播推送模板集合 |
| `liveClose` | 映射 | 见下方示例 | 下播推送模板集合 |
| `footer.dynamicFooter` | 字符串 | 空 | 动态卡片页脚文案 |
| `footer.liveFooter` | 字符串 | 空 | 直播卡片页脚文案 |
| `footer.footerAlign` | 字符串 | `LEFT` | 页脚对齐方式 |

可用占位符：`{draw}` 渲染图，`{name}` 名称，`{uid}` UID，`{type}` 动态类型，`{link}` 链接，`{content}` 正文，`{images}` 图片组，`{time}` 时间，`{title}` 标题，`{duration}` 直播时长。

**`cacheConfig` 缓存配置**

| 参数名 | 类型 | 默认值 | 单位 | 说明 |
| --- | --- | --- | --- | --- |
| `downloadOriginal` | 布尔 | `true` | — | 是否下载原图 |
| `expires.DRAW` | 整数 | `7` | 天 | 绘图缓存过期时间 |
| `expires.IMAGES` | 整数 | `7` | 天 | 图片缓存过期时间 |
| `expires.EMOJI` | 整数 | `7` | 天 | 表情缓存过期时间 |
| `expires.USER` | 整数 | `7` | 天 | 用户缓存过期时间 |
| `expires.OTHER` | 整数 | `7` | 天 | 其他缓存过期时间 |

**`proxyConfig` 代理配置**

| 参数名 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `proxy` | 字符串列表 | `[]` | 代理地址列表，仅在 `enableConfig.proxyEnable` 为 `true` 时生效 |

**`translateConfig` 翻译配置**

| 参数名 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `cutLine` | 字符串 | `\n\n〓〓〓 翻译 〓〓〓\n` | 原文与译文之间的分隔线 |
| `baidu.APP_ID` | 字符串 | 空 | 百度翻译 APP_ID |
| `baidu.SECURITY_KEY` | 字符串 | 空 | 百度翻译 SECURITY_KEY |

**`linkResolveConfig` 链接解析配置**

| 参数名 | 类型 | 默认值 | 取值范围 | 说明 |
| --- | --- | --- | --- | --- |
| `triggerMode` | 枚举 | `At` | `At`、`Always`、`Never` | 触发模式 |
| `drawEnable` | 布尔 | `true` | — | 是否生成图文卡片 |
| `returnLink` | 布尔 | `false` | — | 是否在回复中额外返回原始链接 |

**完整示例**

```yaml
admin: 123456789
admin_contact: ""

enableConfig:
  debugMode: false
  drawEnable: true
  pushDrawEnable: true
  notifyEnable: true
  liveCloseNotifyEnable: true
  lowSpeedEnable: true
  translateEnable: false
  proxyEnable: false
  cacheClearEnable: true

accountConfig:
  cookie: ""
  autoFollow: true
  followGroup: "Bot关注"

checkConfig:
  lowSpeedTime: "22-8"
  lowSpeedRange: "60-240"
  normalRange: "30-120"
  checkReportInterval: 10
  timeout: 10

pushConfig:
  messageInterval: 100
  pushInterval: 500
  toShortLink: false

imageConfig:
  quality: "1000w"
  theme: "v3"
  font: ""
  defaultColor: "#d3edfa"
  cardOrnament: "FanCard"
  timeDisplayMode: "ABSOLUTE"
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

translateConfig:
  cutLine: "\n\n〓〓〓 翻译 〓〓〓\n"
  baidu:
    APP_ID: ""
    SECURITY_KEY: ""

linkResolveConfig:
  triggerMode: "At"
  drawEnable: true
  returnLink: false
```

### 6.4 BiliData.yml

`BiliData.yml` 由程序维护，手工编辑前请先停止程序。

| 参数名 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `dataVersion` | 整数 | `4` | 数据结构版本，由程序自动维护并执行迁移 |
| `dynamic` | 映射 | `{}` | 键为 UID，值为订阅信息，含 `name`、`last`、`lastLive`、`contacts`、`sourceRefs`、`banList` |
| `filter` | 映射 | `{}` | 键为联系人，值为该联系人下按 UID 组织的过滤器 |
| `dynamicTemplatePolicyByScope` | 映射 | `{}` | 动态模板策略，键为作用域 |
| `liveTemplatePolicyByScope` | 映射 | `{}` | 开播模板策略，键为作用域 |
| `liveCloseTemplatePolicyByScope` | 映射 | `{}` | 下播模板策略，键为作用域 |
| `dynamicColorByUid` | 映射 | `{}` | 键为联系人，值为该联系人下按 UID 绑定的主题色 |
| `atAll` | 映射 | `{}` | 键为联系人，值为按 UID 组织的 `@全体` 类型集合 |
| `atAllCooldownUntil` | 映射 | `{}` | `@全体` 冷却时间戳，由程序维护 |
| `subscriptionCardUpdatedAt` | 映射 | `{}` | WebUI 订阅卡片更新时间戳，由程序维护 |
| `group` | 映射 | `{}` | 分组数据，键为分组名 |
| `bangumi` | 映射 | `{}` | 番剧订阅数据，键为 `seasonId` |
| `linkParseBlacklist` | 集合 | `{}` | 链接解析黑名单（旧数字 QQ 号，兼容读取） |
| `linkParseBlacklistContacts` | 集合 | `{}` | 链接解析黑名单（平台联系人，新增写入走此字段） |

旧版本字段 `dynamicPushTemplate`、`livePushTemplate`、`liveCloseTemplate` 与 `*TemplateByUid` 仅用于 `dataVersion < 4` 的兼容读取迁移，写回后会从文件中移除。

**作用域命名规则**

| 作用域 | 格式 | 示例 |
| --- | --- | --- |
| 直接联系人 | `contact:<联系人>` | `contact:onebot11:group:987654321` |
| 分组 | `groupRef:<分组名>` | `groupRef:主站运营` |

**示例**

```yaml
dataVersion: 4

dynamic:
  2:
    name: "bishi"
    last: 0
    lastLive: 0
    contacts:
      - "onebot11:group:987654321"
      - "onebot11:private:123456789"
    sourceRefs:
      - "direct:onebot11:group:987654321"
      - "groupRef:主站运营"
    banList: {}

dynamicTemplatePolicyByScope:
  "contact:onebot11:group:987654321":
    2:
      templates:
        - "OneMsg"
        - "TwoMsg"
      randomEnabled: true
  "groupRef:主站运营":
    2:
      templates:
        - "DrawOnly"
      randomEnabled: false

group:
  主站运营:
    name: "主站运营"
    creator: 123456789
    admin: []
    creatorContact: "onebot11:private:123456789"
    adminContacts: []
    contacts:
      - "onebot11:group:987654321"

bangumi:
  12345:
    title: "示例番剧"
    seasonId: 12345
    mediaId: 67890
    type: "ss"
    isEnd: false
    color: null
    contacts:
      - "onebot11:group:987654321"
```

### 6.5 环境变量

`.env.example` 提供了环境变量样例：

| 变量名 | 类型 | 示例值 | 说明 |
| --- | --- | --- | --- |
| `TZ` | 字符串 | `Asia/Shanghai` | 容器时区，`docker-compose.yml` 已显式注入 |
| `JAVA_OPTS` | 字符串 | `-Xms256m -Xmx512m -XX:+UseG1GC` | JVM 参数示例；**不建议**在 Compose 中设置，会覆盖镜像内的 `JAVA_TOOL_OPTIONS` 优化与诊断参数 |
| `NAPCAT_ACCESS_TOKEN` | 字符串 | 空 | OneBot11 访问令牌样例；实际取值以 `config/bot.yml` 的 `token` 为准 |
| `LOG_LEVEL` | 字符串 | `INFO` | 日志级别样例，可选 `TRACE`、`DEBUG`、`INFO`、`WARN`、`ERROR` |
| `LINUX_JDK_HOME` | 字符串 | `C:\path\to\jdk-17-linux-x64` | 交叉打包 Linux 发行包时替代 `-PlinuxJdkHome` 参数 |

### 6.6 自定义配置示例

**场景：一个群需要「视频推卡片 + 动态只发文字 + 不推转发」，并在夜间降低检测频率**

`config/BiliConfig.yml`：

```yaml
checkConfig:
  lowSpeedTime: "23-7"          # 夜间 23 点到次日 7 点降频
  lowSpeedRange: "120-300"      # 降频后 120~300 秒一次
  normalRange: "30-120"         # 白天保持 30~120 秒一次

templateConfig:
  defaultDynamicPush: "TextOnly"   # 动态默认只发文字
  dynamicPush:
    "TextOnly": "{name}@{type}\n{link}\n{content}"
    "OneMsg": "{draw}\n{name}@{type}\n{link}"
```

然后按顺序执行命令：

```text
# 1. 订阅该 UP
/bili add 2 onebot11:group:123456789

# 2. 屏蔽转发动态
/bili filter add 2 type black 转发动态

# 3. 该 UID 改用 OneMsg 模板，保留图片卡片
/bili template add d OneMsg 2

# 4. 确认最终策略
/bili template list d 2
/bili filter list 2
```

执行结果：该 UP 的视频与原创动态会推送图片卡片，转发动态被过滤掉，夜间检测频率自动下降。

---

## 7. 贡献指南

### 7.1 开始之前

仓库根目录的 `AGENTS.md` 是仓库级强制规范，`docs/AGENTS.md` 是文档体系的入口。提交任何改动前请先阅读这两份文件，尤其是编码规范、注释要求和文档分区边界。以下几条是最常被忽略的硬性约束：

- 所有文件必须使用 UTF-8 编码。
- 不得删除、减少或覆盖仓库中已有的注释。
- 新增函数、方法或逻辑块时，必须补充紧邻代码的注释，说明用途、意图或关键约束。
- 修改函数签名时，必须同步更新对应的 KDoc。
- 不得把项目文档内容复制进 `src/test` 测试模块。

### 7.2 分支管理策略

| 分支 | 用途 |
| --- | --- |
| `main` | 默认分支与集成分支，CI 在 `push` 与 `pull_request` 时触发 |
| `feature/<简述>` | 新功能开发 |
| `fix/<简述>` | 缺陷修复 |
| `docs/<简述>` | 文档调整 |
| `refactor/<简述>` | 不改变外部行为的重构 |

请始终从最新的 `main` 切出工作分支，并在提交 PR 前把 `main` 的最新改动合并回你的分支。

### 7.3 提交规范

提交信息遵循 Conventional Commits，仓库现有提交保持一致风格：

```text
<type>(<scope>): <简短描述>
```

| type | 用途 | 仓库中的实际示例 |
| --- | --- | --- |
| `feat` | 新功能 | `feat(tasker, data): 新增动态轮询筛选逻辑与关注状态兼容反序列化器` |
| `fix` | 缺陷修复 | `fix(connector): 修复断线后心跳未重置的问题` |
| `docs` | 文档变更 | `docs: 统一更新文档中的项目名为 Hoshimi-Cat-Bot` |
| `chore` | 构建、依赖、忽略规则等杂项 | `chore(gitignore): 简化.gitignore配置` |
| `refactor` | 重构 | `refactor(service): 收口模板策略读写路径` |
| `test` | 测试变更 | `test(tasker): 补充轮询筛选的回归用例` |

`scope` 建议使用模块名，例如 `tasker`、`connector`、`draw`、`webui`、`config`；跨模块改动可以写成 `feat(tasker, data)`。

### 7.4 本地开发调试

**后端**

```bash
# 编译
./gradlew compileKotlin compileTestKotlin

# 运行测试
./gradlew test

# 只运行某个测试类
./gradlew test --tests "top.bilibili.service.*"

# 运行带原生内存追踪的 Skia 证据测试
./gradlew skiaNativeMemoryEvidenceTest

# 打包
./gradlew shadowJar
```

**前端**

```bash
cd webui-frontend

npm ci               # 严格按 lock 文件安装
npm run lint         # ESLint
npm test             # Vitest
npm run dev          # Vite 开发服务器，监听 127.0.0.1
npm run build        # 产出到 src/main/resources/webui/react
npm run test:e2e     # 构建后运行 Playwright
```

**联调建议**

WebUI 前端开发时，先在 `config/bot.yml` 中启用 WebUI 并启动后端，再用 `npm run dev` 起前端开发服务器，前端请求直接打到本机后端端口。改动前端后需要运行一次 `npm run build`（或执行任意 Gradle 构建任务）才能在完整程序中看到效果，因为后端加载的是打包后的静态资源。

### 7.5 代码审查要求

提交 PR 后，以下 CI 步骤必须全部通过（`.github/workflows/ci.yml`，单次运行限时 10 分钟）：

| 步骤 | 命令 | 说明 |
| --- | --- | --- |
| WebUI 前端检查 | `npm ci && npm run lint && npm test` | 在 `webui-frontend` 目录执行 |
| 编译 | `./gradlew compileKotlin compileTestKotlin` | 编译主源码与测试源码 |
| 测试 | `./gradlew test` | 后端测试 |
| 打包 | `./gradlew shadowJar` | 产出与 Docker、裸机发行链路一致的 fat jar |

审查时重点关注：

1. 是否引入了对具体平台实现（NapCat / LLBot / 官方）的硬依赖，业务层必须只依赖平台中立模型。
2. 配置与数据的读写是否仍收口在 `BiliConfigManager`、`ConfigManager` / `BotConfigFileStore` 中。
3. 新增常驻任务是否纳入了任务启动列表与资源策略声明。
4. Skia 绘制创建的 native 资源是否在会话内被追踪并关闭。
5. 注释与 KDoc 是否随签名变更同步更新。
6. 行为变更是否同步更新了 `docs/` 下对应的维护文档。

### 7.6 问题上报模板

提交 Issue 前请先确认：使用的是最新版本、已经尝试过 [5.9 常见问题与排查](#59-示例-9常见问题与排查) 中的对照项、并已开启 DEBUG 日志复现了一次。

**缺陷报告模板**

```markdown
### 环境信息
- 部署方式：Docker / Windows 发行包 / Linux 发行包 / 源码运行
- 程序版本：v1.8.16
- 平台适配器：napcat / llbot / onebot11 / qq_official
- 操作系统：

### 问题描述
（简述现象，以及它与你预期的差异）

### 复现步骤
1.
2.
3.

### 预期结果
（你期望发生什么）

### 实际结果
（实际发生了什么）

### 日志片段
（粘贴 logs/error.log 或控制台中的关键片段，注意先删除 cookie、token、QQ 号等敏感信息）

### 配置文件
（粘贴 config/bot.yml 与 config/BiliConfig.yml 中与问题相关的片段，务必删除真实凭据）
```

**功能请求模板**

```markdown
### 使用场景
（你在什么场景下需要这个功能）

### 期望行为
（希望程序如何响应）

### 备选方案
（你目前是如何绕过这个限制的）

### 补充说明
（截图、草图或其他上下文）
```

**安全提示**：在任何 Issue、日志或截图中，都不要包含真实的 Cookie、Token、AppSecret、管理员账号或私有服务器地址。

---

## 8. 许可证

### 8.1 许可证类型

本项目采用 **GNU Affero General Public License v3.0（AGPL-3.0）**，完整条款见仓库根目录的 [LICENSE](LICENSE) 文件。

### 8.2 核心权益说明

| 权益 | 说明 |
| --- | --- |
| 使用自由 | 可以出于任何目的运行本程序，包括商业用途 |
| 修改自由 | 可以自由修改源码以满足自身需求 |
| 分发自由 | 可以再分发原始版本或修改版本 |
| 源码获取 | 接收分发版本的用户有权获得完整对应源码 |

需要特别注意的义务：

1. **传染性**：基于本项目修改或衍生的作品，分发时必须继续以 AGPL-3.0 授权，并保留原有版权声明。
2. **网络服务条款**：AGPL-3.0 相对于 GPL 的关键差异在于，如果你修改本项目并通过网络向用户提供服务，必须向这些用户提供对应的完整源码。也就是说，把本项目改造成对外的机器人服务并让用户与之交互时，你需要公开你的修改。
3. **无担保**：本软件按「原样」提供，不附带任何明示或暗示的担保，作者不对使用后果承担责任。
4. **合规使用**：项目本身只提供技术框架，不提供任何数据服务。数据来源、账号登录与内容传播行为由使用者自行负责，请遵守相关平台的服务条款与法律法规。

### 8.3 第三方依赖许可证合规声明

以下为本项目的主要直接依赖及其许可证。项目自身以 AGPL-3.0 发布，所有依赖的许可证均与 AGPL-3.0 兼容，不会对使用者施加额外限制。

**后端（Kotlin / JVM）**

| 依赖 | 版本 | 许可证 | 用途 |
| --- | --- | --- | --- |
| Kotlin Standard Library | 2.0.0 | Apache-2.0 | 语言运行时 |
| kotlinx-coroutines-core | 1.8.0 | Apache-2.0 | 协程 |
| kotlinx-serialization-json | 1.6.3 | Apache-2.0 | JSON 序列化 |
| Ktor Client / Server | 3.0.3 | Apache-2.0 | HTTP 客户端、WebSocket 与 WebUI 服务端 |
| OkHttp | 随 Ktor 传递 | Apache-2.0 | HTTP 引擎 |
| Skiko | 0.8.15 | Apache-2.0 | Skia 的 Kotlin 绑定，图片渲染 |
| KAML | 0.61.0 | Apache-2.0 | YAML 配置解析 |
| ZXing | 3.5.0 | Apache-2.0 | 二维码生成 |
| Logback | 1.4.14 | EPL-1.0 或 LGPL-2.1 | 日志实现 |
| SLF4J API | 2.0.9 | MIT | 日志门面 |

**前端（WebUI）**

| 依赖 | 声明版本 | 许可证 |
| --- | --- | --- |
| React / React DOM | `^19.2.6` | MIT |
| Vite | `^8.1.4` | MIT |
| TypeScript | `~6.0.2` | Apache-2.0 |
| Tailwind CSS | `^4.3.0` | MIT |
| Vitest | `^3.2.7` | MIT |
| Playwright | `^1.56.0` | Apache-2.0 |
| ESLint | `^10.3.0` | MIT |

完整依赖清单与版本以 `build.gradle.kts` 与 `webui-frontend/package.json` 为准。若你需要在自己的发行版中附带许可证文件，请从上述依赖的发布包中提取对应的 LICENSE 文本一并分发。

---

## 9. 致谢 / 相关项目

### 9.1 项目来源

本项目衍生自 [bilibili-dynamic-mirai-plugin](https://github.com/Colter23/bilibili-dynamic-mirai-plugin)，一个运行在 Mirai 上的 B 站动态检测插件。当前实现已经演化为独立应用，但动态检测思路、绘图风格与配置结构都受到上游的深刻影响。上游的原始说明文档保留在仓库根目录的 `README-MIRAI-ORIGINAL.md`，用于追溯原始特性与使用方式；如需确认上游的授权条款，请以该上游仓库为准。

### 9.2 与上游项目的差异

| 维度 | 上游（Mirai 插件） | 本项目 |
| --- | --- | --- |
| 运行形态 | 通过 Mirai / MCL 安装为插件，依赖 mirai-skia-plugin、chat-command 与 Mirai 权限系统 | 独立 Kotlin/JVM 应用，主类 `top.bilibili.MainKt`，可通过发行包、Docker 或源码运行 |
| 平台接入 | 直接运行在 Mirai 的 QQ 联系人、权限与消息模型上 | 平台中立连接层，对接 OneBot11（NapCat / llbot / 通用实现），并按能力 guard 处理图片、回复、`@全体` 与链接解析差异 |
| 配置路径 | Mirai 插件目录下的 `BiliConfig.yml`、`ImageQuality.yml`、`ImageTheme.yml` 等 | 运行目录下的 `config/BiliConfig.yml`、`config/BiliData.yml`、`config/bot.yml` |
| 联系人标识 | Mirai 联系人对象 | 统一 subject，如 `onebot11:group:<id>`、`onebot11:private:<id>` |
| 保留能力 | 动态/直播检测、番剧订阅、扫码登录、自动关注、过滤器、模板、主题色、缓存清理、Skia/Skiko 绘图 | 全部保留 |
| 扩展能力 | — | 联系人/分组作用域模板策略、随机模板、链接解析限流、运行态观测、资源生命周期管理、平台能力降级、内置 WebUI、Docker 与 jlink 发行包 |

### 9.3 贡献者

感谢所有为本项目提交过代码、文档与问题反馈的贡献者。

| 贡献者 | 贡献 |
| --- | --- |
| [menghuanan](https://github.com/menghuanan) | 项目作者与主要维护者 |
| [MoeJiyun233](https://github.com/MoeJiyun233) | 代码贡献 |

同时感谢上游项目 [bilibili-dynamic-mirai-plugin](https://github.com/Colter23/bilibili-dynamic-mirai-plugin) 的作者 Colter23 及全体贡献者，以及上游 README 中致谢的 [bilibili-helper](https://github.com/cssxsh/bilibili-helper)（作者 cssxsh）与 [Twemoji](https://github.com/twitter/twemoji) 项目。

### 9.4 相关生态项目

**下游平台接入**

| 项目 | 链接 | 关联说明 |
| --- | --- | --- |
| OneBot 11 标准 | [botuniverse/onebot-11](https://github.com/botuniverse/onebot-11) | 本项目对外通信所遵循的协议标准 |
| NapCat | [NapNeko/NapCatQQ](https://github.com/NapNeko/NapCatQQ) | 默认推荐的 OneBot11 实现，本项目提供专用适配器 `adapter: napcat` |
| LuckyLilliaBot | [LLOneBot/LuckyLilliaBot](https://github.com/LLOneBot/LuckyLilliaBot) | 已适配的 OneBot11 实现，使用 `adapter: llbot` |
| 通用 OneBot11 实现 | — | 使用 `adapter: onebot11`，未覆盖的能力会显式降级 |

**核心依赖项目**

| 项目 | 链接 | 关联说明 |
| --- | --- | --- |
| Skiko | [JetBrains/skiko](https://github.com/JetBrains/skiko) | Kotlin 的 Skia 绑定，承担全部图片渲染 |
| Ktor | [ktorio/ktor](https://github.com/ktorio/ktor) | HTTP 客户端与 WebUI 服务端 |
| kotlinx.serialization | [Kotlin/kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | 配置与数据序列化 |
| KAML | [charleskorn/kaml](https://github.com/charleskorn/kaml) | YAML 配置解析 |
| ZXing | [zxing/zxing](https://github.com/zxing/zxing) | 扫码登录二维码生成 |
| React | [facebook/react](https://github.com/facebook/react) | WebUI 前端框架 |
| Vite | [vitejs/vite](https://github.com/vitejs/vite) | WebUI 前端构建工具 |

**同源或可参考的项目**

| 项目 | 链接 | 关联说明 |
| --- | --- | --- |
| bilibili-dynamic-mirai-plugin | [Colter23/bilibili-dynamic-mirai-plugin](https://github.com/Colter23/bilibili-dynamic-mirai-plugin) | 本项目的上游项目 |
| Mirai | [mamoe/mirai](https://github.com/mamoe/mirai) | 上游项目所依赖的机器人框架，本项目已不再使用 |

### 9.5 交流与反馈

- 问题反馈与功能建议：[GitHub Issues](https://github.com/menghuanan/Hoshimi-Cat-Bot/issues)
- 版本发布与更新日志：[GitHub Releases](https://github.com/menghuanan/Hoshimi-Cat-Bot/releases)
- 容器镜像：[Docker Hub](https://hub.docker.com/r/menghuanan/hoshimi-cat-bot)
- 交流群：[点击链接加入 QQ 群](https://qm.qq.com/q/VfN3EQggQI)

### 9.6 免责声明

本项目仅提供技术框架，不提供任何数据服务。

所有数据来源、账号登录及相关行为均由使用者自行负责，开发者不参与、不存储任何用户数据。

用户基于本项目进行的内容获取、处理及传播行为，均由使用者自行承担责任，与开发者无关。

使用本项目时，请遵守相关平台的服务条款及法律法规。
