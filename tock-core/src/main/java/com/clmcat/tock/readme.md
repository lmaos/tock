分布式定时器的实现。
```X
com.clmcat.tock
├── Tock.java                 # 全局门面类，单例入口，负责组装配置、启动所有组件
├── Config.java               # 配置类（Builder模式），管理所有可替换组件（注册中心、存储、队列、线程池等）
├── TockContext.java          # 上下文对象，持有所有组件实例，供生命周期和依赖注入使用
├── TockContextAware.java     # 接口，实现该接口的组件可注入 TockContext
├── Lifecycle.java            # 生命周期接口（start/stop/isRunning）
│
├── job/                      # 任务执行器相关
│   ├── JobExecutor.java      # 业务逻辑接口，用户实现 execute(JobContext)
│   ├── JobContext.java       # 执行上下文（scheduleId, jobId, 计划时间, 实际时间, 参数等）
│   ├── JobRegistry.java      # 注册中心，存储 jobId -> JobExecutor 映射
│   └── DefaultJobRegistry.java # 内存版实现
│
├── schedule/                 # 调度配置相关
│   ├── ScheduleConfig.java   # 不可变配置对象（scheduleId, jobId, cron/fixedDelay, workerGroup, 时区, params, enabled）
│   ├── ScheduleStore.java    # 配置存储接口（save/delete/get/getAll/getGlobalVersion）
│   └── memory/               # 内存实现（用于测试）
│       └── MemoryScheduleStore.java
│   └── redis/               # redis实现（分布式共享）
│       └── RedisScheduleStore.java
│
├── scheduler/                # 调度器实现
│   ├── TockScheduler.java    # 调度器接口（start/stop/refreshSchedules/tick/isRunning）
│   ├── EventDrivenCronScheduler.java  # 事件驱动调度器（每个 scheduleId 一个 JVM 定时器，提前唤醒推送）
│   └── CronCalculator.java   # Cron 表达式解析和计算（自研，位于 cron 子包，但实际放在 cron 包）
│
├── cron/                     # Cron 工具（自研）
│   ├── CronCalculator.java   # 核心计算类，给定基准时间计算下次触发时间
│   └── CronCalculators.java  # 工厂类，按 ZoneId 缓存 Calculator 实例
│
├── store/                    # 延时任务存储（原设计用于轮询，当前事件驱动下未使用，保留接口）
│   ├── JobStore.java         # 延时队列接口（add/pollDueTasks/remove/hasPending/size）
│   ├── JobExecution.java     # 任务执行元数据（executionId, scheduleId, jobId, nextFireTime, workerGroup, params）
│   └── memory/               # 内存实现（仅测试用）
│       └── MemoryJobStore.java
│
├── worker/                   # 工作者（任务执行端）
│   ├── TockWorker.java       # Worker 接口（start/stop/joinGroup/leaveGroup/getGroups/isRunning）
│   ├── DefaultTockWorker.java # 默认实现：支持订阅和拉取两种队列，本地精确计时，分布式锁
│   ├── WorkerQueue.java      # 队列接口（push）
│   ├── PullableWorkerQueue.java   # 拉模式队列（poll）
│   ├── SubscribableWorkerQueue.java # 订阅模式队列（subscribe/unsubscribe）
│   └── memory/               # 内存版队列实现
│       ├── MemoryPullableWorkerQueue.java
│       └── MemorySubscribableWorkerQueue.java
│   └── redis/               # redis版队列实现
│       └── RedisSubscribableWorkerQueue.java
│
├── registry/                 # 服务注册与发现（选主、节点管理、分布式状态）
│   ├── TockRegister.java     # 注册中心接口（选主、节点查询、过期节点、运行时状态、组属性锁等）
│   ├── TockMaster.java       # 选主接口（isMaster/addListener/removeListener/start/stop/getMasterName）
│   ├── TockNode.java         # 节点只读接口（getId/getStatus/属性读写等）
│   ├── TockCurrentNode.java  # 当前节点接口（继承 TockNode，增加生命周期和监听器）
│   ├── MasterListener.java   # 选主监听器（onBecomeMaster/onLoseMaster）
│   ├── NodeListener.java     # 节点状态监听器（onRunning/onStopped）
│   ├── memory/               # 内存版实现（单机测试）
│   │   ├── MemoryTockRegister.java
│   │   ├── MemoryTockMaster.java
│   │   ├── MemoryTockNode.java
│   │   └── MemoryManager.java   # 共享内存管理器（存放 masterMap, nodeMap, groupAttr, runtimeState）
│   └── redis/                # Redis 版实现（分布式）
│       ├── RedisTockRegister.java   # 实现 TimeProvider，整合 master 和 node
│       ├── RedisTockMaster.java     # 基于 Redis SETNX 的选主，Lua 脚本续期
│       ├── RedisTockNode.java       # 节点只读代理（非当前节点）
│       └── RedisTockCurrentNode.java # 当前节点实现，心跳、生命周期、监听器
│
├── serialize/                # 序列化
│   ├── Serializer.java       # 序列化接口（serialize/deserialize/version）
│   ├── JavaSerializer.java   # Java 原生序列化
│   ├── JacksonSerializer.java # Jackson JSON（可选）
│   ├── FastjsonSerializer.java # Fastjson（可选）
│   ├── KryoSerializer.java   # Kryo（可选）
│   ├── SerializerFactory.java # 自动检测 classpath 中的库，返回最优实现
│   └── VersionedSerializer.java # 带版本头的包装器，支持协议平滑升级
│
├── time/                     # 时间同步
│   ├── TimeSource.java       # 基础时间源接口（currentTimeMillis）
│   ├── TimeProvider.java     # 原始时间提供者（供同步器采样）
│   ├── SystemTimeProvider.java # 本地系统时间
│   ├── TimeSynchronizer.java # 时间同步器（继承 TimeSource，提供 offset/start/stop）
│   └── DefaultTimeSynchronizer.java # 默认实现：采样远程时间源，RTT 中点补偿，单调递增
│
│
└── money/                    # 内存测试辅助（如 MemoryManager，实际应为 memory 包, 本作者觉得内存就是钱包就是财富）
    └── MemoryManager.java

```
## 包与类说明表

