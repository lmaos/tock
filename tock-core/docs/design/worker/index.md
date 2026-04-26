# Worker 模块设计

`worker` 目录负责消费任务、等待本地触发点、执行 Job，并在分布式环境中做执行保护。

## 核心类

| 类 | 作用 |
| --- | --- |
| `TockWorker` | Worker 接口 |
| `DefaultTockWorker` | 默认 Worker 实现 |
| `WorkerQueue` | 任务推送接口 |
| `SubscribableWorkerQueue` | 订阅式队列接口 |
| `PullableWorkerQueue` | 拉取式队列接口 |
| `WorkerExecutionLease` | 执行租约标记 |
| `WorkerExecutionKeys` | Worker 键生成工具 |

## 设计说明

- Worker 只负责“收到任务后如何执行”
- 队列模式分为 subscribe 和 poll 两种
- 真正执行前会先抢分布式保护键，防止同一计划被重复执行
- 支持 pending 恢复和组级别重新入队，默认开关由 `Config.pendingExecutionRecoveryEnabled` 控制，默认关闭
- 默认实现会等心跳首次建立 / 恢复后再进入消费态，减少“刚启动就被 pause 回去”的抖动
- 任务进入 Worker 后会先绑定一次时间快照，这个快照会跟着同一个任务一路走到业务执行结束，保证整个执行过程看到的是同一个时间种子

## 依赖链路

- `EventDrivenCronScheduler` 把 `JobExecution` 推到 `WorkerQueue`
- `DefaultTockWorker` 从 `WorkerQueue` 取到任务后查询 `JobRegistry`
- `TaskScheduler` 负责本地等待到点再执行
- `TockRegister` 负责执行锁、node 属性和 group attribute
- `DefaultTimeSynchronizer` 给 Worker 提供线程内时间快照，`JobContext.currentTimeMillis()` 直接读取它
- 同一个任务从进入 Worker、进入业务回调到执行结束，都会沿用同一个快照；业务侧看到的时间因此更稳定，也更贴近任务开始时刻

## 实际执行链路

1. `joinGroup()` 记录本节点要消费的 `workerGroup`
2. `SubscribableWorkerQueue.subscribe()` 或 `PullableWorkerQueue.poll()` 接到任务
3. `executeJob()` 先绑定时间快照，再计算剩余延迟并交给本地 `TaskScheduler`
4. `doExecuteJob()` 抢 `WorkerExecutionLease`
5. 通过 `JobRegistry.get(jobId)` 找到 `JobExecutor`
6. 执行完成后清理锁、pending 标记和线程内快照

## 这条链路的容错思路

调度器会提前把计划送进队列，Worker 收到后先缓存成 `JobExecution`，再依赖本地定时器等到真正到点时执行。  
这时绑定的时间快照会一直沿用到业务结束，所以业务线程里读到的时间不是“临时取值”，而是这次任务自己的时间种子。  
这样做的直接效果是：网络延迟只影响“计划到达 Worker 的时刻”，不会直接放大成“业务执行偏差”。

## 实现思路

Worker 和调度器之间只传 `JobExecution`，不直接共享执行上下文。  
这样做能把“何时触发”“如何配送”“真正执行业务”拆成三层，方便替换队列、替换执行器，也方便做恢复。

## 为什么这样设计

- **解耦**：队列实现、等待实现、业务执行互不绑定
- **职责清晰**：Worker 不负责计算 cron，也不负责存配置
- **支持两类队列**：既支持订阅式，也支持拉取式
- **支持恢复**：pending / lease 信息都放在注册中心里
- **把准确度下沉到本机**：最终触发由 Worker 本地定时器负责，更容易贴近目标时间点

