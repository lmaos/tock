# Health Client 设计

`health/client` 负责从节点侧连到 Master 的健康服务，并完成时间获取和心跳上报。

## 核心类

| 类 | 作用 |
| --- | --- |
| `HealthClient` | 单连接请求客户端 |
| `HealthClientManager` | 连接发现、重连和 Master 切换管理 |
| `RequestContext` | 请求上下文 |
| `HealthRequest` | 请求基类 |
| `HealthResponse` | 响应对象 |
| `HealthReportActiveRequest` | 心跳上报请求 |
| `MasterChangeListener` | Master 切换监听器 |

## 设计说明

- `HealthClient` 只有一条 socket 连接，内部用单独的读写线程处理请求
- 请求通过 `reqId` 匹配响应，避免并发请求串包
- `HealthClientManager` 从注册中心读取 `health.host`
- 连接断开或 Master 切换后，管理器会自动重建连接

## 实现思路

`HealthClient` 只负责协议层：连接、写请求、读响应、按 `reqId` 关联结果。  
`HealthClientManager` 负责策略层：发现服务端、探测可达 IP、判断 master 是否切换、重连。

## 为什么这样设计

- **职责拆分**：协议和连接策略分开，方便测试和替换
- **避免复杂状态机**：单连接模型更容易保证请求响应配对
- **支持 Master 切换**：管理器可以在 key 变化时重建连接并通知上层

## 使用场景

- 节点上报活跃心跳
- 获取 Master 侧时间
- Master 切换感知

