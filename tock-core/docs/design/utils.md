# Utils 模块设计

`utils` 目录放通用基础工具，不承担业务职责。

## 核心类

| 类 | 作用 |
| --- | --- |
| `NetworkUtils` | IP 探测、端口连通性探测 |
| `LifecycleSupport` | 生命周期依赖排序 |
| `ReferenceSupport` | 对象复用保护 |

## 设计说明

- `NetworkUtils` 用于健康服务地址发现和连通性探测
- `LifecycleSupport` 负责把依赖的生命周期组件按顺序启动/停止
- `ReferenceSupport` 防止 Config 或共享组件被重复接入多个 `Tock`

## 依赖链路

- `Tock` 使用 `LifecycleSupport` 排序生命周期组件
- `HealthClientManager` 使用 `NetworkUtils` 探测 Master 健康服务可达性
- `HealthServer` 也使用 `NetworkUtils.getLocalIpAddresses()` 组装可广播地址
- `Tock` 和 `Config` 通过 `ReferenceSupport` 避免重复复用同一对象

## 实现思路

这些工具都不直接参与业务链路，但它们解决的是框架级问题：生命周期顺序、网络探测、对象复用。  
把这些逻辑抽出来后，主模块就可以专注于调度、执行和注册本身。

## 为什么这样设计

- **减少重复代码**：框架级能力统一收口
- **避免循环依赖**：工具类不反向依赖业务模块
- **方便替换**：后续如果需要更复杂的网络或生命周期策略，可以只换工具层实现

