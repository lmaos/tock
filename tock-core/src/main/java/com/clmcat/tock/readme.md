# Tock 分布式定时器架构文档

开发时的设计思路。

## 项目包结构

```
com.clmcat.tock
├── Tock.java                 # 全局门面类，单例入口，负责组装配置、启动所有组件
├── Config.java               # 配置类（Builder模式），管理所有可替换组件（注册中心、存储、队列、线程池等）
├── TockContext.java          # 上下文对象，持有所有组件实例，供生命周期和依赖注入使用
├── TockContextAware.java     # 接口，实现该接口的组件可注入 TockContext
├── Lifecycle.java            # 生命周期接口（init/start/stop/isStarted）
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
│   ├── memory/
│   │   └── MemoryScheduleStore.java
│   └── redis/
│       └── RedisScheduleStore.java
│
├── scheduler/                # 调度器实现
│   ├── TockScheduler.java    # 调度器接口（start/stop/refreshSchedules/tick/isRunning）
│   └── EventDrivenCronScheduler.java  # 事件驱动调度器（每个 scheduleId 一个 JVM 定时器，提前唤醒推送）
│
├── cron/                     # Cron 工具（自研）
│   ├── CronCalculator.java   # 核心计算类，给定基准时间计算下次触发时间
│   └── CronCalculators.java  # 工厂类，按 ZoneId 缓存 Calculator 实例
│
├── store/                    # 延时任务存储（原设计用于轮询，当前事件驱动下未使用，保留接口）
│   ├── JobStore.java         # 延时队列接口（add/pollDueTasks/remove/hasPending/size）
│   ├── JobExecution.java     # 任务执行元数据（executionId, scheduleId, jobId, nextFireTime, workerGroup, params）
│   └── memory/
│       └── MemoryJobStore.java
│
├── worker/                   # 工作者（任务执行端）
│   ├── TockWorker.java       # Worker 接口（start/stop/joinGroup/leaveGroup/getGroups/isRunning）
│   ├── DefaultTockWorker.java # 默认实现：支持订阅和拉取两种队列，本地精确计时，分布式锁
│   ├── WorkerQueue.java      # 队列接口（push）
│   ├── PullableWorkerQueue.java   # 拉模式队列（poll）
│   ├── SubscribableWorkerQueue.java # 订阅模式队列（subscribe/unsubscribe）
│   ├── memory/
│   │   ├── MemoryPullableWorkerQueue.java
│   │   └── MemorySubscribableWorkerQueue.java
│   ├── redis/
│   │   └── RedisSubscribableWorkerQueue.java
│   └── scheduler/            # Worker 端本地定时器
│       ├── TaskScheduler.java              # 本地定时器接口（schedule/submit）
│       ├── HighPrecisionWheelTaskScheduler.java # 高精度时间轮实现
│       ├── ScheduledExecutorTaskScheduler.java  # 基于 ScheduledExecutorService 的默认实现
│       └── TaskSchedulers.java             # 工厂类，方便创建两种调度器
│
├── registry/                 # 服务注册与发现（选主、节点管理、分布式状态）
│   ├── TockRegister.java     # 注册中心接口（选主、节点查询、过期节点、运行时状态、组属性锁等）
│   ├── TockMaster.java       # 选主接口（isMaster/addListener/removeListener/start/stop/getMasterName）
│   ├── TockNode.java         # 节点只读接口（getId/getStatus/属性读写等）
│   ├── TockCurrentNode.java  # 当前节点接口（继承 TockNode，增加生命周期和监听器）
│   ├── MasterListener.java   # 选主监听器（onBecomeMaster/onLoseMaster）
│   ├── NodeListener.java     # 节点状态监听器（onRunning/onStopped）
│   ├── memory/
│   │   ├── MemoryTockRegister.java
│   │   ├── MemoryTockMaster.java
│   │   ├── MemoryTockNode.java
│   │   └── MemoryManager.java
│   └── redis/
│       ├── RedisTockRegister.java   # 实现 TimeProvider，整合 master 和 node
│       ├── RedisTockMaster.java     # 基于 Redis SETNX 的选主，Lua 脚本续期
│       ├── RedisTockNode.java       # 节点只读代理（非当前节点）
│       └── RedisTockCurrentNode.java # 当前节点实现，心跳、生命周期、监听器
│
├── health/                   # 健康检查与心跳管理
│   ├── HealthMaintainer.java     # 健康维护器接口（Master 端）
│   ├── DefaultHealthMaintainer.java # 默认实现，使用 HealthServer 接收心跳，HeartbeatManager 判定超时
│   ├── HeartbeatReporter.java    # Worker 端心跳上报接口
│   ├── DefaultHeartbeatReporter.java # 默认实现，通过 HealthClientManager 连接 Master
│   ├── HealthHost.java           # 健康服务地址描述
│   ├── server/
│   │   ├── HealthServer.java     # 基于 NIO 的健康检查服务端
│   │   └── HeartbeatManager.java # 心跳数据管理器（双 Map 结构，支持超时快照和原子删除）
│   └── client/               # 健康检查客户端（Worker 端）
│       ├── HealthClient.java               # 底层 TCP 客户端，单连接读写，支持请求/响应
│       ├── HealthClientManager.java       # 连接管理器，自动地址发现、重连维护、心跳上报
│       ├── HealthRequest.java             # 健康检查请求协议定义
│       ├── HealthResponse.java            # 健康检查响应协议定义
│       └── MasterChangeListener.java      # Master 变更监听器接口
│
├── time/                     # 时间同步
│   ├── TimeProvider.java     # 原始时间提供者（供同步器采样）
│   ├── SystemTimeProvider.java # 本地系统时间
│   ├── TimeSynchronizer.java # 时间同步器接口
│   ├── DefaultTimeSynchronizer.java # 默认实现：采样远程时间源，RTT 中点补偿，单调递增
│   └── HealthTimeProvider.java   # 基于 Master 健康服务的时间提供者
│
├── serialize/                # 序列化
│   ├── Serializer.java       # 序列化接口（serialize/deserialize/version）
│   ├── JavaSerializer.java   # Java 原生序列化
│   ├── JacksonSerializer.java
│   ├── FastjsonSerializer.java
│   ├── KryoSerializer.java
│   ├── SerializerFactory.java # 自动检测 classpath 中的库，返回最优实现
│   └── VersionedSerializer.java # 带版本头的包装器，支持协议平滑升级
│
├── builders/                 # 快速配置构建器
│   ├── MemoryConfigBuilder.java  # 内存模式配置构建
│   └── RedisConfigBuilder.java   # Redis 模式配置构建
│
└── utils/                    # 工具类
    ├── NetworkUtils.java     # 网络工具（获取本机IP，探测可达地址）
    ├── LifecycleSupport.java # 生命周期自动排序工具
    └── ReferenceSupport.java # 组件实例引用唯一性校验
```

