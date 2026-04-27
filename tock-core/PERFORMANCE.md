# Tock Core 当前能力性能报告

本报告只保留**当前最终结论**。  
历史暴露问题与排查过程单独记录在 [`docs/exposed-issues-log.md`](docs/exposed-issues-log.md)。

## 1. 当前结论

1. **内存 Worker 严格 A/B 结果**：`worker-chain avg abs skew` 为 **4.31ms vs 0.28ms**，高精度在本地精度上更稳。
2. **Windows 本机 Redis 稳态精度**：`redis-high-precision` 约 **0.44ms**，`redis-default-worker` 约 **8.16ms**。
3. **吞吐不是高精度的绝对优势项**：内存模式约 **5.5k~5.7k tasks/s**，Redis 模式约 **4.1k~4.2k tasks/s**，默认调度器在吞吐上略高一些，但差距不大。
4. **业务侧时间依然贴近实际执行点**：`tock.currentTimeMillis()` 与 `actualFireTime` 基本贴合，通常只有 **0~1ms** 级偏差。

## 2. 测试矩阵

| 测试 | 环境 | 目的                                                                                                           |
| --- | --- |--------------------------------------------------------------------------------------------------------------|
| Windows memory-only Worker A/B | Windows 11, Java 25.0.1, `MemoryTockRegister`, 20 warmup + 100 measured, `delay=150ms` | 严格比较 `ScheduledExecutorTaskScheduler` 与 `HighPrecisionWheelTaskScheduler` 在纯内存 Worker 链路下的真实精度，作为当前本地精度排名的权威口径 |
| Windows 精度稳态复测 | Windows 11, Java 25.0.1, Redis 8.6.2, `-Xms4g/-Xmx4g` | 观察 1025ms 长尾是否复现                                                                                 |
| Windows 全量 benchmark | Windows 11, Java 25.0.1, Redis 8.6.2, `-Xms4g/-Xmx4g` | 生成整秒精度、吞吐、CPU、堆内存的主报告数据                                                                                      |

说明：

- Redis 场景全部基于 **单实例 Redis，本机回环地址 `127.0.0.1:6379`**

## 3. 整秒触发精度

### 3.1 Windows memory-only Worker 严格 A/B

| 指标 | default-worker | high-precision | 结论 |
| --- | ---: | ---: | --- |
| avg abs skew | 4.31ms | 0.28ms | 高精度更稳 |
| p95 | 13ms | 1ms | 高精度更稳 |
| p99 | 14ms | 1ms | 高精度更稳 |

补充说明：

- 数据来自当前 4G 全量 benchmark 的 memory 场景
- 口径为：`MemoryTockRegister` + `delay=150ms` + `20` 次 warmup + `100` 次 measured

### 3.2 Windows Redis 稳态精度

| 指标 | redis-default-worker | redis-high-precision | 结论 |
| --- | ---: | ---: | --- |
| avg abs skew | 8.16ms | 0.44ms | 高精度更稳 |
| p95 | 15ms | 1ms | 高精度更稳 |
| p99 | 16ms | 3ms | 高精度更稳 |

补充说明：

- 数据来自当前 4G 全量 benchmark 的 Redis 场景
- 环境为单实例 Redis，本机回环地址 `127.0.0.1:6379`

### 3.3 Ubuntu 参考精度结果

本轮未包含 Linux 对照。

### 3.4 业务最关心的“直接看到的时间”

下面这些值来自业务回调内直接读取的：

```java
tock.currentTimeMillis()
```

#### Windows 本机 `memory-high-precision`

| scheduled | actual | callback now | skew (ms) | callback lag (ms) |
| --- | --- | --- | ---: | ---: |
| 12:47:17.000 (1777265237000) | 12:47:16.999 (1777265236999) | 12:47:17.000 (1777265237000) | -1 | 1 |
| 12:47:18.000 (1777265238000) | 12:47:18.001 (1777265238001) | 12:47:18.001 (1777265238001) | 1 | 0 |
| 12:47:19.000 (1777265239000) | 12:47:19.001 (1777265239001) | 12:47:19.001 (1777265239001) | 1 | 0 |
| 12:47:20.000 (1777265240000) | 12:47:20.000 (1777265240000) | 12:47:20.001 (1777265240001) | 0 | 1 |
| 12:47:21.000 (1777265241000) | 12:47:21.000 (1777265241000) | 12:47:21.000 (1777265241000) | 0 | 0 |

#### Windows 本机 `redis-default-worker`

| scheduled | actual | callback now | skew (ms) | callback lag (ms) |
| --- | --- | --- | ---: | ---: |
| 12:48:01.000 (1777265281000) | 12:48:01.001 (1777265281001) | 12:48:01.002 (1777265281002) | 1 | 1 |
| 12:48:02.000 (1777265282000) | 12:48:02.010 (1777265282010) | 12:48:02.011 (1777265282011) | 10 | 1 |
| 12:48:03.000 (1777265283000) | 12:48:03.003 (1777265283003) | 12:48:03.004 (1777265283004) | 3 | 1 |
| 12:48:04.000 (1777265284000) | 12:48:04.014 (1777265284014) | 12:48:04.015 (1777265284015) | 14 | 1 |
| 12:48:05.000 (1777265285000) | 12:48:05.009 (1777265285009) | 12:48:05.010 (1777265285010) | 9 | 1 |

