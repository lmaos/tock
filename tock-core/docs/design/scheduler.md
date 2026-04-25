# Scheduler 模块设计

`scheduler` 目录是 Master 侧调度核心。

## 核心类

| 类 | 作用 |
| --- | --- |
| `TockScheduler` | 调度器接口 |
| `EventDrivenCronScheduler` | 当前默认实现 |

## 设计说明

- 只有 Master 节点会真正启动调度器
- 调度器根据 `ScheduleStore.getGlobalVersion()` 判断是否需要重载配置
- 每个 `scheduleId` 都会独立维护触发计划
- 触发时只负责生成 `JobExecution` 并推给 `WorkerQueue`

## 依赖链路

- `Tock` 负责把 `EventDrivenCronScheduler` 装进生命周期图
- `TockRegister` 提供 Master 角色和节点状态
- `ScheduleStore` 提供配置快照和版本号
- `WorkerQueue` 接收最终的 `JobExecution`
- `HealthMaintainer` 提供节点过期通知，供恢复逻辑使用

## 实际运行链路

1. Master 成为主节点后触发 `resume()`
2. `refreshSchedulesIfNeeded()` 拉取 `ScheduleStore.getAll()`
3. `computeNextFireTime()` 生成下一次 fireTime
4. 到点后 `onFire()` 构建 `JobExecution`
5. `push()` 把任务送入 `WorkerQueue`
6. `onFire()` 结束后继续为下一轮重新排程
7. 节点过期时通过 `NodeHealthListener` 进入恢复逻辑

## 行为特征

- 支持 cron 和 fixedDelay
- 采用事件驱动而不是全量轮询
- 早唤醒后会重新计算后续触发点，避免同一时间窗反复命中
- 默认会提前约 1 秒把任务送入队列，给 Worker 本地定时执行预留容错窗口
- fixedDelay 会借助 `runtimeState` 保存上次 fireTime，避免丢失续跑基准

## 这条链路的容错思路

调度器不等到最后一刻才把任务交给 Worker，而是提前把 `JobExecution` 放进队列。这样可以吸收网络延迟、节点抖动和短暂调度偏差。  
真正的执行时刻交给 Worker 的本地定时器处理，所以“什么时候执行”这个动作离业务代码更近，整体精度通常更稳定。

## 为什么这样设计

- **容错网络延迟**：任务先到 Worker，再由本机等到点执行
- **提升精度**：最终触发不依赖跨节点实时通信
- **提高效率**：调度器只负责出队和排程，不承担最后一刻的业务等待
- **保持解耦**：Master 不需要知道 Worker 内部如何等待