## 核心组件说明

| 组件 | 用途 | 角色 |
|------|------|------|
| **Tock** | 全局门面 | 组装配置、启动所有组件、提供注册Job和调度的API |
| **Config** | 配置对象 | 管理所有可替换组件，构建 Tock 实例 |
| **ScheduleStore** | 调度配置存储 | Master 读取配置，Web 管理端修改配置 |
| **TockMaster** | 选主接口 | 保证只有一个 Master 运行调度器 |
| **EventDrivenCronScheduler** | 事件驱动调度器 | 每个配置独立定时器，提前唤醒生成任务 |
| **CronCalculator** | Cron 计算 | 计算下次触发时间 |
| **JobRegistry** | 任务执行器注册 | 根据 jobId 查找 JobExecutor |
| **DefaultTockWorker** | Worker 实现 | 订阅/拉取队列，本地高精度计时，分布式锁执行 |
| **WorkerQueue** | 任务队列 | Master 推送，Worker 抢占 |
| **TockRegister** | 注册中心 | 节点管理、心跳、分布式状态 |
| **DefaultTimeSynchronizer** | 时间同步器 | 采样远程时间，计算偏移，单调递增 |
| **HealthTimeProvider** | 基于 Master 的时间提供者 | 通过健康通道获取 Master 时间 |
| **DefaultHealthMaintainer** | Master 端健康维护器 | 接收心跳，判定超时，通知监听器 |
| **DefaultHeartbeatReporter** | Worker 端心跳上报器 | 定时向 Master 发送心跳 |
| **HealthServer** | 健康检查服务端（NIO） | 监听 Worker 连接，处理心跳/时间请求 |
| **HeartbeatManager** | 心跳数据管理器 | 双 Map 结构，支持超时快照和原子删除 |
| **HighPrecisionWheelTaskScheduler** | 高精度时间轮 | 亚毫秒级本地等待，替代 ScheduledExecutorService |
| **HealthClient** | TCP 客户端 | 单连接读写，上报心跳/获取时间 |
| **HealthClientManager** | 连接管理器 | 地址发现、自动重连、Master 切换感知 |
| **MasterChangeListener** | Master 变更监听 | 当 Master 切换时回调，触发时间强制对齐 |
| **TaskScheduler** | 本地定时器接口 | 支持纳秒级延迟调度 |
| **ScheduledExecutorTaskScheduler** | 标准线程池定时器 | 默认实现或降级选项 |
| **LifecycleSupport** | 生命周期自动排序工具 | 通过反射按依赖深度排序 |
| **NetworkUtils** | 网络工具 | 获取本机IP、探测可达地址 |
| **MemoryConfigBuilder / RedisConfigBuilder** | 快速构建配置 | 简化内存/Redis 模式启动 |