| 包/类 | 用途 | 在整体流程中的角色 |
|-------|------|-------------------|
| **`com.clmcat.tock.Tock`** | 全局门面类，对外统一入口。负责组装配置、启动所有组件（选主、调度器、Worker）、提供注册Job和配置调度的API。 | 用户代码的唯一交互点。内部持有`ScheduleStore`、`JobRegistry`、`TockMaster`、`TockScheduler`、`TockWorker`等实例，并协调它们的生命周期。 |
| **`com.clmcat.tock.Config`** | 配置对象，用于传入Redis地址、选主超时、默认WorkerGroup等参数。 | 在调用`Tock.configure(Config)`时使用，决定各组件实现类的具体行为（如连接信息、线程池大小）。 |
| **`job.JobExecutor`** | 用户实现的业务逻辑接口，包含`execute(JobContext)`方法。 | Worker执行任务时调用的回调。 |
| **`job.JobRegistry`** | 注册中心，存储`jobId` → `JobExecutor`的映射。 | 供Worker在执行任务时根据`jobId`找到对应的业务逻辑。 |
| **`job.DefaultJobRegistry`** | `JobRegistry`的简单内存实现，使用`ConcurrentHashMap`。 | 默认实现，适用于单机或分布式（因为每个节点都有自己的一份Job代码，不需要共享）。 |
| **`schedule.ScheduleConfig`** | 不可变配置对象，包含`scheduleId`、`jobId`、cron/fixedDelay、`workerGroup`、开关、参数等。 | 描述“什么时间、由谁执行、执行哪个Job”。存储在`ScheduleStore`中，Master调度器读取它来生成定时任务。 |
| **`schedule.ScheduleStore`** | 调度配置的存储接口，支持增删改查以及获取全局版本号。 | 存放所有`ScheduleConfig`。Master通过它获取配置并监听变化（轮询版本号或订阅事件）。后期Web管理端通过它动态修改配置。 |
| **`schedule.RedisScheduleStore`** | `ScheduleStore`的Redis实现，使用Hash存储配置，用String存储全局版本号。 | 分布式环境下共享配置。支持原子递增版本号，便于Master感知变更。 |
| **`schedule.MemoryScheduleStore`** | `ScheduleStore`的内存实现，用于单机测试或开发阶段。 | 快速验证调度逻辑，不依赖外部存储。 |
| **`master.TockMaster`** | 选主接口，提供`isMaster()`和阻塞式`selectMaster(timeout)`。 | 决定哪个节点成为Master（唯一调度者）。所有节点启动时都会尝试选主，只有一个成功。 |
| **`master.RedisTockMaster`** | 基于Redis的选主实现，通常使用`SET NX EX`或Redlock。 | 分布式环境下常用的轻量级选主方案。 |
| **`scheduler.TockScheduler`** | 调度器接口，由Master节点启动。负责：拉取`ScheduleConfig`、计算下次触发时间、将任务写入`JobStore`、轮询到期任务并推给Worker队列。 | 分布式定时器的核心大脑。只在Master节点运行。 |
| **`scheduler.CronScheduler`** | `TockScheduler`的实现，支持Cron表达式和固定延迟。 | 具体实现类，内部使用`CronExpression`或`ScheduledThreadPoolExecutor`计算下次时间。 |
| **`worker.TockWorker`** | 工作者接口，在所有节点（包括Master）上运行。负责订阅Worker队列，抢占任务，调用`JobExecutor`执行。 | 分布式执行器。多个Worker竞争消费同一个队列，实现负载均衡。 |
| **`worker.DefaultTockWorker`** | `TockWorker`的默认实现，使用Redis List的`BLPOP`阻塞获取任务。 | 典型实现：每个Worker属于一个或多个`workerGroup`，只消费自己组对应的队列。 |
| **`store.JobStore`** | 待执行任务的存储接口。支持：添加任务（带score）、原子获取到期任务、删除任务。 | 用于Master存放“未来某个时间点需要执行的任务”，通常按时间排序（如Redis ZSet）。Worker执行时一般不直接操作JobStore，而是通过队列。 |
| **`store.RedisJobStore`** | `JobStore`的Redis ZSet实现，使用`ZADD`添加，`ZRANGEBYSCORE` + `ZREM`原子获取到期任务。 | 分布式环境下可靠的延时队列。 |

