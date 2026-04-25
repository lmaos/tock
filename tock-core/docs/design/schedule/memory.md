# Schedule Memory 实现

`schedule/memory` 是调度配置的本地实现，适合测试和单 JVM 使用。

## 核心类

| 类 | 作用 |
| --- | --- |
| `MemoryScheduleStore` | 内存调度配置存储 |

## 设计说明

- 使用线程安全的内存结构保存 `ScheduleConfig`
- 提供 `getAll()` 快照语义，便于调度器刷新
- 全局版本号只在真实变更时递增

## 依赖链路

- `EventDrivenCronScheduler` 读取这里的配置
- `Tock.addSchedule()`、`Tock.refreshSchedules()` 写入这里
- `ScheduleExecutionGuard` 依赖配置快照判断执行是否还有效

## 实现思路

内存实现只解决“本 JVM 内的配置共享”，因此它主要承担测试和开发场景。  
它保留版本号语义，是为了和 Redis 实现保持一致，便于同一套调度代码在两种存储下复用。

## 为什么这样设计

- **低成本**：不需要外部服务
- **一致性**：和 Redis 版本语义保持一致
- **便于调试**：快照数据可直接在内存里观察

## 适用场景

- 单元测试
- 本地开发
- 不需要持久化的简单场景