## 整体数据流

1. **用户配置**：`Tock.registerJob()` 存入 `JobRegistry`；`Tock.addSchedule()` 存入 `ScheduleStore`
2. **启动**：`Tock.start()` → 选主 → 启动调度器（Master） / Worker（所有节点）
3. **调度循环**（Master）：
    - 从 `ScheduleStore` 加载配置
    - 根据 Cron 或固定延迟计算下次触发时间 `fireTime`
    - 提前量唤醒（大延迟 1000ms，小延迟 15ms）
    - 生成 `JobExecution` 并推送到 `WorkerQueue`
4. **执行循环**（Worker）：
    - 订阅/拉取队列获取 `JobExecution`
    - 计算本地延迟 `delay = fireTime - now`
    - 使用高精度时间轮在本地等待至到期
    - 获取分布式锁（`setGroupAttributeIfAbsent`）
    - 调用 `JobExecutor.execute(JobContext)`
    - 释放锁和节点属性
5. **动态配置更新**：
    - Master 定期轮询 `ScheduleStore` 版本号
    - 发现变更后重新加载配置，只重建变化的定时器
6. **故障处理**：
    - Master 宕机 → 新节点选主，`EventDrivenCronScheduler` 重新加载配置并开始调度
    - Worker 宕机 → Master 健康检查判定过期 → 清理其持有的分布式锁 → 未完成任务重新入队
    - Worker 心跳熔断 → 连续失败后主动退出所有组、归还任务、释放锁，心跳恢复后自动重新加入

## 关键设计点

- **高精度调度**：Master 提前 1 秒推送任务，Worker 使用自研时间轮（分段 park + 最终自旋）实现亚毫秒级本地等待，端到端偏差控制在 3ms 级（Windows 本机实测）。
- **时间同步策略**：`DefaultTimeSynchronizer` 支持多种时间源（Redis、Master 健康服务、系统时钟），通过样本稳定性过滤抵抗时钟抖动，Master 切换时自动强制重对齐。
- **健康检查与心跳**：Master 通过 `HealthServer` 接收 Worker 心跳，使用 `HeartbeatManager` 双 Map 结构实现 O(log N) 超时判定。支持分治管理：精准心跳快速检测，注册中心长超时兜底。
- **生命周期管理**：`LifecycleSupport` 通过反射自动发现组件依赖并按深度优先排序，`AbstractLifecycle` 和 `AbstractResumableLifecycle` 提供状态模板，减少样板代码。
- **熔断与自愈**：Worker 连续心跳上报失败后自动暂停消费、归还任务、释放锁，心跳恢复后自动重新加入工作组。
- **扩展与集成**：通过 `TockRegister`、`ScheduleStore`、`WorkerQueue` 等接口抽象，可快速替换注册中心（Redis/ZK/etcd）和队列实现。提供 `MemoryConfigBuilder` / `RedisConfigBuilder` 简化配置。