# Registry Redis 实现

`registry/redis` 是分布式运行时状态的实现。

## 核心类

| 类 | 作用 |
| --- | --- |
| `RedisTockRegister` | Redis 注册中心 |
| `RedisTockMaster` | Redis 选主实现 |
| `RedisTockNode` | 远程节点只读代理 |
| `RedisTockCurrentNode` | 当前节点实现 |

## 设计说明

- 通过 Redis 共享节点状态、Master 状态和运行时属性
- `RedisTockMaster` 使用租约/续租思路维护主身份
- 当前节点会负责心跳和自身状态上报
- namespace 会隔离不同应用或测试用例的数据
- `setGroupAttribute(...)` 是覆盖写，`setGroupAttributeIfAbsent(...)` 则保留“只写一次”的语义

## 依赖链路

- `RedisTockRegister` 继承 `RedisSupport`
- 它内部持有 `RedisTockMaster` 和 `RedisTockCurrentNode`
- `HealthTimeProvider` / `DefaultHeartbeatReporter` / `EventDrivenCronScheduler` 都会从它读取运行时状态

## 实现思路

把 master、node、runtime state 和 group attribute 放到同一个注册中心里，能够让选主、执行锁、心跳、恢复逻辑都读取同一份 cluster state。  
`RedisTockRegister.start()` 先启动当前节点再启动 master，也是为了让选主时立刻拿到当前节点 ID；健康链路里的 `health.host` 现在也会在恢复时覆盖写回去。

## 为什么这样设计

- **统一 cluster state**：避免多个 Redis 结构各自维护状态
- **便于恢复**：节点过期后可以直接根据属性做回收
- **支持时间/健康联动**：健康链路和调度链路共享同一注册中心

