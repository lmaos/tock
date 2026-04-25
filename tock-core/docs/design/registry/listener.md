# Registry Listener 设计

`registry/listener` 放的是监听器集合和辅助管理逻辑。

## 核心类

| 类 | 作用 |
| --- | --- |
| `MasterListeners` | Master 监听器集合 |
| `NodeListeners` | 节点监听器集合 |

## 设计说明

- 目标是把监听器的增删和广播逻辑收口
- 避免各个注册中心实现重复维护回调集合
- 只负责事件分发，不负责事件来源

