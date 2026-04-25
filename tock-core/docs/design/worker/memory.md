# Worker Memory 实现

`worker/memory` 提供单 JVM 可用的队列实现。

## 核心类

| 类 | 作用 |
| --- | --- |
| `MemoryPullableWorkerQueue` | 拉取式内存队列 |
| `MemorySubscribableWorkerQueue` | 订阅式内存队列 |

## 设计说明

- 主要用于测试和本地开发
- 不提供跨进程共享，也不提供持久化
- 适合验证 Worker 逻辑、调度逻辑和并发行为

## 语义

- Pull 模式：Worker 主动轮询
- Subscribe 模式：队列主动推送到消费者

## 为什么这样设计

- **最小依赖**：用纯内存结构就能跑完整链路
- **测试友好**：单元测试和集成测试都能直接替换掉 Redis
- **语义对齐**：和 Redis 队列保持相同的 WorkerGroup 概念

