# Schedule Redis 实现

`schedule/redis` 把调度配置放到 Redis 里，供多节点共享。

## 核心类

| 类 | 作用 |
| --- | --- |
| `RedisScheduleStore` | Redis 调度配置存储 |

## 设计说明

- 依赖 `RedisSupport` 统一处理 namespace 和编解码
- 通常使用 Hash 保存配置，另用单独键保存全局版本号
- 版本号变化是调度器刷新配置的主要信号
- 写入、删除要尽量保持幂等，只在真实变化时递增版本

## 依赖链路

- `RedisConfigBuilder` 和手工 `Config.builder()` 都可以装配它
- `EventDrivenCronScheduler` 依赖它做配置刷新
- `ScheduleExecutionGuard`、`ScheduleConfig` 的快照变化最终也会体现在这里

## 实现思路

Redis 版本把调度配置拆成“配置体 + 全局版本号”，这样 scheduler 只要盯版本，就能低成本判断是否需要重载。  
写入幂等和“只有真实变化才递增版本”是为了减少无意义的重排。

## 为什么这样设计

- **动态刷新简单**：版本号就是刷新信号
- **避免频繁重排**：无变化不递增版本
- **适合多节点**：所有 Master 读取同一份配置快照