#### Windows 本机 `redis-high-precision`

| scheduled | actual | callback now | skew (ms) | callback lag (ms) |
| --- | --- | --- | ---: | ---: |
| 12:48:45.000 (1777265325000) | 12:48:45.000 (1777265325000) | 12:48:45.001 (1777265325001) | 0 | 1 |
| 12:48:46.000 (1777265326000) | 12:48:46.000 (1777265326000) | 12:48:46.001 (1777265326001) | 0 | 1 |
| 12:48:47.000 (1777265327000) | 12:48:47.000 (1777265327000) | 12:48:47.001 (1777265327001) | 0 | 1 |
| 12:48:48.000 (1777265328000) | 12:48:48.000 (1777265328000) | 12:48:48.001 (1777265328001) | 0 | 1 |
| 12:48:49.000 (1777265329000) | 12:48:49.000 (1777265329000) | 12:48:49.001 (1777265329001) | 0 | 1 |

这些样本说明：**业务在回调里直接看到的 `tock.currentTimeMillis()`，与实际执行时间基本一致**。

### 3.5 图表：精度状态

#### Windows memory Worker 图（avg abs skew，越低越好）

```text
memory-default-worker    | ████████████████████████ 4.31 ms
memory-high-precision    | ██ 0.28 ms
```

#### Windows Redis 精度图（avg abs skew，越低越好）

```text
redis-default-worker     | ████████████████████████ 8.16 ms
redis-high-precision     | ██ 0.44 ms
```


### 3.6 当前可对外表达的精度结论

1. **内存 Worker 严格 A/B**：`worker-chain avg abs skew` 为 **4.31ms vs 0.28ms**，高精度在本地精度上更稳。
2. **Windows 本机 Redis**：`redis-high-precision` 约 **0.44ms**，`redis-default-worker` 约 **8.16ms**。
3. **业务侧时间**：`tock.currentTimeMillis()` 与 `actualFireTime` 基本贴合，通常只有 **0~1ms** 级偏差。

## 4. 吞吐、CPU、堆内存

### 4.1 Windows 11 全量 benchmark

| 场景 | avg tasks/s | avg CPU% | peak heap (MiB) |
| --- | ---: | ---: | ---: |
| memory-default-worker | 5708.78 | 5.19 | 2121.53 |
| memory-high-precision | 5494.31 | 8.81 | 2364.77 |
| redis-default-worker | 4200.35 | 9.62 | 1033.99 |
| redis-high-precision | 4100.14 | 13.17 | 2115.99 |

#### Windows 吞吐图（tasks/s，越高越好）

```text
memory-default-worker    | ████████████████████████ 5708.78 tasks/s
memory-high-precision    | ███████████████████████ 5494.31 tasks/s
redis-default-worker     | ██████████████████ 4200.35 tasks/s
redis-high-precision     | █████████████████ 4100.14 tasks/s
```



### 4.2 吞吐结论

1. **Windows 本机**
   - 内存模式约 **5.5k ~ 5.7k tasks/s**
   - Redis 模式约 **4.1k ~ 4.2k tasks/s**
2. **Redis 模式的吞吐瓶颈主要在单实例 Redis 消息链路**
   - 同样的 Worker 本地执行流程下，Redis 默认 / 高精度两种执行器吞吐差异不大
   - 但一切换到 Redis，吞吐会明显低于内存模式
   - 这说明当前 workload 下，主要瓶颈不是本地等待调度器，而是 **单实例 Redis 的消息通道、序列化/反序列化、消费分发**

## 5. 资源代价

1. 本轮 Windows 的 Redis 压测峰值堆都大致落在 **1.0 ~ 2.4 GiB** 左右，4G 堆下会比 2G 口径更高一些。
2. Windows 本机里，Redis 默认吞吐略高于 Redis 高精度，但两者差距不大，CPU 也都维持在可控范围。

## 6. 产物位置

| 产物 | 路径                                                   |
| --- |------------------------------------------------------|
| Windows 全量 benchmark | `target/performance/`                                |
| Windows memory-only Worker A/B | `target/memory-worker-precision-study/`     |
| Windows 2G 长尾复测 | `target/tail-study/windows-2g/`             |
| Windows 4G 长尾复测 | `target/tail-study/windows-4g/`             |
| Redis 高精度专项 study | `target/performance/redis-precision-study/` |

备注: target 仅本地测试产物, 可参考: [PerformanceBenchmarkMain.java](src/test/java/com/clmcat/tock/PerformanceBenchmarkMain.java)


## 7. 补充说明

- 如果需要看 Redis 高精度 Worker 的细粒度参数研究，参考 [`docs/redis-high-precision-study.md`](docs/redis-high-precision-study.md)
- 如果需要看历史暴露问题与复测结论，参考 [`docs/exposed-issues-log.md`](docs/exposed-issues-log.md)
- 如果需要看时间同步修复细节，参考 [`docs/time-sync-ab-report.md`](docs/time-sync-ab-report.md)
