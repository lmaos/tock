# Registry Memory 实现

`registry/memory` 为单 JVM 或测试场景提供注册中心。

## 核心类

| 类 | 作用 |
| --- | --- |
| `MemoryTockRegister` | 内存注册中心 |
| `MemoryTockMaster` | 内存 Master 选举 |
| `MemoryTockNode` | 内存节点对象 |

## 设计说明

- 使用共享内存状态保存 master、node、group attribute 和 runtime state
- 适合测试、demo 和本地开发
- 不负责跨进程一致性

## 依赖链路

- `MemoryTockRegister` 聚合 `MemoryTockMaster` 和 `MemoryTockNode`
- `MemoryTockRegister.start()` 先启用 master，再启用 currentNode
- `DefaultTockWorker`、`EventDrivenCronScheduler`、`HealthMaintainer` 都可以直接依赖它的接口语义

## 实现思路

内存注册中心把 cluster state 放在一个共享 `MemoryManager` 里，目的是让测试和本地模式也能具备完整的选主、节点属性和运行时状态能力。  
这样可以在不依赖 Redis 的情况下，验证大部分运行链路。

## 为什么这样设计

- **测试替身**：能复刻 Redis 注册中心的大部分语义
- **简单直观**：共享内存结构便于调试
- **避免引入外部依赖**：本地模式更轻

