# Tock

Tock 是一个面向 JVM 的分布式定时器内核。可以先把它理解成：**Master 负责出计划，Worker 负责到点执行**，中间的结果会提前入队，最后一跳交给 Worker 本机定时器完成。

这样拆开以后，几件事会更顺手：

- 调度和执行自然分开
- 网络抖动主要影响任务到达时刻，不会直接放大成业务执行偏差
- 默认行为比较轻，复杂场景也能慢慢加配置

## 运行方式

| 模式 | 适用场景 | 关键组件 |
| --- | --- | --- |
| Memory | 本地开发、单进程验证、单元测试 | `MemoryTockRegister` + `MemoryScheduleStore` + `MemoryPullableWorkerQueue` |
| Redis | 多节点部署、共享状态、真实集群 | `RedisTockRegister` + `RedisScheduleStore` + `RedisSubscribableWorkerQueue` |

## 核心链路

```text
Config -> Tock -> Register / ScheduleStore / WorkerQueue / TaskScheduler
       -> TockScheduler -> JobExecution -> WorkerQueue -> TockWorker -> JobExecutor
```

补充两条支撑链路：

- `time` 负责统一调度和 Worker 的时间基准，系统时间也会走统一的快照形态
- `health` 负责健康、心跳、过期通知，以及健康地址的更新
- `serialize` 负责 Redis 组件之间的对象传输
- `utils` 负责生命周期排序、引用保护、网络探测

## 设计风格

1. **职责分离**：`Tock` 只做门面和生命周期编排，不直接承载调度逻辑
2. **通过 context 传递依赖**：模块之间不互相 new，统一从 `TockContext` 拿自己需要的东西
3. **时间统一**：默认直接用服务器时间；如果配置时间同步规则，再由 `time` 模块统一时间基准
4. **健康只管健康**：健康链路不碰业务执行，只负责发现、心跳和过期通知
5. **Memory / Redis 同语义**：换存储，不换接口语义
6. **调度提前，Worker 本地执行**：Master 提前把任务送进队列，Worker 到点后在本机执行，兼顾精度和效率

## 重点解决什么

- **分布式一致调度**：只有一个 Master 负责出计划
- **执行精度**：Worker 本机定时器负责最后一跳，减少跨节点通信对精度的影响
- **容错**：提前入队给网络延迟和抖动留出余量
- **可替换性**：注册中心、调度器、队列、执行器、时间源、序列化器都可以替换

## 性能和验证

当前 benchmark 结果：

- **内存 Worker 严格 A/B**：`high-precision` 的 `worker-chain avg abs skew` 为 **0.28ms**，默认调度器为 **4.31ms**
- **Windows 本机 Redis 精度**：`redis-high-precision` 为 **0.44ms**，`redis-default-worker` 为 **8.16ms**
- **Windows 本机吞吐**：内存模式约 **5.5k ~ 5.7k tasks/s**，Redis 模式约 **4.1k ~ 4.2k tasks/s**

这些数据说明：

- 高精度执行器在本机链路里确实有价值
- Redis 模式的主要成本更偏向消息通道和序列化，而不是 Worker 本地执行器本身
- 当前实现已经把精度、吞吐和可维护性放在一个比较平衡的位置

## 文档入口

- [入门使用](tock-core/README.md)
- [核心模块设计](tock-core/docs/design/index.md)
- [架构说明](tock-core/docs/architecture.md)
- [扩展性说明](tock-core/docs/extensibility.md)
- [配置指南](tock-core/docs/configuration.md)
- [运维手册](tock-core/docs/operations.md)
- [性能报告](tock-core/PERFORMANCE.md)

## 源码入口

- 核心模块：`tock-core`
- 示例/演示：`tock-demo`

如果你想直接看“模块怎么拆、谁依赖谁、为什么这么设计”，先读 `tock-core/docs/design/index.md`。
