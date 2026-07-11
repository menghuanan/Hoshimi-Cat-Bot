# 运行故障排查

本文提供从运行症状定位到日志、指标和恢复动作的当前入口。先收集证据，再执行可逆恢复；不要从 `docs/plans`、`docs/过期文档` 或历史 release 说明推断当前行为。

## 排查顺序

按以下顺序缩小故障范围：

1. 记录发生时间、部署方式、平台类型、最近变更和可复现操作。
2. 检查 `logs/error.log`、`logs/bilibili-bot.log` 和同一时间窗口的 `logs/daemon/Daemon_*.log`。
3. 判断故障属于启动、平台连接、Tasker、业务 channel、Skia/native、配置热重载还是 WebUI。
4. 先执行模块提供的受控 reload、stop 或 restart 入口，不直接删除配置、日志、缓存索引或凭据文件。
5. 恢复后保留关键日志、配置版本和时间线；未确认根因写入 [`../context/known-issues.md`](../context/known-issues.md)，不要写成已发生事故。

## 证据来源

| 来源 | 能证明什么 | 不能单独证明什么 |
| --- | --- | --- |
| `logs/bilibili-bot.log` | 启动步骤、平台事件、业务流程和普通告警 | native 内存完整归属 |
| `logs/error.log` | 异常堆栈、失败请求和未恢复错误 | 进程整体健康或消息是否最终送达 |
| `logs/daemon/Daemon_*.log` | Tasker、heap、non-heap、NMT、RSS、平台 pressure、本地业务队列、Skia 队列和资源快照 | JVM/NMT 无法识别的全部 native RSS 精确归属 |
| WebUI Dashboard | 当前生命周期、平台、宿主资源、推送统计和最近推送 | 未启用 WebUI 时的历史状态 |
| `jcmd <pid> VM.native_memory summary` | JVM 识别的 native reserved/committed 分类 | JVM 外全部 RSS 的精确归属 |
| Linux `/proc/<pid>/status` 与 `smaps_rollup` | VmRSS、匿名页和映射汇总 | Windows 进程内存 |
| Docker `HEALTHCHECK` | Java 进程仍存在 | Bot 已启动、平台已连接或 WebUI `/api/health` 可用 |

主日志和错误日志由 Logback 按 30 天配置滚动；`LogClearTasker` 还会每 7 天清理命中的普通滚动日志和守护日志。排查跨周问题前先确认目标文件仍存在。

## 常见症状

### WebUI 凭据损坏但核心 Bot 正常

检查错误中列出的 `config/webui-credentials.json` 原文件和最新 `.bak` 路径，保留现场后人工恢复；程序不会覆盖损坏文件或生成新密码。该故障只禁用 WebUI。

### bot.yml 损坏导致核心启动失败

启动错误会列出原文件和最近备份。先复制保留损坏文件，再人工校验或恢复备份；程序不会自动写默认配置覆盖已有文件。

### 消息持续重试或出现永久失败

检查 `data/delivery-ledger.json`、平台发送错误和联系人范围。不要直接删除账本；`RETRY_WAIT` 会跨重启恢复，`PERMANENT_FAILURE` 表示已耗尽次数或时间预算。

### Tasker 进入熔断

检查主 Job 最后异常和 30 分钟恢复记录。修复根因后重启进程可重置熔断，本版本不提供在线人工恢复 API。

### 容器停机超过预期

按 `INGRESS → WORKERS → CHANNELS → DEPENDENCIES → ROOT_SCOPE` 检查阶段耗时，并确认 90/100/110 秒三层预算未被部署配置缩短。

### 进程存在但 Bot 没有启动

检查是否出现“初始化配置失败”“平台配置无效”“必需启动阶段失败”或“Bot 启动失败”，并确认是否出现“Bot 启动成功”。配置、平台、业务数据或任一 Tasker 初始化失败都会以状态码 1 退出；若外部守护仍显示正常，应检查其退出码与重启策略。

修复配置后主动停止旧进程再重启。不要让外部守护同时保留失败进程和新进程。

### 平台已连接但没有消息响应

依次检查：