---

## 整体数据流向（简要）

1. **用户**：`Tock.registerJob()` → 存入`JobRegistry`  
   `Tock.schedule()` → 存入`ScheduleStore`  
   `Tock.start()` → 启动选主 + 启动Worker

2. **Master节点**（选主成功）：  
   `TockScheduler` 循环：
    - 从`ScheduleStore`获取所有`ScheduleConfig`
    - 计算下次触发时间 → 调用`JobStore.add()`
    - 轮询`JobStore`获取到期任务 → 按`workerGroup`推入Redis List（Worker队列）

3. **所有节点（Worker）**：  
   `TockWorker` 循环：
    - `BLPOP` 自己所属组的队列
    - 获取任务 → 从`JobRegistry`找`JobExecutor` → 执行

4. **动态配置变更**：  
   Web管理端 → 修改`ScheduleStore`（版本号递增）  
   Master轮询发现版本变化 → 重新加载配置 → 调整后续调度

---

## 重点思考的流程关键点

- **选主与调度器的启动时机**：只有Master节点才启动`TockScheduler`，其他节点只跑Worker。如何确保Master挂掉后，新Master能接替调度？（`TockMaster.selectMaster`阻塞等待）
- **任务执行的可靠性**：Worker拿到任务后如果执行失败，是否需要重试？是否要防止任务重复执行？（建议先不做复杂处理，失败记录日志并丢弃，后续再扩展）
- **固定延迟任务**：是Master在每次触发后立即计算下次时间并重新入JobStore，还是等Worker执行完成后再通知Master？（前者简单，后者精确）
- **Worker队列的组隔离**：每个`workerGroup`独立队列，Worker启动时声明自己所属的组，订阅对应队列。

如果你对某个类的具体方法签名或实现细节有疑问，我可以直接写出代码骨架。