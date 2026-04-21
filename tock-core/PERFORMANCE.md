# Tock Core 当前能力性能报告

本报告只保留**当前最终结论**。  
历史暴露问题与排查过程单独记录在 [`docs/exposed-issues-log.md`](docs/exposed-issues-log.md)。

## 1. 当前结论

1. **内存 Worker 严格 A/B 结果**：当前最准确的本地精度对比里，`high-precision` 明显优于默认调度器；`worker-chain avg abs skew` 为 **0.360ms vs 2.950ms**。
2. **Windows 本机 Redis 稳态精度**：Redis 高精度 Worker 在当前版本下稳定落在 **3ms 级**，Windows 2G / 4G 复测都没有再出现秒级长尾。
3. **Redis 吞吐下降的主要原因是消息通道，而不是本地执行器**：当前压测基于 **单实例 Redis（127.0.0.1:6379）**，Redis 模式吞吐主要受单实例 Redis 的 push/pop、序列化/反序列化、订阅分发链路限制。
4. **业务侧时间可直接看 `tock.currentTimeMillis()`**：在最终验证样本里，它与 `actualFireTime` 基本一致，没有额外业务可见滞后。

## 2. 测试矩阵

| 测试 | 环境 | 目的 |
| --- | --- | --- |
| Windows memory-only Worker A/B | Windows 11, Java 25.0.1, `MemoryTockRegister`, 20 warmup + 100 measured, `delay=150ms` | 严格比较 `ScheduledExecutorTaskScheduler` 与 `HighPrecisionWheelTaskScheduler` 在纯内存 Worker 链路下的真实精度，作为当前本地精度排名的权威口径 |
| Windows 精度稳态复测 | Windows 11, Java 25.0.1, Redis 8.6.2, `-Xms2g/-Xmx2g` 与 `-Xms4g/-Xmx4g` | 专门验证 1025ms 长尾是否和 GC / 堆大小相关 |
| Windows 全量 benchmark | Windows 11, Java 25.0.1, Redis 8.6.2, `-Xms2g/-Xmx2g` | 生成整秒精度、吞吐、CPU、堆内存的主报告数据 |
| WSL2 Ubuntu 全量 benchmark | WSL2 Ubuntu 22.04, Java 25.0.2, Redis 8.6.2, `-Xms2g/-Xmx2g` | 提供 Linux 风格运行环境参考值 |

说明：

- Redis 场景全部基于 **单实例 Redis，本机回环地址 `127.0.0.1:6379`**
- WSL2 是 Linux 风格环境参考，不等同于裸机 Linux 生产机

## 3. 整秒触发精度

### 3.1 Windows memory-only Worker 严格 A/B

| 口径 | default-worker | high-precision | 结论 |
| --- | ---: | ---: | --- |
| scheduler-only avg abs skew | 1.507ms | 0.108ms | 单看本地调度器本体，高精度时间轮明显更准 |
| worker-chain avg abs skew | 2.950ms | 0.360ms | 经过 `DefaultTockWorker` 后，高精度仍明显优于默认调度器 |

补充说明：

- 这组数据来自新的 `MemoryWorkerPrecisionStudyMain`
- 口径为：`MemoryTockRegister` + `delay=150ms` + `20` 次 warmup + `100` 次 measured
- 这是当前用于判断“内存模式下谁更准”的**最终权威口径**

### 3.2 Windows Redis 稳态精度复测

| 环境 | memory-high-precision | redis-default-worker | redis-high-precision | 结论 |
| --- | ---: | ---: | ---: | --- |
| Windows 2G precision-only, 12 轮 | 1.68ms | 未测 | 3.03ms | 96 个 Redis 高精度样本，无秒级长尾 |
| Windows 4G precision-only, 12 轮 | 1.03ms | 未测 | 3.51ms | 96 个 Redis 高精度样本，无秒级长尾 |

### 3.3 WSL2 Ubuntu 参考精度结果

