# Tock Core

Tock 是一个面向 JVM 的分布式定时器内核：**一个 Master 负责调度，所有节点都可以作为 Worker 执行任务**。  
当前仓库提供两种运行模式：

| 模式 | 适用场景 | 关键组件 |
| --- | --- | --- |
| Memory | 本地开发、单进程验证 | `MemoryTockRegister` + `MemoryScheduleStore` + `MemoryPullableWorkerQueue` |
| Redis | 多节点部署 | `RedisTockRegister` + `RedisScheduleStore` + `RedisSubscribableWorkerQueue` |

## 特性

- **统一入口**：通过 `Tock.configure(config)` 完成组件装配与生命周期管理
- **Master-Worker 架构**：仅主节点调度，所有节点都可消费任务
- **两种本地执行器**：默认 `ScheduledExecutorTaskScheduler`，可选 `TaskSchedulers.highPrecision(...)`
- **高度可扩展**：注册中心、调度器、队列、执行器、序列化器都可替换
- **动态调度配置**：`ScheduleStore` 更新后，调度器可通过版本号刷新
- **分布式执行保护**：Worker 使用 group attribute 锁避免同一计划被并发重复执行

## 当前状态： 

已完成核心功能，并进行了测试评估。

后面持续优化功能细节， 并提供测试验证工具/代码。

version: 1.0.0-SNAPSHOT

## Maven 依赖

```xml
<dependency>
    <groupId>com.clmcat.tock</groupId>
    <artifactId>tock-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- 序列化依赖 ： 
    默认序列工具根据用户依赖的第三方库 “自动选择”：
                优先级为：Jackson > Fastjson > Kryo > JavaSerializer。
    
    注意： 需要自行导入. 如果不导入，序列化将使用Java默认的。
    <dependency>
        <groupId>com.alibaba</groupId>
        <artifactId>fastjson</artifactId>
        <version>${fastjson.varsion}</version>
    </dependency>
 -->

<!-- Jedis 依赖， 当前默认版本的Redis 是 Jedis客户端实现。需要导入Jedis -->
<!-- <dependency>
    <groupId>redis.clients</groupId>
    <artifactId>jedis</artifactId>
    <version>${jedis.version}</version>
</dependency> -->

```

## 5 分钟上手：内存模式

### 使用 MemoryConfigBuilder 构造
```java
Config config = MemoryConfigBuilder.builder("test-app")
        // 可选，默认 false (TaskSchedulers.defaultWorker("xx-worker"))。
        //          true (TaskSchedulers.highPrecision("xx-worker"))。
        .withHighPrecisionWorker(false) 
        .build();
Tock tock = Tock.configure(config).start();
```

### 使用 Config.builder() 构建配置，

```java
MemoryTockRegister register = new MemoryTockRegister("quickstart", MemoryManager.create());
// 构建配置，指定组件实现。Tock 内置了 Memory / Redis 两套默认实现，也提供了接口供用户扩展 实现其他的存储模式。
Config config = Config.builder()
        .register(register)
        .scheduleStore(MemoryScheduleStore.create())
        .workerQueue(MemoryPullableWorkerQueue.create())
        .workerExecutor(TaskSchedulers.highPrecision("quickstart-worker"))
        .build();
// 启动 Tock 实例，完成组件装配与生命周期管理
Tock tock = Tock.configure(config).start();
// 注册 Job 回调， 相当于工作的技能树，Worker 执行时会根据调度配置找到对应的 Job 回调并执行。
tock.registerJob("hello-job", ctx -> {
    System.out.println("scheduled=" + ctx.getScheduledTime() + ", actual=" + ctx.getActualFireTime());
});
// Worker 只会消费自己加入的组，加入后才能收到调度器分发的任务。
tock.joinGroup("default");
// 添加调度配置，描述“何时执行、执行哪个 jobId、交给哪个 workerGroup”，Tock 会自动把它们分发给所有 Worker。
tock.addSchedule(ScheduleConfig.builder()
        .scheduleId("hello-schedule")
        .jobId("hello-job")
        .cron("*/1 * * * * ?")
        .workerGroup("default")
        .zoneId("Asia/Shanghai")
        .build());
tock.refreshSchedules();
// 让它跑一会儿，观察输出，然后关闭
tock.sync(10_000L);
tock.shutdown();
```


## Redis 模式最小配置

