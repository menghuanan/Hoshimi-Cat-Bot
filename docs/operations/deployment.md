# 部署流程

本文记录当前项目的 Docker 与裸机部署方式。内容以 `Dockerfile`、`docker-compose.yml`、`docker-entrypoint.sh` 和 Gradle distribution 配置为准。

## 构建要求

- JDK 17。
- Gradle wrapper。
- Node.js `^20.19.0` 或 `>=22.12.0` 与 npm，用于 WebUI 前端打包和静态检查；该下限来自当前 Vite 锁定版本。
- Kotlin JVM 单模块项目。
- 主类：`top.bilibili.MainKt`。

常用构建：

```powershell
./gradlew.bat shadowJar
```

平台发行包任务：

```powershell
./gradlew.bat windowsReleaseDistZip linuxReleaseDistTar
```

Windows 本地交叉编译 Linux 发行包时，必须额外提供已解压的 Linux x64 JDK 17：

```powershell
.\gradlew.bat linuxReleaseDistTar -PlinuxJdkHome=C:\path\to\jdk-17-linux-x64
```

也可以用 `LINUX_JDK_HOME` 环境变量替代 `-PlinuxJdkHome`。

说明：

- `windowsReleaseDistZip` 只能在 Windows runner 上执行（产出内置 Windows jlink runtime）。
- `linuxReleaseDistTar` 在 Linux runner 上会直接使用当前 JDK 产出内置 Linux jlink runtime；在 Windows 上需要通过 `linuxJdkHome`/`LINUX_JDK_HOME` 指向 Linux x64 JDK 17 的 `jmods`。

## Docker 部署

当前 Docker 镜像基于 `eclipse-temurin:17-jdk`，保留 JDK 是为了容器内可用 `jcmd` 和 NMT 诊断能力。

镜像包含：

- `libjemalloc2`
- `procps`
- `fonts-dejavu-core`
- `fonts-noto-color-emoji`
- X11/AWT/Skiko 软件渲染依赖
- freetype/fontconfig/harfbuzz/png/jpeg/webp/zlib

默认 JVM 策略：

- G1GC。
- `NativeMemoryTracking=summary`。
- heap 默认 `-Xms64m -Xmx160m`。
- Metaspace 上限 48m。
- CodeCache 上限 32m。
- DirectMemory 上限 32m。
- Skiko software rendering。
- jemalloc `background_thread:true,dirty_decay_ms:2000,muzzy_decay_ms:2000,narenas:1,tcache:false`。

## docker-compose 约束

`docker-compose.yml` 当前默认：

- 镜像：`menghuanan/hoshimi-cat-bot:latest`
- `restart: unless-stopped`
- `TZ=Asia/Shanghai`
- `mem_limit: 512m`
- `shm_size: 256m`
- 日志 `json-file`，`max-size=100m`，`max-file=5`

挂载目录：

- `./config:/app/config`
- `./data:/app/data`
- `./temp:/app/temp`
- `./logs:/app/logs`

**禁止**：在 compose 中随意设置 `JAVA_OPTS` 覆盖容器默认优化参数，除非明确同步更新 [`memory-tuning.md`](memory-tuning.md)。

## Docker entrypoint

`docker-entrypoint.sh` 负责：

- 合并 CMD 传入的 heap 参数。
- 固定 `-Dfile.encoding=UTF-8`。
- 固定 `-Duser.timezone=Asia/Shanghai`。
- 可选启用 RSS watchdog。
- 捕获 SIGTERM/SIGINT 并给 JVM 最多 8 秒优雅退出窗口。

RSS watchdog 只有在 `MEMORY_THRESHOLD_MB` 为正数时启用。

Docker `HEALTHCHECK` 只检查 Java 进程是否存在，不调用 WebUI `/api/health`，也不能证明 Bot 已进入 `RUNNING`、平台已连接或 Tasker 正常。

`.env.example` 当前不会被仓库 Compose 自动加载：`docker-compose.yml` 没有 `env_file`，也没有引用其中的变量。运行配置仍以挂载的 `config/*.yml`、Compose `environment` 和镜像启动参数为准。

## Docker 中启用 WebUI

WebUI 默认关闭并监听 `127.0.0.1:18080`，仓库 Compose 默认不映射该端口。容器外访问需要同时完成：

