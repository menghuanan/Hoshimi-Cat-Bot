# JVM 与 native 内存调优记录

**覆盖度**：核心流程 ✅ | 完整参数 ✅ | 边界情况 ⚠️

本文记录当前项目内存参数的设计意图和维护边界。dynamic-bot 是常驻进程，内存策略优先考虑 7x24 稳定和 native/RSS 可观测性。

## 当前目标

- 低常驻 heap。
- 限制 Metaspace、CodeCache、DirectMemory。
- Skiko 强制软件渲染。
- 使用 jemalloc 降低 Linux glibc malloc 碎片和 RSS 高水位。
- 保留 NMT summary 以支持 `ProcessGuardian` 采样。

## Docker 参数总览

以下参数必须与 `Dockerfile` 保持一一对应。新增、删除或重排参数时，应先同步 `Dockerfile`，再更新本文。

### jemalloc 与 native 分配器参数

| 来源 | 参数 | 当前值 | 用途 |
| --- | --- | --- | --- |
| `ENV LD_PRELOAD` | `LD_PRELOAD` | `/usr/lib/x86_64-linux-gnu/libjemalloc.so.2` | 强制优先使用 jemalloc |
| `ENV MALLOC_CONF` | `MALLOC_CONF` | `background_thread:true,dirty_decay_ms:2000,muzzy_decay_ms:2000,narenas:1,tcache:false` | 控制页归还、arena 数量与 tcache，降低 RSS 高水位 |

### `JAVA_TOOL_OPTIONS` 完整清单

| 顺序 | 参数 | 当前值 | 用途/维护要点 |
| --- | --- | --- | --- |
| 1 | `-XX:+UseG1GC` | 开启 | 固定使用 G1 |
| 2 | `-XX:NativeMemoryTracking` | `summary` | 保留轻量 NMT 摘要供 `ProcessGuardian`/`jcmd` 采样 |
| 3 | `-XX:MaxGCPauseMillis` | `100` | G1 目标停顿时间 |
| 4 | `-XX:G1ReservePercent` | `15` | 保留 G1 预留区，避免突发晋升挤压 |
| 5 | `-XX:MinHeapFreeRatio` | `10` | 允许 heap 更积极收缩 |
| 6 | `-XX:MaxHeapFreeRatio` | `20` | 限制静默期 committed heap 高水位 |
| 7 | `-XX:G1PeriodicGCInterval` | `60000` | 每 60 秒允许周期性 GC |
| 8 | `-XX:G1PeriodicGCSystemLoadThreshold` | `0` | 不依赖系统负载门槛，静默时也可回收 |
| 9 | `-XX:MaxDirectMemorySize` | `32m` | 限制 DirectByteBuffer/native NIO 缓冲 |
| 10 | `-XX:MetaspaceSize` | `16m` | Metaspace 初始触发点 |
| 11 | `-XX:MaxMetaspaceSize` | `48m` | Metaspace 上限 |
| 12 | `-XX:CompressedClassSpaceSize` | `16m` | 压缩类空间上限 |
| 13 | `-XX:InitialCodeCacheSize` | `32m` | CodeCache 初始大小 |
| 14 | `-XX:ReservedCodeCacheSize` | `32m` | CodeCache 总上限 |
| 15 | `-XX:+UseCodeCacheFlushing` | 开启 | 允许编译缓存回收 |
| 16 | `-XX:TieredStopAtLevel` | `1` | 降低 JIT 层级，减少编译开销和 CodeCache 压力 |
| 17 | `-XX:CICompilerCount` | `2` | 限制编译线程数 |
| 18 | `-XX:CompileThreshold` | `10000` | 提高热点编译阈值 |
| 19 | `-XX:+HeapDumpOnOutOfMemoryError` | 开启 | OOM 时保留 heap dump |
| 20 | `-XX:HeapDumpPath` | `/app/logs/heapdump.hprof` | Heap dump 输出位置 |
| 21 | `-XX:+ExitOnOutOfMemoryError` | 开启 | OOM 后直接退出，避免半死进程继续跑 |
| 22 | `-XX:+UseStringDeduplication` | 开启 | 降低重复字符串占用 |
| 23 | `-Xss` | `512k` | 压缩线程栈体积 |
| 24 | `-Djdk.nio.maxCachedBufferSize` | `65536` | 限制 JDK NIO 临时缓冲缓存上限 |
| 25 | `-Dio.netty.allocator.type` | `unpooled` | 禁用 Netty pooled allocator，避免额外 native/直接内存池 |
| 26 | `-Djava.awt.headless` | `true` | 固定 headless 模式 |
| 27 | `-Dskiko.renderApi` | `SOFTWARE` | 强制 Skiko 软件渲染 |
| 28 | `-Dskiko.hardwareAcceleration` | `false` | 禁用硬件加速 |
| 29 | `-Dskiko.resourceCache.maxBytes` | `50331648` | Skiko 资源缓存上限 48 MiB |
| 30 | `-Dskiko.vsync.enabled` | `false` | 禁用 vsync 相关行为 |
| 31 | `-Dsun.java2d.opengl` | `false` | 禁用 AWT OpenGL 管线 |
| 32 | `-Dsun.java2d.xrender` | `false` | 禁用 XRender 管线 |
| 33 | `-Dsun.java2d.pmoffscreen` | `false` | 禁用 Java2D 离屏像素管理优化 |

