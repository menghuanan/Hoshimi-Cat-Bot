# 部署流程

本文记录当前项目的 Docker 与裸机部署方式。内容以 `Dockerfile`、`docker-compose.yml`、`docker-entrypoint.sh` 和 Gradle distribution 配置为准。

## 构建要求

- JDK 17。
- Gradle wrapper。
- Node.js 22+ 与 npm，用于 WebUI 前端打包和静态检查。
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

说明：

- `windowsReleaseDistZip` 只能在 Windows runner 上执行（产出内置 Windows jlink runtime）。
- `linuxReleaseDistTar` 只能在 Linux runner 上执行（产出内置 Linux jlink runtime）。

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

- 镜像：`menghuanan/dynamic-bot:latest`
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

## 裸机发行包

Gradle 会生成：

- Windows：`dynamic-bot-windows-x64-v<version>.zip`
- Linux：`dynamic-bot-linux-x64-v<version>.tar.gz`

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
- 缺少 jemalloc 时会继续使用系统默认分配器启动，只留下告警
- 使用与 Windows 类似的 JVM heap/G1/Skiko 参数
- 发行包内置 runtime 会额外携带 `jdk.charsets`，以保证二维码生成等依赖 GB2312 字符集的路径在精简运行时内可用

发布流程约束：

- GitHub Release 必须在 Windows/Linux 原生 runner 分别打包后再聚合发布，避免跨平台 runtime 不匹配。

## 部署 checklist

- [ ] 是否已构建最新 `shadowJar`？
- [ ] Docker 镜像是否包含目标版本 jar？
- [ ] 是否挂载 `config`、`data`、`temp`、`logs`？
- [ ] Linux 裸机是否希望启用 jemalloc 优化？
- [ ] 是否保持 UTF-8 和 Asia/Shanghai 时区参数？
- [ ] 是否保留 software rendering 参数？
- [ ] 修改 JVM 参数后是否更新 [`memory-tuning.md`](memory-tuning.md)？
