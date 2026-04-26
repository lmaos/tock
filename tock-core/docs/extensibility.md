# 可扩展性与接口实现指南

Tock 不只是“一个带 Redis/Memory 两套实现的定时器”，它本质上也是一套**可替换基础设施组件的分布式定时器框架**。  
核心运行链路里的大部分关键节点都被抽象成接口，业务方或第三方可以按自己的存储、注册中心、消息系统、序列化规范重新实现。

## 1. 框架式扩展的核心思路

Tock 的默认运行链路可以概括成：

```text
ScheduleStore
    -> TockScheduler (Master)
    -> WorkerQueue
    -> TockWorker
    -> TaskScheduler
    -> JobExecutor
```

同时，整个链路还依赖：

- `TockRegister`：选主、节点状态、分布式状态存储
- `Serializer`：对象编解码

也就是说，Tock 的默认实现只是“开箱即用的一组内置组件”；**这些组件并不是唯一实现**。

## 2. 通过 `Config` 可直接替换的核心接口

下面这些接口都已经进入 `Config` 或 `Tock` 的真实装配链路，可以由外部重新实现后直接接入：

| `Config` 字段 | 核心接口 | 当前内置实现 | 作用 | 是否适合外部重写 |
| --- | --- | --- | --- | --- |
| `register` | `TockRegister` | `MemoryTockRegister`、`RedisTockRegister` | 注册中心、选主、节点状态、分布式运行时状态 | **是** |
| `scheduleStore` | `ScheduleStore` | `MemoryScheduleStore`、`RedisScheduleStore` | 存储 `ScheduleConfig`，供 Master 读取和刷新 | **是** |
| `workerQueue` | `WorkerQueue` | `MemoryPullableWorkerQueue`、`MemorySubscribableWorkerQueue`、`RedisSubscribableWorkerQueue` | Master 下发 `JobExecution`、Worker 消费任务 | **是** |
| `worker` | `TockWorker` | `DefaultTockWorker` | Worker 主逻辑：消费、等待、加锁、执行 | **是** |
| `scheduler` | `TockScheduler` | `EventDrivenCronScheduler` | Master 侧调度器 | **是** |
| `workerExecutor` | `TaskScheduler` | `ScheduledExecutorTaskScheduler`、`HighPrecisionWheelTaskScheduler` | Worker 本地延迟执行器 | **是** |
| `jobStore` | `JobStore` | `MemoryJobStore` | 延时任务存储；当前默认事件驱动热路径不强依赖 | **是** |
| `serializer` | `Serializer` | `JacksonSerializer`、`FastjsonSerializer`、`KryoSerializer`、`JavaSerializer` | 对象序列化/反序列化 | **是** |

补充说明：

1. 如果你自定义 `Serializer`，`Tock` 仍会在外层包一层 `VersionedSerializer`，保持当前版本兼容策略。
2. `WorkerQueue` 虽然最小接口只有 `push(...)`，但当前默认 `DefaultTockWorker` 实际要求它至少实现：
   - `SubscribableWorkerQueue`，或
   - `PullableWorkerQueue`

## 3. 每个接口分别负责什么

## 3.1 集群与注册中心层

### `TockRegister`

职责：

- 暴露 Master 实现：`getMaster()`
- 暴露当前节点：`getCurrentNode()`
- 查询节点 / 过期节点：`getNode()`、`getNods()`、`getExpiredNodes()`
- 提供节点属性、组属性、运行时状态的读写能力

这意味着一个自定义注册中心实现，不只是“存一下节点列表”，而是要同时承担：

1. **选主协调**
2. **节点元数据管理**
3. **Worker 执行锁 / 运行时状态存储**
4. **故障恢复所需的过期节点识别**

适合替换成：

- ZooKeeper / Etcd / Consul 注册中心
- 自研数据库注册中心
- 基于云服务的租约/选主组件

### `TockMaster`

职责：

- 执行选主
- 维护自己是否为 Master
- 在主身份变化时触发 `MasterListener`

通常它不会独立传进 `Config`，而是由 `TockRegister` 返回。  
所以如果你自定义 `TockRegister`，通常也需要同时实现自己的 `TockMaster`。

### `TockCurrentNode` / `TockNode`

职责：

- 节点 ID、状态
- 当前节点属性写入 / 读取
- 节点运行 / 停止监听

同样，这部分通常也由你的 `TockRegister` 一并提供。

### `MasterListener` / `NodeListener`

它们不是基础设施替换点，而是**生命周期事件钩子**：

- `MasterListener`
  - `onBecomeMaster()`
  - `onLoseMaster()`
- `NodeListener`
  - `onRunning()`
  - `onStopped()`

当前 `Tock` 内部就依赖这两个钩子来：

- Master 成功后启动调度器
- 当前节点 stopped 时收敛 Worker
- Worker 的实际恢复消费现在更依赖心跳首次建立 / 恢复，而不是单靠 `onRunning()`

## 3.2 调度配置与调度器层

### `ScheduleStore`

职责：

- `save` / `delete` / `get` / `getAll`
- 通过 `getGlobalVersion()` 暴露配置版本变化

这意味着自定义 `ScheduleStore` 不只是一个 KV 存储，还要满足：

- Master 可以在运行中感知配置变化
- 版本号语义明确

适合替换成：

- MySQL / PostgreSQL / TiDB
- MongoDB
- 配置中心

### `TockScheduler`

职责：

- 在 Master 身份下运行
- 从 `ScheduleStore` 读取配置
- 计算 `nextFireTime`
- 下发 `JobExecution`

当前内置的是 `EventDrivenCronScheduler`。  
如果你想做不同的调度策略，也可以自己实现，例如：

