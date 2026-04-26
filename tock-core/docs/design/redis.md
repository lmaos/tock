# Redis 支撑模块设计

`redis` 目录放 Redis 组件共用的基础封装，主要是把连接、namespace 和编解码这些重复逻辑收在一起。

## 核心类

| 类 | 作用 |
| --- | --- |
| `RedisSupport` | namespace、JedisPool、编解码和 key 辅助 |

## 设计说明

- 所有 Redis 组件统一继承这里，避免重复写连接和命名空间逻辑
- `namespace` 会自动归一成 `tock:<name>` 形式
- 编解码默认使用 `VersionedSerializer(new JavaSerializer())`

## 依赖链路

- `RedisTockRegister`、`RedisScheduleStore`、`RedisSubscribableWorkerQueue` 都依赖它
- 这些组件再进一步被 `RedisConfigBuilder` 和手工 `Config.builder()` 装配

## 实现思路

把 Redis 组件的连接、命名空间、序列化封装成一个基类，可以保证不同组件使用同一套 key 规范和编码策略。  
这样做后，每个具体组件只需要关注自己的 Redis 数据结构和业务语义。

## 为什么这样设计

- **统一命名空间**：避免不同组件 key 规则不一致
- **减少重复**：连接获取、释放、编解码不用每个类都写一遍
- **便于维护**：Redis 行为变动时只改一处