| 环境 | redis-default-worker | redis-high-precision | 说明 |
| --- | ---: | ---: | --- |
| WSL2 Ubuntu full benchmark | 6.47ms | 4.00ms | 仅作为 Linux 风格参考值；当前内存 Worker 精度排名不再使用 WSL2 数字 |

补充说明：

- WSL2 仍然只作为 Linux 风格参考环境，不替代裸机 Linux 生产数据
- 之前用于讨论 WSL2 memory 抖动的数字已经移入历史暴露日志，不再作为当前 Worker 精度排名依据

### 3.4 业务最关心的“直接看到的时间”

下面这些值来自业务回调内直接读取的：

```java
tock.currentTimeMillis()
```

#### Windows 本机 `memory-high-precision`

| scheduled | actual | callback now | skew (ms) |
| --- | --- | --- | ---: |
| 16:40:51.000 | 16:40:51.002 | 16:40:51.002 | 2 |
| 16:40:52.000 | 16:40:52.000 | 16:40:52.000 | 0 |
| 16:40:53.000 | 16:40:53.000 | 16:40:53.000 | 0 |
| 16:40:54.000 | 16:40:54.000 | 16:40:54.000 | 0 |
| 16:40:55.000 | 16:40:55.000 | 16:40:55.000 | 0 |

#### Windows 本机 `redis-default-worker`

| scheduled | actual | callback now | skew (ms) |
| --- | --- | --- | ---: |
| 16:41:35.000 | 16:41:35.003 | 16:41:35.003 | 3 |
| 16:41:36.000 | 16:41:36.006 | 16:41:36.006 | 6 |
| 16:41:37.000 | 16:41:37.004 | 16:41:37.004 | 4 |
| 16:41:38.000 | 16:41:38.003 | 16:41:38.003 | 3 |
| 16:41:39.000 | 16:41:39.003 | 16:41:39.003 | 3 |

#### Windows 本机 `redis-high-precision`

| scheduled | actual | callback now | skew (ms) |
| --- | --- | --- | ---: |
| 16:42:19.000 | 16:42:19.002 | 16:42:19.003 | 2 |
| 16:42:20.000 | 16:42:20.004 | 16:42:20.004 | 4 |
| 16:42:21.000 | 16:42:21.004 | 16:42:21.004 | 4 |
| 16:42:22.000 | 16:42:22.003 | 16:42:22.003 | 3 |
| 16:42:23.000 | 16:42:23.002 | 16:42:23.002 | 2 |

这些样本说明：**业务在回调里直接看到的 `tock.currentTimeMillis()`，与实际执行时间基本一致**。

### 3.5 图表：精度状态

#### Windows memory-only Worker A/B 图（avg abs skew，越低越好）

```text
scheduler-only / default-worker    | ████████████████████████ 1.507 ms
scheduler-only / high-precision    | ██ 0.108 ms
worker-chain / default-worker      | ████████████████████████████████████████ 2.950 ms
worker-chain / high-precision      | █████ 0.360 ms
```

#### Windows Redis 稳态精度图（avg abs skew，越低越好）

```text
memory-high-precision (2G)   | ████████████ 1.68 ms
redis-high-precision (2G)    | ██████████████████████ 3.03 ms
memory-high-precision (4G)   | ███████ 1.03 ms
redis-high-precision (4G)    | █████████████████████████ 3.51 ms
```

#### WSL2 Ubuntu Redis 参考图（avg abs skew，越低越好）

```text
redis-default-worker (4轮)    | ███████████████████████ 6.47 ms
redis-high-precision (4轮)    | ██████████████ 4.00 ms
```

### 3.6 当前可对外表达的精度结论

1. **内存 Worker 严格 A/B**：当前最准的本地结果是 `high-precision 0.360ms`，明显优于 `default-worker 2.950ms`。
2. **调度器本体 A/B**：单看调度器，高精度时间轮 `0.108ms`，明显优于默认调度器 `1.507ms`。
3. **Windows 本机 Redis**：高精度 Worker 当前稳态大致在 **3ms 级**。
4. **WSL2 Ubuntu Redis 参考值**：高精度 Worker 当前大致在 **4ms 级**，仍优于 Redis 默认 Worker 的 **6.47ms**。

