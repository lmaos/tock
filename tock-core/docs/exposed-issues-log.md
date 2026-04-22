# 性能暴露日志

本文只记录**已经暴露过、但不属于当前主结论**的问题与复测说明，避免把历史异常混入主性能报告。

## 1. Redis 高精度 Worker 的 1025ms 长尾

### 首次暴露

- 环境：Windows 11，Java 25.0.1，Redis 8.6.2，`-Xms2g -Xmx2g`
- 入口：`PerformanceBenchmarkMain`
- 现象：`redis-high-precision` 在 4 轮 × 8 样本的全量 benchmark 中，出现了 **1 个 `1025ms` 的极端迟到样本**
- 直接影响：把该轮聚合均值显著拉高

### 复测结果

#### Windows 2G 精度专项复测

- 入口：`PerformanceBenchmarkMain`
- 配置：`benchmark.scenarios=memory-high-precision,redis-high-precision`、`benchmark.skipThroughput=true`、`benchmark.precision.rounds=12`
- Redis 高精度结果：
  - 平均绝对偏差：`3.03ms`
  - p99：`4ms`
  - 样本数：`96`
  - **未再出现秒级长尾**
- GC 日志：
  - 仅出现 Young GC
  - 单次停顿约 `2.37ms ~ 2.69ms`

#### Windows 4G 精度专项复测

- 环境改为：`-Xms4g -Xmx4g`
- Redis 高精度结果：
  - 平均绝对偏差：`3.51ms`
  - p99：`8ms`
  - 样本数：`96`
  - **未再出现秒级长尾**
- GC 日志：
  - 仍然只有 Young GC
  - 单次停顿约 `2.32ms ~ 2.83ms`

#### Windows 专项 study 复测

- 入口：`RedisHighPrecisionStudyMain`
- `redis-high-precision-auto-default`：
  - 10 轮
  - 平均轮次绝对偏差：`0.32ms`
  - **未再出现秒级长尾**

### 当前判断

1. **没有证据表明 1025ms 长尾是 GC 导致的**
   - 2G 与 4G 两组复测里，GC 停顿都只有 `2ms` 级
   - 秒级长尾没有和 GC 日志对上
2. **没有证据表明“增大堆”能直接治好这个问题**
   - 因为在 2G 和 4G 两组复测里，长尾都没有再出现
   - 更合理的结论是：这个长尾**不是稳定复现问题**
3. **更像是低概率的瞬时运行时抖动**
   - 原始暴露：`1 / 32` 个样本
   - 后续 Windows follow-up：
     - 精度专项 2G：`96` 个 Redis 高精度样本
     - 精度专项 4G：`96` 个 Redis 高精度样本
     - Redis 专项 study：`60` 个 Redis 高精度样本
   - 合计后续 **`252` 个 Windows Redis 高精度样本未再复现**

### 建议如何对外说明

- 可以说明：**这次 1025ms 长尾属于已暴露但未复现的问题，当前判断为低概率异常样本，不是稳定能力边界**
- 当前对外能力结论仍应以 [`../PERFORMANCE.md`](../PERFORMANCE.md) 中的最终复测结果为准

## 2. Redis 吞吐为什么明显低于内存模式

### 当前结论

当前 benchmark 基于：

- **单实例 Redis**
- 本机回环地址 `127.0.0.1:6379`

在这个前提下，Redis 模式吞吐明显低于内存模式，主因不是 Worker 本地执行器，而是：

1. Redis push/pop 消息链路
2. 序列化 / 反序列化
3. 订阅分发与网络栈路径

直接证据是：

- Windows 本机：
  - `redis-default-worker`：`5419.27 tasks/s`
  - `redis-high-precision`：`5909.85 tasks/s`
- WSL2 Ubuntu：
  - `redis-default-worker`：`4676.51 tasks/s`
  - `redis-high-precision`：`4711.16 tasks/s`

也就是说，在 Redis 场景里切换本地执行器，吞吐变化不大；但一切换到 Redis，相比内存模式会整体下降很多。  
这说明当前 workload 下的主瓶颈是 **单实例 Redis 消息通道**。

## 3. 产物位置

| 产物 | 路径 |
| --- | --- |
| 首次暴露的 Windows 全量 benchmark | `target/performance/` |
| Windows 2G 长尾专项复测 | `target/tail-study/windows-2g/` |
| Windows 4G 长尾专项复测 | `target/tail-study/windows-4g/` |
| WSL2 Ubuntu 全量 benchmark | `target/wsl-study/linux-2g/` |
| Redis 高精度专项 study | `target/performance/redis-precision-study/` |

## 4. WSL2 memory 模式周期性 700ms 长尾（已修复）

### 首次暴露

- 环境：WSL2 Ubuntu 22.04，Java 25.0.2，`-Xms2g -Xmx2g`
- 入口：`PerformanceBenchmarkMain`
- 现象：
  - `memory-default-worker`：`29.36ms`
  - `memory-high-precision`：`22.91ms`
  - 样本中反复出现 `678ms ~ 706ms` 级别的迟到

### 说明

该条目的相关分析与结论已移除。