## Heap 策略

默认 heap 来自 `Dockerfile` 的 `CMD`，不在 `JAVA_TOOL_OPTIONS` 里：

- `-Xms64m`
- `-Xmx160m`

近期提交中已引入 G1 周期性回收和更积极 heap shrink 参数，用于长时间静默场景降低 committed heap 高水位。

## Native/RSS 策略

Docker 镜像通过 `LD_PRELOAD` 强制使用 jemalloc；Linux 裸机启动脚本要求 `libjemalloc.so.2` 可用，不可用时尝试交互安装或失败退出；Windows 裸机不使用 jemalloc。当前 `MALLOC_CONF` 的重点约束是：

- `background_thread:true`：后台回收线程常驻，避免空闲页长期滞留。
- `dirty_decay_ms=2000`、`muzzy_decay_ms=2000`：2 秒内加速把脏页/惰性页归还给系统。
- `narenas:1`：压低 arena 数量，减少碎片和 RSS 虚高。
- `tcache:false`：关闭线程本地缓存，降低长期驻留页。

**原因**：Skiko、OkHttp、字体和图像处理会产生 native 分配；jemalloc decay 策略能更快把空闲页归还给系统。

## Skia 策略

- 强制 software rendering。
- 禁用硬件加速。
- 限制 Skiko resource cache。
- 通过 `SkiaCleanupTasker` 周期 purge。
- 通过 `ProcessGuardian` 记录 Skia 状态和队列。

## 调参红线

- 不要关闭 `NativeMemoryTracking=summary`，除非同步说明诊断替代方案。
- 不要移除 software rendering 参数，除非验证所有部署环境的图形依赖。
- 不要重复添加 `UseStringDeduplication`、`jdk.nio.maxCachedBufferSize`、`io.netty.allocator.type` 等已存在参数，否则容易制造冲突或伪优化。
- 不要在 compose 中用 `JAVA_OPTS` 覆盖 `JAVA_TOOL_OPTIONS`。
- 不要把 `-Xmx` 调得过低后跳过完整启动、绘图和链接解析验证。
- 不要把 `Metaspace`、`CodeCache` 上限提高后不更新监控阈值。

## 验证建议

- 常规：`./gradlew.bat test`
- Skia native 证据：`./gradlew.bat skiaNativeMemoryEvidenceTest`
- 容器：观察 `/app/logs/daemon/Daemon_*.log`
- Linux：确认 NMT summary 可由 `jcmd <pid> VM.native_memory summary` 采样
- Linux 裸机：确认 `start.sh` 能解析 `libjemalloc.so.2`；非交互部署应在启动前由系统包管理器安装
