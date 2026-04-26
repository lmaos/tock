# API 说明

本文覆盖 `tock-core` 当前最常用的公共入口与构建对象。

## `Tock`

## 创建与获取实例

| 方法 | 说明 |
| --- | --- |
| `Tock.configure(Config config)` | 初始化单例；同一 JVM 只能配置一次 |
| `Tock.get()` | 获取已配置实例 |
| `Tock.start()` | 启动选主、Worker、调度链路 |
| `Tock.shutdown()` | 停止调度与 Worker，并按配置关闭线程池 |

## 任务与调度管理

| 方法 | 说明 |
| --- | --- |
| `registerJob(String jobId, JobExecutor executor)` | 注册业务回调 |
| `joinGroup(String groupName)` | 让当前 Worker 开始消费某个组 |
| `addSchedule(ScheduleConfig scheduleConfig)` | 保存或覆盖一个调度配置 |
| `refreshSchedules()` | 立即触发一次调度器刷新 |
| `removeSchedule(String scheduleId)` | 删除计划 |
| `pauseSchedule(String scheduleId)` | 把计划标记为 disabled |
| `resumeSchedule(String scheduleId)` | 重新启用计划 |

## 时间与阻塞

| 方法 | 说明 |
| --- | --- |
| `currentTimeMillis()` | 返回当前时间 |
| `sync()` | 一直阻塞到 `shutdown()` |
| `sync(long ms)` | 最多阻塞指定毫秒数 |

## `HeartbeatReporter`

| 方法 | 说明 |
| --- | --- |
| `addHeartbeatReportListener(...)` | 监听心跳上报结果 |
| `isHeartbeatEstablished()` | 当前心跳是否已经建立成功过 |

`HeartbeatReportListener` 会区分三种状态：

- 首次建立成功
- 失败累计达到阈值
- 失败后恢复成功

## `ScheduleConfig.Builder`

`ScheduleConfig` 描述的是“什么时候执行哪个 job，并交给哪个 group”。

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `scheduleId` | 是 | 调度配置唯一 ID |
| `jobId` | 是 | 要执行的业务回调 ID |
| `cron` | 二选一 | Cron 表达式，和 `fixedDelayMs` 互斥 |
| `fixedDelayMs` | 二选一 | 固定延迟毫秒数，和 `cron` 互斥 |
| `workerGroup` | 建议填写 | 任务路由键；Worker 需显式 `joinGroup()` |
| `enabled` | 否 | 默认 `true` |
| `params` | 否 | 业务参数，透传到 `JobContext` |
| `zoneId` | 否 | 默认 `ZoneId.systemDefault().getId()` |

## Cron 格式

当前实现支持六段格式：

```text
秒 分 时 日 月 周
```

常用例子：

| 表达式 | 含义 |
| --- | --- |
| `*/1 * * * * ?` | 每秒 |
| `0 */5 * * * ?` | 每 5 分钟 |
| `0 0 2 * * ?` | 每天凌晨 2 点 |

## `JobExecutor`

```java
public interface JobExecutor {
    void execute(JobContext context) throws Exception;
}
```

约束：

- 业务异常会被 Worker 记录日志
- 当前默认实现不会自动重试

## `JobContext`

| 字段 | 含义 |
| --- | --- |
| `scheduleId` | 本次执行来自哪个调度配置 |
| `jobId` | 当前回调 ID |
| `scheduledTime` | 计划触发时间 |
| `actualFireTime` | 实际开始执行时间 |
| `params` | `ScheduleConfig` 透传参数 |
| `retryCount` | 预留字段，默认 `0` |
| `timeSource` | 当前线程绑定的时间种子；没有快照时回退到上下文默认时间源，同一个任务从进入 Worker 到业务结束都会沿用它 |

辅助方法：

| 方法 | 说明 |
| --- | --- |
| `currentTimeMillis()` | 直接从 `timeSource` 取当前时间 |

推荐在业务日志里同时记录：

- `scheduledTime`
- `actualFireTime`
- `actualFireTime - scheduledTime`

这样最容易追踪执行偏差，也方便把业务线程里的时间和调度线程对齐。

## `TaskSchedulers`

```java
TaskSchedulers.highPrecision("worker-name")
TaskSchedulers.schedulerExecutor("worker-name")
```

| 工厂方法 | 返回类型 | 适用场景 |
| --- | --- | --- |
| `highPrecision(...)` | `HighPrecisionWheelTaskScheduler` | 更关注本地等待精度 |
| `schedulerExecutor(...)` | `ScheduledExecutorTaskScheduler` | 希望保持 JDK 原生调度语义 |

两种执行器都支持 `setAdvanceNanos(...)` 调整“提前提交”量。  
其中 `HighPrecisionWheelTaskScheduler` 在分布式时间源下如果没有手动覆盖，会自动把默认 advance 从 `200_000ns` 提升到 `1_000_000ns`。

## `RedisTockRegister`

常用构造方法：

```java
new RedisTockRegister(namespace, jedisPool)
new RedisTockRegister(namespace, host, port)
new RedisTockRegister(namespace, jedisPool, serializer, leaseTimeoutMs, heartbeatIntervalMs)
```

其中：

- `namespace` 决定 Redis key 前缀
- `leaseTimeoutMs` 决定主从与节点租约过期时间
- `heartbeatIntervalMs` 决定续租频率

## 推荐调用顺序

```java
Tock tock = Tock.configure(config).start();
tock.registerJob("job-id", ctx -> { ... });
tock.joinGroup("group");
tock.addSchedule(schedule);
tock.refreshSchedules();
```

如果是 demo 或命令行程序，可在最后调用：

```java
tock.sync();
```
