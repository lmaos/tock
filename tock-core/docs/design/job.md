# Job 模块设计

`job` 目录定义业务执行入口和 Job 注册表。

## 核心类

| 类 | 作用 |
| --- | --- |
| `JobExecutor` | 业务回调 |
| `JobContext` | 执行上下文 |
| `JobRegistry` | Job 注册接口 |
| `DefaultJobRegistry` | 本地实现 |

## JobContext

Job 执行时至少需要知道：

- `scheduleId`
- `jobId`
- `scheduledTime`
- `actualFireTime`
- `params`

## 设计说明

- Job 代码默认是节点本地注册的，不需要分布式共享
- 调度层只负责决定“何时执行”，业务层只负责“执行什么”

## 依赖链路

- `Tock.registerJob()` 写入 `JobRegistry`
- `DefaultTockWorker.doExecuteJob()` 通过 `JobRegistry.get(jobId)` 取出 `JobExecutor`
- `JobContext` 携带 scheduleId、jobId、scheduledTime、actualFireTime 和 params

## 实现思路

Job 模块只做“业务执行入口映射”，不去参与调度或分布式协调。  
这样 Job 代码可以保持非常稳定，调度器和 Worker 的变化不会影响业务回调签名。

## 为什么这样设计

- **稳定 API**：业务方只实现一个回调接口
- **解耦**：业务逻辑和调度逻辑不互相依赖
- **易测试**：Job 可以直接单测，不需要启动集群