## 4. 吞吐、CPU、堆内存

### 4.1 Windows 11 全量 benchmark

| 场景 | avg tasks/s | avg CPU% | peak heap (MiB) |
| --- | ---: | ---: | ---: |
| memory-default-worker | 9944.55 | 5.93 | 1201.53 |
| memory-high-precision | 10374.77 | 10.34 | 1202.15 |
| redis-default-worker | 5419.27 | 12.21 | 1222.46 |
| redis-high-precision | 5909.85 | 16.35 | 1214.90 |

#### Windows 吞吐图（tasks/s，越高越好）

```text
memory-default-worker    | ███████████████████████ 9944.55 tasks/s
memory-high-precision    | ████████████████████████ 10374.77 tasks/s
redis-default-worker     | █████████████ 5419.27 tasks/s
redis-high-precision     | ██████████████ 5909.85 tasks/s
```

### 4.2 WSL2 Ubuntu 全量 benchmark

| 场景 | avg tasks/s | avg CPU% | peak heap (MiB) |
| --- | ---: | ---: | ---: |
| memory-default-worker | 32000.43 | 13.60 | 1086.57 |
| memory-high-precision | 33263.78 | 16.24 | 1207.76 |
| redis-default-worker | 4676.51 | 7.02 | 1222.86 |
| redis-high-precision | 4711.16 | 11.29 | 1218.99 |

#### WSL2 Ubuntu 吞吐图（tasks/s，越高越好）

```text
memory-default-worker    | ███████████████████████ 32000.43 tasks/s
memory-high-precision    | ████████████████████████ 33263.78 tasks/s
redis-default-worker     | ███ 4676.51 tasks/s
redis-high-precision     | ███ 4711.16 tasks/s
```

### 4.3 吞吐结论

1. **Windows 本机**
   - 内存模式约 **1 万 tasks/s**
   - Redis 模式约 **5400 ~ 5900 tasks/s**
2. **WSL2 Ubuntu**
   - 内存模式约 **3.2 万 ~ 3.3 万 tasks/s**
   - Redis 模式约 **4670 ~ 4710 tasks/s**
3. **Redis 模式的吞吐瓶颈主要在单实例 Redis 消息链路**
   - 同样的 Worker 本地执行流程下，Redis 默认 / 高精度两种执行器吞吐差异不大
   - 但一切换到 Redis，吞吐会明显低于内存模式
   - 这说明当前 workload 下，主要瓶颈不是本地等待调度器，而是 **单实例 Redis 的消息通道、序列化/反序列化、消费分发**

## 5. 资源代价

1. 本轮 Windows 与 WSL2 的 Redis 压测峰值堆都大致落在 **1.2 GiB** 左右。
2. Windows 本机 Redis 高精度吞吐略高于 Redis 默认，但 CPU 也更高。
3. WSL2 下 Redis 两种执行器吞吐接近，进一步说明本地执行器不是 Redis 吞吐主瓶颈。

## 6. 产物位置

| 产物 | 路径                                          |
| --- |---------------------------------------------|
| Windows 全量 benchmark | `docs/performance/performance/`                  |
| Windows memory-only Worker A/B | `docs/performance/memory-worker-precision-study/`     |
| Windows 2G 长尾复测 | `docs/performance/tail-study/windows-2g/`             |
| Windows 4G 长尾复测 | `docs/performance/tail-study/windows-4g/`             |
| WSL2 Ubuntu 全量 benchmark | `docs/performance/wsl-study/linux-2g/`                |
| Redis 高精度专项 study | `docs/performance/performance/redis-precision-study/` |

## 7. 补充说明

- 如果需要看 Redis 高精度 Worker 的细粒度参数研究，参考 [`docs/redis-high-precision-study.md`](docs/redis-high-precision-study.md)
- 如果需要看历史暴露问题与复测结论，参考 [`docs/exposed-issues-log.md`](docs/exposed-issues-log.md)
- 如果需要看时间同步修复细节，参考 [`docs/time-sync-ab-report.md`](docs/time-sync-ab-report.md)
