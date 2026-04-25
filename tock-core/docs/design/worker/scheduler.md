# Worker Scheduler 设计

`worker/scheduler` 负责本地等待和定时唤醒。

## 核心类

| 类 | 作用 |
| --- | --- |
| `TaskScheduler` | 调度器接口 |
| `TaskSchedulers` | 工厂入口 |
| `ScheduledExecutorTaskScheduler` | JDK 调度器实现 |
| `HighPrecisionWheelTaskScheduler` | 高精度时间轮实现 |
| `SpinWaitSupport` | 自旋等待辅助 |

## 设计说明

- `ScheduledExecutorTaskScheduler` 适合简单和默认场景
- `HighPrecisionWheelTaskScheduler` 使用 1ms tick，适合更敏感的触发精度
- `TaskScheduler` 同时支持 `schedule(...)` 和 `submit(...)`

## 依赖链路

- `DefaultTockWorker` 通过 `TaskScheduler` 等待 `nextFireTime`
- `Config` 通过 `workerExecutor` 注入具体实现
- `EventDrivenCronScheduler` 不直接依赖这里，但会产出给它执行的 `JobExecution`

## 实现思路

把本地等待抽成接口，是为了让 Worker 的“任务到点执行”能力可替换。  
默认调度器简单稳定，高精度时间轮更适合对抖动敏感的场景。

## 为什么这样设计

- **可替换**：不同业务可以选择不同等待策略
- **避免耦合**：Worker 不需要知道底层是线程池还是时间轮
- **便于验证**：执行器可以独立做精度测试

## 选择原则

- 普通任务：默认调度器即可
- 追求更稳的本地触发：使用高精度时间轮

