# Cron 模块设计

`cron` 目录提供 Cron 计算基础设施，供调度器计算下一次触发时间。

## 核心类

| 类 | 作用 |
| --- | --- |
| `CronCalculator` | 计算下一次触发时间 |
| `CronCalculators` | 按 `ZoneId` 缓存计算器实例 |

## 设计说明

- 这里负责的是“算时间”，不是“调度任务”
- `ZoneId` 是 Cron 计算的一部分，不能省略
- 调度器只负责调用它，不应该自己拼 cron 逻辑

## 依赖链路

- `EventDrivenCronScheduler` 依赖这里计算下一次触发时间
- `ScheduleConfig` 提供 cron 字符串和时区
- `TimeSynchronizer` 负责给调度器一个稳定时间基准

## 实现思路

把 Cron 计算单独拆出来，是为了避免调度器里混进表达式解析、时区处理和时间推导逻辑。  
这样做的好处是调度器只关心“什么时候触发”，Cron 模块只关心“下一次什么时候”，两边都容易测试。
当前实现是自研 `CronCalculator`，调度器直接调用它，而不是依赖外部 Cron 解析器。

## 为什么这样设计

- **单一职责**：Cron 只算表达式，不做调度编排
- **可替换**：以后更换 Cron 语法或实现时，不影响调度器主流程
- **易验证**：Cron 计算可以独立做回归测试

## 关系

- `EventDrivenCronScheduler` 依赖这里得到下一次 `fireTime`
- `ScheduleConfig` 里的 cron 表达式最终会流到这里

