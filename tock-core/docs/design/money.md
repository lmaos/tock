# Money 模块设计

`money` 目录只保留 `MemoryManager`，它本质上是测试和内存实现的共享状态容器。

## 设计说明

- 主要服务 `MemoryTockRegister` 和相关内存测试
- 这里不是业务资金概念，只是历史命名
- 如果后续要重构命名，建议先保留兼容层

## 依赖链路

- `MemoryTockRegister` 通过 `MemoryManager` 共享 node、group attribute 和 runtime state
- 内存测试会直接依赖它来模拟分布式状态

## 为什么这样设计

- **给内存实现一个共享状态容器**：测试和本地模式不用额外引入外部存储
- **保持兼容**：历史命名虽然不直观，但已经被现有实现使用

