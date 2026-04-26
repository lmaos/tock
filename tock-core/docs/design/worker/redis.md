# Worker Redis 实现

`worker/redis` 是多节点场景下的任务队列实现。

## 核心类

| 类 | 作用 |
| --- | --- |
| `RedisSubscribableWorkerQueue` | Redis 列表队列 |

## 设计说明

- 生产端对 `queue:<workerGroup>:pending` 做写入
- 消费端按分片线程维护多个 workerGroup
- 通过 `BLPOP` 阻塞获取任务
- 如果回调未成功派发，会把任务重新放回队列
- `subscribe()` 会先懒初始化分片线程，因此即使队列还没显式 start，Worker 也可以先加入消费

## 依赖链路

- `EventDrivenCronScheduler.push()` 生产任务到这里
- `DefaultTockWorker` 订阅这里的队列或轮询这里的队列
- `RedisSupport` 提供连接、命名空间和编解码

## 实现思路

使用 Redis List 作为队列，是为了让调度器和 Worker 之间只传递简单的执行载荷，而不需要共享复杂状态。  
分片线程负责多个 group，可以减少“每个 group 一个线程”的资源浪费；懒初始化则能让 joinGroup 更早发生，不用先卡在队列启动顺序上。

## 为什么这样设计

- **降低线程数**：多个 group 复用分片线程
- **可靠交付**：消费失败时可以重新入队
- **模块解耦**：队列不需要知道业务如何执行

## 实现特征

- 队列分片减少线程占用
- 订阅者为空时会等待，不会忙等
- 依赖 `TockContext` 提供的 consumerExecutor 派发执行

