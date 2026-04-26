# 配置指南

本文对应 `com.clmcat.tock.Config` 的当前实现，优先说明**真实存在且已接入运行链路**的配置项。

如果你想从“哪些字段可以替换、每个接口负责什么”的角度理解 Tock，先看 [extensibility.md](extensibility.md)。

## `Config` 字段总览

| 字段 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `scheduleStore` | 是 | 无 | 调度配置存储；Master 从这里读取 `ScheduleConfig` |
| `register` | 是 | 无 | 注册中心与选主实现；Redis 实现同时也是 `TimeProvider` |
| `workerQueue` | 是 | 无 | Master 推送执行计划、Worker 消费任务的队列 |
| `jobStore` | 否 | `null` | 预留扩展点；当前默认事件驱动调度链路不依赖它 |
| `serializer` | 否 | 自动选择 | 自动顺序：Jackson > Fastjson > Kryo > Java 序列化 |
| `worker` | 否 | `DefaultTockWorker` | Worker 实现 |
| `scheduler` | 否 | `EventDrivenCronScheduler` | 当前内置调度器 |
| `schedulerExecutor` | 否 | 2 线程 `ScheduledExecutorService` | Master 侧定时任务线程池 |
| `workerExecutor` | 否 | `ScheduledExecutorTaskScheduler("tock-worker-thread")` | Worker 本地延迟执行器 |
| `consumerExecutor` | 否 | `cachedThreadPool` | 队列消费与分发线程池 |
| `manageThreadPools` | 否 | `true` | `shutdown()` 时是否由 Tock 关闭线程池 |
| `pendingExecutionRecoveryEnabled` | 否 | `false` | 在 `doExecuteJob` 前记录短生命周期 pending 标记，便于故障恢复 |

上表里除了线程池和布尔开关，绝大多数字段本身都是接口，因此可以由业务侧替换为自定义实现。

补充两点常见约定：

- 时间链路默认会走 `TimeSynchronizer`，系统时间源也会返回统一的快照对象，业务线程可以直接用 `currentTimeMillis()`。
- 心跳链路默认由内置 `HeartbeatReporter`/`HealthMaintainer` 接管，Worker 会在心跳首次建立后再恢复消费。

## 推荐配置

## 1. 本地开发 / Demo

```java
Config config = Config.builder()
        .register(new MemoryTockRegister("demo", MemoryManager.create()))
        .scheduleStore(MemoryScheduleStore.create())
        .workerQueue(MemoryPullableWorkerQueue.create())
        .workerExecutor(TaskSchedulers.highPrecision("demo-worker"))
        .build();
```

适合：

- 单元测试
- API 验证
- 本地快速体验

## 2. Redis 生产模式

```java
JedisPool jedisPool = new JedisPool("127.0.0.1", 6379);
String namespace = "prod";

Config config = Config.builder()
        .register(new RedisTockRegister(namespace, jedisPool))
        .scheduleStore(RedisScheduleStore.create(namespace, jedisPool))
        .workerQueue(RedisSubscribableWorkerQueue.create(namespace, jedisPool))
        .build();
```

特点：

- `RedisScheduleStore` 通过全局版本号驱动调度刷新
- `RedisSubscribableWorkerQueue` 使用 Redis List + `BLPOP`

## 3. 需要更小本地等待误差时

默认 `workerExecutor` 是 `ScheduledExecutorTaskScheduler`。  
如果你更关注 Worker 本地定时的边界精度，可显式切换到高精度时间轮：

```java
Config config = Config.builder()
        // 省略 register / scheduleStore / workerQueue
        .workerExecutor(TaskSchedulers.highPrecision("hp-worker"))
        .build();
```

说明：

- `TaskSchedulers.highPrecision(...)` 返回 `HighPrecisionWheelTaskScheduler`
- 该执行器内部使用 1ms tick、分段 park + 最终自旋
- 如果你已经显式调用 `setAdvanceNanos(...)`，Tock 不会覆盖你的自定义值
- 是否优于默认调度器，仍建议以你自己的目标环境复跑 `PerformanceBenchmarkMain` 或 `RedisHighPrecisionStudyMain`

## 调度器选择

当前代码内置的调度器是 `EventDrivenCronScheduler`，一般不需要手动替换。

它的特点：

- 每个 `scheduleId` 对应一个 JVM 定时任务
- 大延迟任务默认提前 1000ms 唤醒，小延迟任务提前 15ms 唤醒
- 唤醒后只做“推送执行计划”，真正的准点执行由 Worker 本地执行器负责

如果你想优化精度，优先调 `workerExecutor`，而不是替换 `scheduler`。

## Redis 连接池建议

Tock Core 直接接收外部 `JedisPool`，因此连接池策略由业务侧控制。以下是起步建议，而不是框架内置默认值：

| 场景 | 建议 |
| --- | --- |
| 单实例开发环境 | `maxTotal 8~16`，`maxIdle 4~8` 即可 |
| 多 Worker Group | 让 `maxTotal` 至少覆盖峰值并发消费线程数，再预留 20%~30% 余量 |
| 低延迟场景 | 打开连接有效性检查，避免借出坏连接导致突发抖动 |

如果你已经有成熟的 Redis 连接治理规范，优先沿用业务统一规范。

## 故障恢复相关开关

`pendingExecutionRecoveryEnabled=false` 是当前默认值。

开启后：

- Worker 在真正进入 `doExecuteJob()` 前，会把待执行计划记录为 `pending.<group>.<schedule>.<execution>`
- 节点退出或组退订时，会尝试重排这些未真正开始执行的计划

适合：

- 更关心节点在“已收到计划但未真正执行”窗口内的恢复能力

代价：

- 更多节点属性写入
- 运维排查时会看到额外的 pending 标记
