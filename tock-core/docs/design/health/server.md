# Health Server 设计

`health/server` 负责在 Master 上暴露健康服务和节点心跳入口。

## 核心类

| 类 | 作用 |
| --- | --- |
| `HealthServer` | NIO 服务端 |
| `HeartbeatManager` | 心跳时间管理 |
| `SelectorBalancer` | Selector 分发策略接口 |
| `DefaultSelectorBalancer` | 默认分发实现 |

## 设计说明

- `HealthServer` 基于 `Selector` / `SocketChannel` 处理请求
- `port = 0` 时会自动绑定可用端口，并写回 `HealthHost`
- `getHealthHost()` 会携带当前可见 IP、端口和服务 key
- 收到 active 报文时会回调 `onActive(...)`

## 实现思路

服务端用 NIO 是为了把健康请求和心跳请求放在同一个轻量入口里，而不是引入完整 RPC 框架。  
`HealthHost` 把当前 Master 可见的地址集合、端口和 key 交给注册中心，客户端再据此发现并重连。

## 为什么这样设计

- **轻量**：健康链路只需要时间和心跳，不需要复杂协议栈
- **可发现**：节点可从注册中心找到当前健康服务
- **可切换**：Master 变化时 key 会变化，客户端可自动识别

## 与维护器的关系

`DefaultHealthMaintainer` 会启动这个服务端，并把 `HealthHost` 写回注册中心，供节点侧 `HealthClientManager` 发现。

