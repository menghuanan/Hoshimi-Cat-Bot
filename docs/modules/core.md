# Core 模块

## 模块定位

Core 模块承载 bot 运行期骨架、命令入口、数据存储协调和资源生命周期监督。它是启动层、service 层、connector 层和 tasker 层之间的装配边界。

## 代码入口

- `src/main/kotlin/top/bilibili/core/BiliBiliBot.kt`
- `src/main/kotlin/top/bilibili/core/BiliCommandProcessor.kt`
- `src/main/kotlin/top/bilibili/core/DataStorage.kt`
- `src/main/kotlin/top/bilibili/core/resource/BusinessLifecycleManager.kt`
- `src/main/kotlin/top/bilibili/core/resource/ResourceStrictness.kt`
- `src/main/kotlin/top/bilibili/core/resource/ResourceSupervisor.kt`
- `src/main/kotlin/top/bilibili/core/resource/TaskResourcePolicyRegistry.kt`

## 主要职责

- 维护 `BiliBiliBot` 根生命周期和根协程作用域。
- 持有动态、直播和消息 channel。
- 连接命令处理、消息网关、平台连接、service 和 tasker。
- 通过 `ResourceSupervisor` 管理有序停机。
- 通过 `TaskResourcePolicyRegistry` 定义 tasker 资源策略。

## 禁止事项

- 禁止业务服务直接绕过 core 创建新的根协程作用域。原因：停机无法统一回收。
- 禁止随意改变 `ResourceSupervisor` 分区顺序。原因：停机必须按逆依赖收敛。
- 禁止新增 tasker 后不登记 `TaskResourcePolicyRegistry`。原因：启动、监控和停机都会丢失资源边界。
- 禁止在 core 中写具体平台 vendor 协议。原因：平台差异必须停留在 connector。

## 关键流程

`BiliBiliBot` 负责装配共享配置、数据、connector、service、tasker 和 channel。运行中消息从平台入口进入 core/service，再流向 tasker 或 message gateway；停机时由 `ResourceSupervisor` 分阶段停止入口、worker、channel、依赖和根 scope。

新增 core 能力时必须先判断它是“运行期骨架”还是“业务规则”。业务规则应下沉到 service，平台协议应下沉到 connector，周期工作应放入 tasker。

## 资源与生命周期

Core 是长期资源的协调层，不应隐藏拥有者。新增 channel、scope、worker、manager 或 registry 项时，必须明确：创建点、关闭点、所属分区、超时策略、监控暴露方式。

WebUI 配置热重载协调器由 `BiliBiliBot` 根生命周期持有，并登记在 `webui-config-hot-reload` 入口分区；停机时必须先拒收新保存、等待或取消当前 worker，并把所有未终态 job 标记失败。WebUI 自身 host/port/enabled 等运行面变化只由 Bot scope 在保存响应返回后延迟调度，避免当前 HTTP 请求被自己的 `stop()` 中断。

## 配置与数据

Core 可以读取已加载配置并把存储入口传递给 service/tasker，但不得直接写 YAML。涉及业务数据持久化时必须走 `BiliConfigManager` 或已有数据协调服务。

## 测试与验证

- 修改 `BiliBiliBot` 装配、channel、root scope 或停机顺序后，运行 core/resource 和 tasker 生命周期相关测试。
- 修改 `ResourceSupervisor` 分区或 strictness 后，同步检查 [`../architecture/invariants.md`](../architecture/invariants.md) 与 [`../operations/monitoring.md`](../operations/monitoring.md)。
- 修改 `TaskResourcePolicyRegistry` 后，运行 tasker 启动、停机和资源策略回归测试。

## 查询 checklist

- [ ] 是否已阅读根目录 `AGENTS.md` 与 `docs/AGENTS.md`？
- [ ] 是否确认查询对象属于生命周期、命令入口、channel、资源分区还是 tasker 策略？
- [ ] 是否同步阅读 [`architecture/invariants.md`](../architecture/invariants.md) 中停机、channel、tasker 和 client 相关不变量？
- [ ] 是否核对 [`operations/monitoring.md`](../operations/monitoring.md) 中对应观测项？

## 变更 checklist

- [ ] 是否保持 core 只做装配、协调和生命周期，不新增业务规则？
- [ ] 是否更新 `ResourceSupervisor` 相关分区时同步更新不变量和监控文档？
- [ ] 是否新增 tasker 或 worker 策略，并补齐 `TaskResourcePolicyRegistry`？
- [ ] 是否影响 channel 容量、关闭顺序或背压？若是，是否更新 [`modules/tasker.md`](tasker.md)？
- [ ] 是否运行 core/resource、tasker 生命周期和停机相关测试？

## 新建 checklist

- [ ] 新 core 类是否确实是运行期骨架，而不是 service、connector 或 tasker 职责？
- [ ] 新 registry、manager 或 supervisor 是否有紧邻注释说明职责边界？
- [ ] 新资源是否声明 strictness、关闭超时和所属分区？
- [ ] 新 channel 是否有容量、生产者、消费者和停机退出路径说明？