1. 在挂载的 `config/bot.yml` 设置 `webui.enabled: true`
2. 把容器内 `webui.host` 设置为 `0.0.0.0`
3. 在 Compose 增加受控端口映射，例如仅绑定宿主回环地址的 `127.0.0.1:18080:18080`
4. 从首次启动日志读取初始密码，登录后立即修改

若要跨主机访问，优先让可信反向代理或虚拟专用网络（VPN）终止传输加密并限制来源。不要直接把 WebUI 端口暴露到公网；代理必须覆盖外部传入的 `Forwarded` 与 `X-Forwarded-*`，避免客户端伪造来源信息。

## 裸机发行包

Gradle 会生成：

- Windows：`hoshimi-cat-bot-windows-x64-v<version>.zip`
- Linux：`hoshimi-cat-bot-linux-x64-v<version>.tar.gz`

Windows `start.bat`：

- `chcp 65001`
- 优先使用发行包内 `runtime\bin\java.exe`，不再依赖系统 PATH 中的 Java
- `-Xms64m -Xmx160m`
- G1 周期回收和 heap shrink 参数
- UTF-8、时区、Skiko software rendering

Linux `start.sh`：

- 优先使用发行包内 `./runtime/bin/java`，不再依赖系统 PATH 中的 Java
- 启动前探测 `libjemalloc.so.2`
- jemalloc 可用时会自动启用并设置 `LD_PRELOAD` 和默认 `MALLOC_CONF`
- 缺少 jemalloc 时，交互终端会询问是否通过受支持的系统包管理器安装；拒绝安装、非交互运行、安装失败或安装后仍不可用都会以状态码 1 退出
- 使用与 Windows 类似的 JVM heap/G1/Skiko 参数
- 发行包内置 runtime 会额外携带 `jdk.charsets`，以保证二维码生成等依赖 GB2312 字符集的路径在精简运行时内可用

## 运行环境差异

| 环境 | JVM/NMT | jemalloc | 退出后的恢复 |
| --- | --- | --- | --- |
| Docker | 使用 Dockerfile 完整 `JAVA_TOOL_OPTIONS`，默认启用 NMT summary | 镜像内置并通过 `LD_PRELOAD` 强制启用 | Compose `restart: unless-stopped` 可在状态码 78 后重新拉起 |
| Linux 裸机发行包 | 使用 `start.sh` 的 heap shrink、编码、时区和 software rendering 参数，不保证与 Docker 完整参数相同 | 启动前必须可用，否则尝试交互安装或失败退出 | 启动脚本不自动重启，需要 systemd 等外部管理器 |
| Windows 裸机发行包 | 使用 `start.bat` 的 heap shrink、编码、时区和 software rendering 参数 | 不使用 jemalloc | 启动脚本不自动重启，需要外部管理器或人工处理 |

Linux 的 RSS 软限制依赖 `/proc` 指标。连续超过 300 MB 达 30 分钟时，`ProcessGuardian` 会先执行停机，再以状态码 78 退出；该动作本身不负责拉起新进程。WebUI `request-restart` 默认也只返回需要人工或外部管理器接管的结果。

发布流程约束：

- GitHub Release 必须在 Windows/Linux 原生 runner 分别打包后再聚合发布，避免跨平台 runtime 不匹配。
- Windows 本地交叉编译 Linux 裸机包时，必须显式提供 Linux x64 JDK 17，并在打包任务中保留 `runtime/bin/*` 与 `bin/start.sh` 的可执行权限。

## 部署 checklist

- [ ] 是否已构建最新 `shadowJar`？
- [ ] Docker 镜像是否包含目标版本 jar？
- [ ] 是否挂载 `config`、`data`、`temp`、`logs`？
- [ ] Linux 裸机是否已安装并可解析 `libjemalloc.so.2`？
- [ ] 是否保持 UTF-8 和 Asia/Shanghai 时区参数？
- [ ] 是否保留 software rendering 参数？
- [ ] Docker 外访问 WebUI 时，是否同时配置监听地址、端口映射、反向代理和访问控制？
- [ ] 修改 JVM 参数后是否更新 [`memory-tuning.md`](memory-tuning.md)？
