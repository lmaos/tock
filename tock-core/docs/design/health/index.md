# Health 模块设计

`health` 目录负责节点活性、心跳、Master 健康服务和连接维护。

## 职责

- 对外提供健康服务端
- 对内维护活跃心跳
- 在 Master 切换时重连和通知

## 关键类

| 类 | 作用 |
| --- | --- |
| `DefaultHealthMaintainer` | 节点侧健康维护与过期清理 |
| `DefaultHeartbeatReporter` | 节点心跳上报 |
| `HealthHost` | 健康服务地址描述 |
| `HealthMaintainer` | 健康维护接口 |
| `HeartbeatReporter` | 心跳上报接口 |
| `NodeHealthListener` | 节点过期监听器 |

## 设计边界

- client 负责发请求
- server 负责接收请求
- maintainer 负责把两者接到注册中心和生命周期里

## 依赖链路

- `DefaultHealthMaintainer` 依赖 `HealthServer` 和 `HeartbeatManager`
- `DefaultHeartbeatReporter` 依赖 `HealthClientManager`
- `HealthClientManager` 依赖 `TockRegister` 读取 `health.host`
- `NetworkUtils` 用于发现可访问 IP 和探测服务端口

## 实际运行链路

1. Master 启动 `HealthServer`
2. `DefaultHealthMaintainer` 把 `HealthHost` 写回注册中心
3. Worker 侧 `DefaultHeartbeatReporter` 启动 `HealthClientManager`
4. `HealthClientManager` 发现 Master 地址并建立连接
5. `reportActive()` 周期性上报活跃心跳
6. `DefaultHealthMaintainer` 扫描超时节点并通知 `NodeHealthListener`

## 为什么这样设计

- **服务端/客户端分离**：Master 只管收心跳，不掺杂节点发现逻辑
- **重连逻辑独立**：客户端连接变化不会污染业务执行路径
- **便于恢复**：节点过期后可以统一进入调度恢复流程
- **健康只关心健康**：健康模块不直接参与业务执行，它只负责活性、心跳和过期通知
- **依赖通过 context 传递**：健康模块只从 `TockContext` 和 `TockRegister` 拿所需依赖，不直接依赖业务模块实现

