# 运维手册

本文以 Redis 部署模式为主，因为这也是线上最常见的运行方式。

## Redis key 结构

所有 key 默认带统一前缀：

```text
tock:redis:<namespace>
```

## 核心 key

| Key / Pattern | 类型 | 说明 |
| --- | --- | --- |
| `tock:redis:<ns>:master:<ns>` | String | 当前 Master 节点 ID |
| `tock:redis:<ns>:nodes:index` | ZSet | 所有节点及其最近 lease 时间 |
| `tock:redis:<ns>:node:<nodeId>:meta` | Hash | 节点状态、leaseTime、heartbeatIntervalMs |
| `tock:redis:<ns>:node:<nodeId>:attrs` | Hash | 节点执行属性与 pending 标记 |
| `tock:redis:<ns>:runtime:states` | Hash | 运行时状态，例如 `last_fire:<scheduleId>` |
| `tock:redis:<ns>:group:attrs` | Hash | 分布式执行锁等 group 级属性 |
| `tock:redis:<ns>:queue:<workerGroup>:pending` | List | Worker 待消费队列 |
| `tock:redis:<ns>:schedules` | Hash | 序列化后的 `ScheduleConfig` |
| `tock:redis:<ns>:schedules:version` | String | 调度配置全局版本号 |

## 属性名约定

| Pattern | 所在位置 | 含义 |
| --- | --- | --- |
| `consumer.<group>.<schedule>` | `group:attrs` | 某个计划当前被哪个 Worker 占用 |
| `pending.<group>.<schedule>.<execution>` | `node:<nodeId>:attrs` | 已收到但尚未真正开始执行的计划 |
| `last_fire:<scheduleId>` | `runtime:states` | `fixedDelayMs` 计划的最近触发时间 |

## 日常巡检

## 1. 看当前有没有 Master

重点确认：

- `master:<ns>` 是否存在
- 其值是否对应 `nodes:index` 中某个活跃节点

如果没有 Master：

- 检查 Redis 是否可写
- 检查 `leaseTimeoutMs` / `heartbeatIntervalMs` 是否设置过激

## 2. 看节点心跳是否持续刷新

关注：

- `nodes:index` 中 score 是否持续增长
- `node:<nodeId>:meta` 中 `leaseTime` 是否跟着更新
- `status` 是否为 `ACTIVE`

若 `status=ACTIVE` 但 `currentTime - leaseTime > leaseTimeoutMs`，节点状态会被视为 `UNKNOWN`。

## 3. 看队列是否堆积

检查：

- `queue:<workerGroup>:pending` 的长度
- Worker 是否已经执行 `joinGroup(group)`
- `consumer.<group>.<schedule>` 是否长期不释放

如果列表持续增长且无消费：

1. 确认对应节点已 `joinGroup`
2. 确认 `JobExecutor` 已注册
3. 检查 Worker 线程池是否被业务阻塞

## 4. 看调度配置是否已被 Master 感知

检查：

- `schedules` 中配置是否存在
- `schedules:version` 是否递增

`EventDrivenCronScheduler` 只会在版本变化时刷新，因此配置保存后没有触发新版本，Master 就不会重建调度。

## 常见问题排查

## 问题 1：任务没有执行

按顺序检查：

1. `registerJob(jobId, executor)` 是否执行过
2. Worker 是否 `joinGroup(workerGroup)`
3. `ScheduleConfig.enabled` 是否为 `true`
4. 是否存在可工作的 Master
5. 队列是否积压在 `queue:<group>:pending`

## 问题 2：Redis 模式下时间提前或连续连发

优先确认使用的是当前修复后的版本，并查看：

- `docs/time-sync-ab-report.md`
- `../PERFORMANCE.md`

当前版本已经修复：

1. 时间同步器混用墙钟与单调时钟
2. 调度器提前唤醒后错误重算下一次 cron
3. Worker 到点前未再次复核同步时间

如果仍有偏差，先判断是：

- Redis 时间基准错误
- 队列堆积
- 本机调度抖动

最直接的方法是复跑 `RedisMemoryTimingDiagnosticsTest`。

## 问题 3：节点离线后恢复慢

主要看两个参数：

| 参数 | 默认值 | 作用 |
| --- | ---: | --- |
| `leaseTimeoutMs` | 3000ms | 多久不续租就认为租约过期 |
| `heartbeatIntervalMs` | 1000ms | 多久续租一次 |

默认组合意味着：

- 正常情况下每秒一次续租
- 超过约 3 秒不续租，其他节点就可能开始接管

想更快恢复，可以调小这两个值，但代价是：

- Redis 写入更频繁
- 对瞬时 GC / 抖动更敏感

## 调优项

## 对外可调的参数

| 参数 | 位置 | 默认值 | 建议 |
| --- | --- | ---: | --- |
| `leaseTimeoutMs` | `RedisTockRegister` | 3000ms | 故障切换更快可适度调小 |
| `heartbeatIntervalMs` | `RedisTockRegister` | 1000ms | 一般保持为 lease 的 1/3 左右 |
| `advanceNanos` | `ScheduledExecutorTaskScheduler` | 1,000,000ns | 默认 1ms 提前量 |
| `advanceNanos` | `HighPrecisionWheelTaskScheduler` | 200,000ns（本地）/ 1,000,000ns（分布式时间源自动默认） | 显式 `setAdvanceNanos(...)` 时不会再自动调整 |
| `workerExecutor` 池大小 | 执行器构造参数 | 依实现而定 | 任务 CPU 重时适当增加 |
| `schedulerExecutor` 线程数 | 自定义线程池 | 默认 2 | 大量 scheduleId 时可上调 |
| `consumerExecutor` 大小 | 自定义线程池 | cached | 大量 group 时建议自定义 |
| `pendingExecutionRecoveryEnabled` | `Config` | false | 需要更强恢复能力时开启 |

## 当前不是运行时配置的项

以下参数存在于实现内部，但当前**不是** `Config` 暴露项：

- `EventDrivenCronScheduler` 的 1000ms / 15ms 提前唤醒策略
- `HighPrecisionWheelTaskScheduler` 的自旋阈值和粗粒度 park 窗口

如果要调整这些值，需要改代码并重新验证。

补充：

- 这里的“分布式时间源”指 `timeProvider` 不是 `SystemTimeProvider` 的场景，例如 `RedisTockRegister`
- 自动默认只影响 **未手动覆盖** 的高精度时间轮实例

## 推荐监控指标

建议至少采集：

- 当前 Master 节点 ID
- 节点总数 / UNKNOWN 节点数
- 每个 `workerGroup` 的队列长度
- 计划执行偏差：`actualFireTime - scheduledTime`
- 时间同步 offset

## 变更与回归验证

只要修改了以下任一项，建议至少重新执行一轮 timing 相关测试：

- 时间同步算法
- Worker 执行器类型
- Redis 连接参数
- 选主租约参数

建议命令：

```powershell
mvn -q "-Dtest=EventDrivenCronSchedulerTimingTest,DefaultTockWorkerTimingGuardTest,RedisMemoryTimingDiagnosticsTest,DefaultTimeSynchronizerRedisPrecisionTest" test
```
