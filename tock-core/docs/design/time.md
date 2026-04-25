# Time 模块设计

`time` 目录提供时间源、时间同步器和相关实现。

## 核心类

| 类 | 作用 |
| --- | --- |
| `TimeSource` | 统一时间源接口 |
| `TimeProvider` | 原始时间采样接口 |
| `TimeSynchronizer` | 同步后的时间基准接口 |
| `DefaultTimeSynchronizer` | 默认实现 |
| `SystemTimeProvider` | 本地系统时间 |
| `RedisTimeProvider` | Redis `TIME` |
| `OffsetTimeProvider` | 偏移包装 |
| `HealthTimeProvider` | 健康服务时间 |

## 设计说明

- `TimeProvider` 决定“从哪取时间”
- `DefaultTimeSynchronizer` 决定“怎么校正和单调化”
- 对调度器来说，时间必须稳定、单调、可比较
- 默认不额外配置时，时间直接依托服务器当前时间
- 如果配置了同步规则，`time` 模块会把调度侧和 Worker 侧拉到同一时间基准

## 依赖链路

- `RedisTimeProvider` 通过 `Jedis` 取 Redis 时间
- `HealthTimeProvider` 通过 `HeartbeatReporter.serverTime()` 取健康服务时间
- `DefaultTimeSynchronizer` 依赖 `TimeProvider`
- `EventDrivenCronScheduler`、`DefaultTockWorker`、`RedisTockRegister` 都会通过 `TockContext.currentTimeMillis()` 使用这里的结果

## 实现思路

时间模块分成“时间来源”和“时间同步器”两层：来源只负责拿一个原始时间，同步器负责把这个时间变成可用于调度的稳定时间。  
`DefaultTimeSynchronizer` 在实现上做了采样、偏移平滑和单调递增保护，因此不会直接把某个远程时间原样抛给上层。

## 为什么这样设计

- **可替换**：Redis 时间、健康服务时间、本地时间都能接入
- **稳定**：同步器统一处理抖动和回拨问题
- **职责分层**：来源和校正分开，方便测试和排障
- **默认简单**：不配置同步时就退化为系统时间，不给使用者增加额外心智负担

