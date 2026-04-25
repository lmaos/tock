# Root 模块设计

`com.clmcat.tock` 是整个库的入口层，负责把配置、上下文、生命周期和门面 API 串起来。

## 核心类

| 类 | 作用 |
| --- | --- |
| `Tock` | 全局门面，负责装配、启动、关闭和对外 API |
| `Config` | 所有可替换组件的装配契约 |
| `TockContext` | 运行时上下文，保存所有组件实例 |
| `Lifecycle` | 基础生命周期接口 |
| `ResumableLifecycle` | 支持 pause / resume 的生命周期扩展 |
| `TockContextAware` | 允许组件接收 `TockContext` |

## 设计要点

- `Tock.configure(config)` 只做装配，真正启动在 `start()`
- `Config` 是组件边界，不是业务对象
- `TockContext` 用来把 `register`、`scheduleStore`、`workerQueue`、`workerExecutor` 等实例传递给各组件
- `ReferenceSupport` 会限制 `Config` 和共享对象重复复用，避免多个 `Tock` 实例争用同一批组件

## 依赖链路

`Tock` 依赖 `Config` 提供所有可替换组件；`Config` 再把 `register`、`scheduleStore`、`workerQueue`、`workerExecutor`、`scheduler`、`worker`、`timeProvider`、`timeSynchronizer`、`healthMaintainer`、`heartbeatReporter` 注入给 `Tock`。  
`Tock` 启动后会构建 `TockContext`，再把上下文交给这些组件。这样各模块只通过 `context.getXxx()` 获取依赖，而不是彼此直接 new。

这层设计的结果是：

- root 层只负责装配和编排
- 各子模块只关心自己的职责
- 测试时可以用内存实现替换掉 Redis 实现

## 实际启动链路

1. `Tock.configure(config)` 构造单例并收集组件
2. `LifecycleSupport.loadLifecycles(...)` 扫描 `Config` 和 `Tock` 中的生命周期组件
3. `start()` 先启动共享基础设施，再启动 register / health / worker / scheduler
4. `register` 负责把当前节点和 Master 状态准备好
5. `healthMaintainer`、`heartbeatReporter`、`worker`、`scheduler` 依次进入运行态

## 实现思路

1. 先通过 `Config` 建立组件图
2. 再用 `LifecycleSupport` 排好启动 / 停止顺序
3. 之后由 `Tock` 统一启动 worker、健康、调度等关键组件
4. 最后把运行时状态留在各自模块内部，避免 root 层持有太多业务逻辑

## 为什么这样设计

- **解耦**：业务执行、调度、注册中心、健康检查互相独立
- **职责清晰**：`Tock` 是门面，不是调度器也不是队列
- **便于替换**：Memory/Redis/自定义实现都只需要接入 `Config`
- **便于测试**：可以单测单模块，也可以组合成端到端测试
- **减少环形依赖**：调度器、Worker、健康模块都通过上下文拿依赖，避免直接互调
- **依赖通过 context 传递**：模块之间不直接持有对方实现，只读取 `TockContext` 暴露的契约

## 启动顺序

1. 组装默认组件和用户自定义组件
2. 按依赖顺序启动生命周期组件
3. 注册 Master / Node 监听器
4. 启动 Worker、健康维护、调度器

## 关闭顺序

停止时按逆序释放：先停消费和调度，再清理注册中心与共享状态，最后销毁上下文引用。

