# Tock Core

Tock 是一个面向 JVM 的分布式定时器内核：**一个 Master 负责调度，所有节点都可以作为 Worker 执行任务**。  
当前仓库提供两种运行模式：

| 模式 | 适用场景 | 关键组件 |
| --- | --- | --- |
| Memory | 本地开发、单进程验证 | `MemoryTockRegister` + `MemoryScheduleStore` + `MemoryPullableWorkerQueue` |
| Redis | 多节点部署、共享时间基准 | `RedisTockRegister` + `RedisScheduleStore` + `RedisSubscribableWorkerQueue` |

## 特性

- **统一入口**：通过 `Tock.configure(config)` 完成组件装配与生命周期管理
- **Master-Worker 架构**：仅主节点调度，所有节点都可消费任务
- **同步时间基准**：Redis 模式下可自动使用 Redis `TIME` 作为采样源
- **两种本地执行器**：默认 `ScheduledExecutorTaskScheduler`，可选 `TaskSchedulers.highPrecision(...)`
- **动态调度配置**：`ScheduleStore` 更新后，调度器可通过版本号刷新
- **分布式执行保护**：Worker 使用 group attribute 锁避免同一计划被并发重复执行

## Maven 依赖

```xml
<dependency>
    <groupId>com.clmcat.tock</groupId>
    <artifactId>tock-core</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

## 5 分钟上手：内存模式

```java
import com.clmcat.tock.Config;
import com.clmcat.tock.Tock;
import com.clmcat.tock.money.MemoryManager;
import com.clmcat.tock.registry.memory.MemoryTockRegister;
import com.clmcat.tock.schedule.ScheduleConfig;
import com.clmcat.tock.schedule.memory.MemoryScheduleStore;
import com.clmcat.tock.worker.memory.MemoryPullableWorkerQueue;
import com.clmcat.tock.worker.scheduler.TaskSchedulers;

MemoryTockRegister register = new MemoryTockRegister("quickstart", MemoryManager.create());

Config config = Config.builder()
        .register(register)
        .scheduleStore(MemoryScheduleStore.create())
        .workerQueue(MemoryPullableWorkerQueue.create())
        .workerExecutor(TaskSchedulers.highPrecision("quickstart-worker"))
        .build();

Tock tock = Tock.configure(config).start();

tock.registerJob("hello-job", ctx -> {
    System.out.println("scheduled=" + ctx.getScheduledTime() + ", actual=" + ctx.getActualFireTime());
});

tock.joinGroup("default");
tock.addSchedule(ScheduleConfig.builder()
        .scheduleId("hello-schedule")
        .jobId("hello-job")
        .cron("*/1 * * * * ?")
        .workerGroup("default")
        .zoneId("Asia/Shanghai")
        .build());
tock.refreshSchedules();
tock.sync(10_000L);
tock.shutdown();
```

## Redis 模式最小配置

```java
import com.clmcat.tock.Config;
import com.clmcat.tock.Tock;
import com.clmcat.tock.registry.redis.RedisTockRegister;
import com.clmcat.tock.schedule.ScheduleConfig;
import com.clmcat.tock.schedule.redis.RedisScheduleStore;
import com.clmcat.tock.worker.redis.RedisSubscribableWorkerQueue;
import com.clmcat.tock.worker.scheduler.TaskSchedulers;
import redis.clients.jedis.JedisPool;

JedisPool jedisPool = new JedisPool("127.0.0.1", 6379);
String namespace = "demo";

Config config = Config.builder()
        .register(new RedisTockRegister(namespace, jedisPool))
        .scheduleStore(RedisScheduleStore.create(namespace, jedisPool))
        .workerQueue(RedisSubscribableWorkerQueue.create(namespace, jedisPool))
        .workerExecutor(TaskSchedulers.highPrecision("redis-worker"))
        .build();

Tock tock = Tock.configure(config).start();
tock.registerJob("redis-job", ctx -> System.out.println("fire@" + ctx.getActualFireTime()));
tock.joinGroup("default");
tock.addSchedule(ScheduleConfig.builder()
        .scheduleId("redis-schedule")
        .jobId("redis-job")
        .fixedDelayMs(1000L)
        .workerGroup("default")
        .zoneId("UTC")
        .build());
tock.refreshSchedules();
```

说明：

- `TaskSchedulers.highPrecision(...)` 在 **分布式时间源** 下会自动把默认 `advanceNanos` 调整到 `1ms`
- 如果业务已经显式调用 `setAdvanceNanos(...)`，Tock 不会覆盖你的自定义值

## 核心概念

| 概念 | 说明 |
| --- | --- |
| `ScheduleConfig` | 描述“何时执行、执行哪个 `jobId`、交给哪个 `workerGroup`” |
| `JobExecutor` | 业务回调接口，Worker 触发时执行 `execute(JobContext)` |
| `WorkerGroup` | 任务路由键；Worker 只会消费自己加入的组 |
| `Tock.currentTimeMillis()` | 当前统一时间基准；Redis 模式下由同步器对齐远端时间 |

## 文档

- [配置指南](docs/configuration.md)
- [架构与设计](docs/architecture.md)
- [API 说明](docs/api.md)
- [运维手册](docs/operations.md)
- [当前性能与精度报告](PERFORMANCE.md)
- [性能暴露日志](docs/exposed-issues-log.md)
- [时间同步与 Redis 定时误差分析](docs/time-sync-ab-report.md)
- [Redis 高精度时间轮研究](docs/redis-high-precision-study.md)

## 当前验证结论

当前仓库已经补充了可重复的多轮 benchmark、专项 study，以及 Windows / WSL2 对照结果。当前结论：

- Windows 本机 Redis 高精度 Worker 稳态整秒偏差约 **3ms 级**
- WSL2 Ubuntu 参考环境下 Redis 高精度 Worker 整秒偏差约 **4ms**
- Windows 本机内存模式吞吐约 **10374.77 tasks/s**
- Windows 本机 Redis 模式吞吐约 **5909.85 tasks/s**

`PERFORMANCE.md` 只保留当前最终能力结论；历史暴露问题移到 [`docs/exposed-issues-log.md`](docs/exposed-issues-log.md)。`docs/redis-high-precision-study.md` 侧重 Redis Worker 精度专项对比，修复过程与根因分析单独放在 [docs/time-sync-ab-report.md](docs/time-sync-ab-report.md)。

## License

项目当前 `pom.xml` 中声明为 **MIT License**。
