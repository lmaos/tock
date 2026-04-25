# Schedule 模块设计

`schedule` 目录定义调度配置模型和配置存储接口。

## 核心类

| 类 | 作用 |
| --- | --- |
| `ScheduleConfig` | 调度配置对象 |
| `ScheduleStore` | 调度配置存储接口 |
| `ScheduleExecutionGuard` | 调度执行保护与 fingerprint 辅助 |

## 设计说明

- `ScheduleConfig` 是“调度声明”，不是执行记录
- `ScheduleStore` 只保存配置和版本，不保存业务状态
- `ScheduleExecutionGuard` 用于避免重复触发和识别同一计划的变化

## 依赖链路

- `Tock.addSchedule()` / `refreshSchedules()` 会把配置写入 `ScheduleStore`
- `EventDrivenCronScheduler` 从 `ScheduleStore` 读取配置并计算下一次触发点
- `EventDrivenCronScheduler` / `DefaultTockWorker` 会参考 `ScheduleExecutionGuard` 做执行有效性判断和 fingerprint 识别

## 实现思路

把“调度定义”和“调度执行”拆开，是为了让配置修改可以独立于执行过程。  
`ScheduleStore` 只保存状态快照和版本，`EventDrivenCronScheduler` 只负责把快照翻译成 `JobExecution`。

## 为什么这样设计

- **配置与执行分离**：避免执行过程反向污染配置对象
- **支持动态刷新**：版本号变化即可触发重载
- **支持恢复语义**：执行保护信息可以单独做去重和恢复判断

## 相关目录

- `memory/`：本地实现
- `redis/`：分布式实现

