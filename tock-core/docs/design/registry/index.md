# Registry 模块设计

`registry` 目录负责节点发现、选主、运行时状态和分布式属性管理。

## 核心类

| 类 | 作用 |
| --- | --- |
| `TockRegister` | 注册中心接口 |
| `TockMaster` | Master 选举接口 |
| `TockNode` | 节点只读接口 |
| `TockCurrentNode` | 当前节点接口 |
| `MasterListener` | Master 切换监听器 |
| `NodeListener` | 节点状态监听器 |

## 设计说明

- `TockRegister` 同时承载选主、节点状态、组属性和运行时状态
- Master 和 Node 的生命周期都从这里发出
- Memory/Redis 两套实现共享同一接口语义

## 依赖链路

- `Tock` 在装配和启动时拿到 `TockRegister`
- `EventDrivenCronScheduler` 用它判断 Master 身份，并在节点过期时清理恢复状态
- `DefaultTockWorker` 用它写执行锁、node 属性和 group attribute
- `DefaultHealthMaintainer` / `DefaultHeartbeatReporter` 用它协调整个健康链路

## 实现思路

注册中心不是单纯的“节点列表”，而是 cluster state 的承载层。  
因此它同时保存 master 租约、当前节点状态、group attribute、runtime state，以及节点过期判断所需的数据。

## 为什么这样设计

- **集中 cluster 状态**：避免 Master、Worker、健康模块各存一份状态
- **减少跨模块耦合**：执行锁、心跳、选主都从注册中心拿统一入口
- **支持多实现**：Memory 和 Redis 只是不同存储介质，不改变接口契约
- **依赖通过 context 传递**：具体实现只通过 `TockContext` 取时间、生命周期和其他协作对象