- `PlatformRuntimeStatus.connected`、重连次数和 inbound/outbound dropped 计数
- `ListenerTasker`、`SendTasker` 与消息 Tasker 的 health snapshot
- capability guard 是否返回 `Unsupported` 或 `Degraded`
- QQ 官方消息是否命中 connector 入站门禁
- `logs/error.log` 中是否有发送、图片上传或链接解析失败

QQ 官方当前只放行精确 `/login` 和 B 站链接候选。群聊 `/login` 必须来自 AT 事件；其他斜杠命令和普通文本不会进入业务链。

### 出现背压或消息延迟

守护日志中的“Channel 背压”同时覆盖平台 inbound/outbound pressure 与四条本地业务队列。检查 `[本地业务队列]` 的 `size/capacity/fillRatio`，80% 以上会触发告警。

先检查平台连接与 `SendTasker` 健康，再对照动态/直播生产速率、四条队列填充率和发送失败日志。

### RSS 或 native 内存持续增长

先区分 JVM heap、Metaspace、CodeCache、Direct/BufferPool、NMT committed、Skia 队列和未归类 RSS。`SkiaManagerStatus.memoryUsage` 是 JVM heap 使用率；`resourceCacheBytes` 只代表 Graphics 可直接观测的 Skia native resource cache，完整 native 压力仍需结合 NMT 与 RSS。

Linux 可采集：

```bash
jcmd <pid> VM.native_memory summary
cat /proc/<pid>/status
cat /proc/<pid>/smaps_rollup
```

RSS 连续超过 300 MB 时，`ProcessGuardian` 在 10 分钟后告警，在 30 分钟后执行停机并以状态码 78 退出。Compose 的 `restart: unless-stopped` 会重新拉起容器；裸机启动脚本不会自动重启，需要外部进程管理器。

### Skia 清理后仍有高水位

检查 Skia pending/active、NMT、RSS 和清理前后趋势。warning 阈值只写告警；普通清理由空闲超时或固定周期触发，critical 阈值触发紧急清理。

普通与紧急 purge 都在 `runExclusiveCleanup()` 完整闸门内执行；等待活动绘图超时时会跳过本轮 purge。不要把一次跳过或 purge 后 RSS 未立即回落直接判定为泄漏。

### WebUI 在 Docker 外无法访问

WebUI 默认关闭，默认监听 `127.0.0.1:18080`，仓库 Compose 也不映射该端口。若要从容器外访问，必须同时：

1. 在 `config/bot.yml` 设置 `webui.enabled: true`
2. 把容器内监听地址改为 `0.0.0.0`
3. 在 Compose 增加受控端口映射或反向代理
4. 从首次启动日志获取初始密码并立即修改

对外暴露前必须使用可信反向代理、访问控制和传输加密。不要把管理面直接暴露到公网。

### WebUI 保存后配置没有生效

检查 `POST /api/config/save-batch` 返回的 job ID，并轮询 `/api/config/save-jobs/{jobId}` 到 `APPLIED` 或 `FAILED`。失败时确认旧运行态是否仍工作、磁盘回滚是否成功，以及响应是否包含新的 `webUiRedirectUrl`。

手工修改 `config/*.yml` 不会触发文件 watcher。需要运行期应用时，使用现有 reload 或 WebUI 保存链路。

## 采集最小诊断包

在报告问题前收集：

- 故障时间和时区
- Git commit 或发行版本
- Docker、Windows 裸机或 Linux 裸机
- 平台类型和 adapter kind，不包含 token、cookie 或 app secret
- 同一时间窗口的应用、错误和守护日志
- 相关配置字段名与脱敏值
- 已执行的恢复动作及结果

禁止上传完整 `BiliConfig.yml`、`bot.yml`、`webui-credentials.json`、Cookie、Authorization header、内网地址或未脱敏 URL。

## 排查 checklist

- [ ] 是否已确认问题时间、部署方式、版本和平台？
- [ ] 是否同时查看应用、错误和守护日志？
- [ ] 是否区分平台 pressure 与本地业务 channel？
- [ ] 是否区分 JVM heap、NMT committed 与 RSS？
- [ ] 是否核对 [`monitoring.md`](monitoring.md)、[`deployment.md`](deployment.md) 和相关模块文档？
- [ ] 是否只执行可逆恢复，并保留脱敏证据？