- 基于数据库轮询的调度器
- 基于 Quartz 风格 trigger 的调度器
- 基于更大粒度时间轮的 Master 调度器

### `JobStore`

职责：

- 存放未来待执行的 `JobExecution`
- 按时间取出到期任务
- 支持删除 / 判断 pending

当前默认事件驱动热路径并不强依赖它，但接口已经保留在公共 API 中，适合作为：

- 自定义调度器的延时存储
- 更重持久化语义的 future task store

## 3.3 Worker 与执行层

### `WorkerQueue`

最小职责只有一件事：

- 把 `JobExecution` 推给某个 `workerGroup`

但如果你想复用内置 `DefaultTockWorker`，则还需要实现下面两种消费模型之一。

### `SubscribableWorkerQueue`

职责：

- `subscribe(workerGroup, consumer)`
- `unsubscribe(workerGroup)`

适合：

- Redis List / Stream
- Kafka / Pulsar / RocketMQ
- 任何支持“异步推送到消费者”的队列

### `PullableWorkerQueue`

职责：

- `poll(workerGroup, timeoutMs)`

适合：

- 本地阻塞队列
- 数据库轮询队列
- 基于 `BRPOP` / `BLPOP` 以外的拉模式队列

### `TockWorker`

职责：

- 加入 / 离开 `workerGroup`
- 消费 `JobExecution`
- 用 `TaskScheduler` 做本地等待
- 在真正执行前做分布式互斥保护

当前内置实现是 `DefaultTockWorker`。  
如果你要实现更激进的策略，例如：

- 本地批量合并等待
- 特殊的幂等 / 去重策略
- 自定义重试与恢复语义

可以替换整个 Worker。

### `TaskScheduler`

职责：

- `schedule(...)`：延迟执行
- `submit(...)`：立即执行

当前内置两种本地执行器：

- `ScheduledExecutorTaskScheduler`
- `HighPrecisionWheelTaskScheduler`

这是最直接的“精度替换点”：

- 你可以接入别的高精度时间轮
- 可以接入 Netty `HashedWheelTimer`
- 可以接入 JNI / OS timer / FPGA / 特殊硬件时钟驱动

### `JobExecutor`

这是业务层最常见的扩展点：

- Worker 最终执行的业务回调
- 接口非常小：`execute(JobContext)`

### `JobRegistry`

职责：

- 保存 `jobId -> JobExecutor`
- 提供注册、查询、移除

默认实现是 `DefaultJobRegistry`。  
如果你的业务希望：

- 从 Spring 容器自动查找 Bean
- 从插件系统动态装卸 Job
- 从脚本引擎或远程函数中心解析 Job

就可以替换 `JobRegistry`。

## 3.4 序列化与公共契约层

### `Serializer`

职责：

- 对对象做序列化 / 反序列化
- 暴露版本号

适合替换成：

- Protobuf
- Avro
- MsgPack
- 公司内部统一协议

### `Lifecycle`

公共生命周期契约：

- `start(TockContext)`
- `stop()`
- `isRunning()`

任何需要被 `Tock` 自动启动 / 停止的组件，都可以实现它。

### `TockContextAware`

公共上下文注入契约：

- `setTockContext(TockContext)`

如果你的自定义组件需要访问：

- `register`
- `scheduleStore`
- `workerExecutor`
- `timeSource`

等上下文对象，可以实现它，`Tock` 会在启动前注入。

## 4. 一般怎么扩展

## 4.1 只替换一层

最常见的做法，是保持大部分默认实现，只替换一层：

- 自定义 `ScheduleStore`
- 自定义 `WorkerQueue`
- 自定义 `TaskScheduler`
- 自定义 `TimeProvider` 或 `TimeSynchronizer` (统一时间源)
```X
TimeProvider 被 DefaultTimeSynchronizer依赖，如果 直接实现TimeSynchronizer，则不需要实现 TimeProvider 了。
TimeProvider 默认值：SystemTimeProvider，直接调用 System.currentTimeMillis()，不做额外处理。
```

这类扩展成本最低，也最符合框架设计。

## 4.2 自定义一整套基础设施

如果你要接到新的分布式基础设施，比如：

- Etcd 做选主和状态
- MySQL 做配置存储
- Kafka 做 Worker 队列
- NTP 做统一时间源

可以这样理解组合关系：

1. 先实现 `TockRegister`
   - 并内部提供 `TockMaster` / `TockCurrentNode` / `TockNode`
2. 再实现 `ScheduleStore`
3. 再实现 `WorkerQueue`
   - 若要复用 `DefaultTockWorker`，最好实现 `SubscribableWorkerQueue` 或 `PullableWorkerQueue`
4. 按需实现其他基础组件
5. 通过 `Config.builder()` 装配进去

## 5. 一个最小的自定义装配示例

```java
Config config = Config.builder()
        .register(new CustomEtcdRegister(...))
        .scheduleStore(new CustomDbScheduleStore(...))
        .workerQueue(new CustomKafkaWorkerQueue(...))
        .worker(new DefaultTockWorker())
        .scheduler(new EventDrivenCronScheduler())
        .workerExecutor(new HighPrecisionWheelTaskScheduler("custom-hp"))
        .serializer(new CustomSerializer())
        .build();
```

这也是为什么可以把 Tock 理解成：

> **一套默认实现完整、但边界高度开放的分布式定时器框架。**

## 6. 当前最值得优先阅读的配套文档

- [配置指南](configuration.md)：看哪些接口已经进入 `Config`
- [架构与设计](architecture.md)：看这些接口在真实运行链路里的位置
- [API 说明](api.md)：看 `Tock` 对外暴露的编程入口
- [当前性能与精度报告](../PERFORMANCE.md)：看当前默认实现的实际能力
