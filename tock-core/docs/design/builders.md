# 构建器设计

`builders` 目录提供两种快速装配方式，目的是把常见的内存模式和 Redis 模式封装成一行式配置。

## `MemoryConfigBuilder`

- 直接拼装 `MemoryTockRegister`、`MemoryScheduleStore`、`MemorySubscribableWorkerQueue`
- `withHighPrecisionWorker(true)` 时使用高精度执行器
- 适合本地开发、单元测试、无外部依赖场景

## `RedisConfigBuilder`

- 需要显式传入 `JedisPool`
- 默认使用 `RedisTockRegister`、`RedisScheduleStore`、`RedisSubscribableWorkerQueue`
- 同样支持 `withHighPrecisionWorker(true)`
- 可通过 `withTimeProvider(...)` 替换时间来源

## 设计原则

- 构建器只负责默认装配，不承载业务逻辑
- 最终仍然返回标准 `Config`
- 如果要接入新存储或新队列，建议直接走 `Config.builder()`，而不是扩展构建器本身

## 依赖链路

- `MemoryConfigBuilder` 依赖 `MemoryTockRegister`、`MemoryScheduleStore`、`MemorySubscribableWorkerQueue`
- `RedisConfigBuilder` 依赖 `JedisPool`、`RedisTockRegister`、`RedisScheduleStore`、`RedisSubscribableWorkerQueue`
- 两者最终都回到 `Config`

## 实现思路

构建器只是把“常见默认值”收拢起来，减少用户在样板代码上重复选择组件。  
真正的依赖装配还是交给 `Config`，这样构建器不会变成另一套平行框架。

## 为什么这样设计

- **降低上手成本**：一行就能跑起来
- **不锁死架构**：需要特殊组件时，仍可直接走 `Config.builder()`
- **减少重复**：Memory 和 Redis 两套默认组合不需要用户重复拼装

