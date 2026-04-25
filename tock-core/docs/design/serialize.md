# Serialize 模块设计

`serialize` 目录负责对象编解码和版本兼容。

## 核心类

| 类 | 作用 |
| --- | --- |
| `Serializer` | 序列化接口 |
| `SerializerFactory` | 默认实现选择器 |
| `VersionedSerializer` | 版本包装器 |
| `JavaSerializer` | JDK 序列化 |
| `JacksonSerializer` | Jackson 实现 |
| `FastjsonSerializer` | Fastjson 实现 |
| `KryoSerializer` | Kryo 实现 |

## 设计说明

- 默认按 classpath 自动选择实现：Jackson > Fastjson > Kryo > Java
- `Tock` 会统一再包一层 `VersionedSerializer`
- Redis 相关组件依赖这里对 `JobExecution`、节点状态等对象做稳定编解码

## 依赖链路

- `SerializerFactory` 先探测 classpath
- `Tock` 和 `RedisSupport` 使用 `SerializerFactory.getDefault()`
- `RedisTockRegister`、`RedisScheduleStore`、`RedisSubscribableWorkerQueue` 等 Redis 组件都通过 `RedisSupport.encode/decode()` 间接依赖它

## 实现思路

自动选择 serializer 的目标是减少用户配置成本。  
外层再包 `VersionedSerializer`，是为了给未来协议变更留出兼容层，而不是把版本信息散落到各个 Redis 组件里。

## 为什么这样设计

- **默认可用**：不强制用户提前引入某个 JSON 库
- **兼容性**：版本头集中管理
- **统一出口**：Redis 相关对象都通过同一个编码路径