### 使用  RedisConfigBuilder 构造
```java

Config config = RedisConfigBuilder.builder("my-app")
.withJedisPool(jedisPool)
.withHighPrecisionWorker(true)
.build();
Tock tock = Tock.configure(config).start();
```

### 使用 Config.builder() 构建配置 - Config 更自由

```java
JedisPool jedisPool = new JedisPool("127.0.0.1", 6379);
String namespace = "demo";

Config config = Config.builder()
        .register(new RedisTockRegister(namespace, jedisPool))  // 注册中心
        .scheduleStore(RedisScheduleStore.create(namespace, jedisPool)) //  调度配置存储
        .workerQueue(RedisSubscribableWorkerQueue.create(namespace, jedisPool)) // 任务队列
        .workerExecutor(TaskSchedulers.highPrecision("redis-worker")) // 执行器
        .build();

Tock tock = Tock.configure(config).start(); // 启动 Tock 实例，完成组件装配与生命周期管理
// 注册 Job 回调， 相当于工作的技能树，Worker 执行时会根据调度配置找到对应的 Job 回调并执行。
tock.registerJob("redis-job", ctx -> System.out.println("fire@" + ctx.getActualFireTime()));
// Worker 只会消费自己加入的组，加入后才能收到调度器分发的任务。
tock.joinGroup("default");
// 添加调度配置，描述“何时执行、执行哪个 jobId、交给哪个 workerGroup”，Tock 会自动把它们分发给所有 Worker。
tock.addSchedule(ScheduleConfig.builder()
        .scheduleId("redis-schedule")
        .jobId("redis-job")
        .fixedDelayMs(1000L)
        .workerGroup("default")
        .zoneId("UTC")
        .build());
tock.refreshSchedules(); // 快速验证效果，提前手动刷新配置。 默认情况是自动刷新。
```


## 核心概念

| 概念 | 说明 |
| --- | --- |
| `ScheduleConfig` | 描述“何时执行、执行哪个 `jobId`、交给哪个 `workerGroup`” |
| `JobExecutor` | 业务回调接口，Worker 触发时执行 `execute(JobContext)` |
| `WorkerGroup` | 任务路由键；Worker 只会消费自己加入的组 |
| `Tock.currentTimeMillis()` | 当前时间 |

## 为什么说它是框架

Tock 当前不仅提供了 Memory / Redis 两套默认实现，也把关键基础设施边界抽成了接口。  
你可以替换：

- `TockRegister`
- `ScheduleStore`
- `WorkerQueue`
- `TockScheduler`
- `TockWorker`
- `TaskScheduler`
- `TimeProvider` / `TimeSynchronizer`
- `Serializer`

也就是说，它既可以直接作为开箱即用的定时器使用，也可以作为你自己基础设施之上的**可扩展分布式定时器内核**。  
详细接口说明见 [docs/extensibility.md](docs/extensibility.md)。

## 文档

- [配置指南](docs/configuration.md)
- [架构与设计](docs/architecture.md)
- [可扩展性与接口实现指南](docs/extensibility.md)
- [API 说明](docs/api.md)
- [运维手册](docs/operations.md)
- [当前性能与精度报告](PERFORMANCE.md)
- [性能暴露日志](docs/exposed-issues-log.md)
- [Redis 高精度时间轮研究](docs/redis-high-precision-study.md)

## 当前验证结论

当前仓库已经补充了可重复的多轮 benchmark、专项 study，以及 Windows / WSL2 对照结果。当前结论：

- Windows 本机 memory-only 严格 A/B 下，`high-precision` 的 `worker-chain avg abs skew` 为 **0.360ms**，优于 `default-worker` 的 **2.950ms**
- 单看调度器本体，`high-precision` 为 **0.108ms**，优于 `default-worker` 的 **1.507ms**
- Windows 本机 Redis 高精度 Worker 稳态整秒偏差约 **3ms 级**。
- Windows 本机内存模式吞吐约 **10374.77 tasks/s**
- Windows 本机 Redis 模式吞吐约 **5909.85 tasks/s**

`PERFORMANCE.md` 只保留当前最终能力结论；历史暴露问题移到 [`docs/exposed-issues-log.md`](docs/exposed-issues-log.md)。`docs/redis-high-precision-study.md` 侧重 Redis Worker 精度专项对比。

## License

项目当前 `pom.xml` 中声明为 **MIT License**。
