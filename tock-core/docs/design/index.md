# 模块设计总览

这组文档按 `src/main/java/com/clmcat/tock` 的目录结构组织，想做的是把每个模块的职责、核心类、调用关系和扩展点说得更顺一点。

## 总链路

```text
Config -> Tock -> TockRegister / ScheduleStore / WorkerQueue / TaskScheduler
       -> TockScheduler -> JobExecution -> WorkerQueue -> TockWorker -> JobExecutor
```

辅助链路：

- `time` 为调度和 Worker 提供统一时间
- `health` 负责 Master 健康服务、节点心跳和重连
- `serialize` 负责 Redis 组件之间的对象传输
- `utils` 负责生命周期排序、引用保护、网络探测

## 设计原则

1. **装配和执行分离**：`Config` 只管拼装，`Tock` 才负责生命周期
2. **主链路和支撑链路分层**：调度/执行/注册是核心链路，time/serialize/utils 是支撑
3. **Memory 与 Redis 同语义**：两套实现只换存储，不换接口语义
4. **优先解耦，再做优化**：高精度执行器、健康链路、执行保护都建立在清晰职责之上
5. **调度提前，Worker 本地执行**：Master 先把任务送进队列，Worker 再用本机定时器等到点执行业务，尽量把网络抖动隔离在执行之外
6. **统一时间基准**：默认使用服务器当前时间；如果配置时间同步，`time` 模块会把调度和 Worker 的时间拉到一条线上，系统时间也会返回统一的快照形态
7. **健康只管健康**：健康链路只负责发现、心跳和过期通知，不直接掺入调度或业务执行；Worker 则在心跳首次建立后再恢复消费

## 阅读顺序

1. `root.md`：入口、上下文、生命周期
2. `builders.md`：两种快速装配方式
3. `schedule/`、`worker/`、`registry/`、`health/`：主运行链路
4. `time.md`、`serialize.md`、`utils.md`、`redis.md`：基础支撑

## 目录映射

| 文档 | 对应源码目录 |
| --- | --- |
| [root.md](root.md) | `com/clmcat/tock` |
| [builders.md](builders.md) | `com/clmcat/tock/builders` |
| [cron.md](cron.md) | `com/clmcat/tock/cron` |
| [health/index.md](health/index.md) | `com/clmcat/tock/health` |
| [health/client.md](health/client.md) | `com/clmcat/tock/health/client` |
| [health/server.md](health/server.md) | `com/clmcat/tock/health/server` |
| [job.md](job.md) | `com/clmcat/tock/job` |
| [schedule/index.md](schedule/index.md) | `com/clmcat/tock/schedule` |
| [schedule/memory.md](schedule/memory.md) | `com/clmcat/tock/schedule/memory` |
| [schedule/redis.md](schedule/redis.md) | `com/clmcat/tock/schedule/redis` |
| [scheduler.md](scheduler.md) | `com/clmcat/tock/scheduler` |
| [store.md](store.md) | `com/clmcat/tock/store` |
| [worker/index.md](worker/index.md) | `com/clmcat/tock/worker` |
| [worker/memory.md](worker/memory.md) | `com/clmcat/tock/worker/memory` |
| [worker/redis.md](worker/redis.md) | `com/clmcat/tock/worker/redis` |
| [worker/scheduler.md](worker/scheduler.md) | `com/clmcat/tock/worker/scheduler` |
| [registry/index.md](registry/index.md) | `com/clmcat/tock/registry` |
| [registry/memory.md](registry/memory.md) | `com/clmcat/tock/registry/memory` |
| [registry/redis.md](registry/redis.md) | `com/clmcat/tock/registry/redis` |
| [registry/listener.md](registry/listener.md) | `com/clmcat/tock/registry/listener` |
| [time.md](time.md) | `com/clmcat/tock/time` |
| [serialize.md](serialize.md) | `com/clmcat/tock/serialize` |
| [utils.md](utils.md) | `com/clmcat/tock/utils` |
| [redis.md](redis.md) | `com/clmcat/tock/redis` |
| [money.md](money.md) | `com/clmcat/tock/money` |
| [annotation.md](annotation.md) | `com/clmcat/tock/annotation` |

## 读法建议

如果你要看“一个任务从配置到执行”的全流程，先看 `root.md`、`schedule/index.md`、`scheduler.md`、`worker/index.md`、`registry/index.md`，再看 `health/` 和 `time.md`。

