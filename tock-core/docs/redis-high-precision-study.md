# Redis 模式下高精度时间轮精度研究

> **状态说明**
>
> 这份文档记录的是一项**历史性暴露问题**的专项研究，不代表当前版本仍存在同样缺陷。  
> 当前代码已经完成修复：**高精度时间轮在分布式时间源下，如果业务没有手动覆盖 `advanceNanos`，默认会自动切到 `1ms`**。  
> 当前版本的最终能力状态请优先参考 [`../PERFORMANCE.md`](../PERFORMANCE.md)。

本文讨论的是：**为什么 `redis-high-precision` 在先前的性能报告里看起来比 `redis-default-worker` 更差，以及这个历史现象是如何被定位和修复的。**

## 修复前 / 当前状态

| 状态 | 说明 |
| --- | --- |
| 修复前 | `redis-high-precision` 在早期报告中一度表现出比 `redis-default-worker` 更差的结果，容易让人误解为 Redis 模式下高精度执行器本身有问题 |
| 根因 | 不是 Redis 模式天然更差，而是当时的默认 `advance` 参数对分布式时间源场景不适配 |
| 当前 | 默认行为已修复；在分布式时间源下，高精度时间轮会自动采用更稳的 `1ms` 默认值 |
| 当前结论 | 修复后，Redis 高精度默认行为已经回到优于 Redis 默认调度器的状态 |

## 结论先行

最终结论是：

1. **`5.69ms vs 7.94ms` 不是稳定结论。**  
   在 10 轮分段计时 study 里，问题被收敛到了“默认 advance 参数不适配”，不是 Redis 模式天然更差。

2. **高精度时间轮本身没有回退到系统墙钟，也没有走错时间源。**  
   Redis / Memory 两种模式下：
   - `DefaultTockWorker` 都是用 `context.currentTimeMillis()` 计算初始 delay
   - `HighPrecisionWheelTaskScheduler` 内部只用 `System.nanoTime()` 做本地等待
   - 这部分实现没有“Redis 模式偷偷退回系统时间”的逻辑缺陷

3. **主要问题是参数不适配，不是链路缺陷。**  
   默认 `200us advance` 在 Memory 模式很好，但在 Redis 模式下对“同步时钟抖动 + Windows 调度噪声”的容错偏小。  
   因此当前代码已升级为：**高精度时间轮在分布式时间源下，如果业务没有手动覆盖 advance，则自动切到 `1ms`。**

4. **改造后的默认行为已经回到“Redis 高精度优于 Redis 默认调度器”。**  
   改造后再跑 10 轮分段计时 study：
   - `redis-default-worker`：`0.45ms`
   - `redis-high-precision-auto-default`：`0.32ms`
   - 显式 `redis-high-precision-1000us-spin`：`0.38ms`

5. **Windows 上的最终自旋不是主因。**  
   实验结果显示：
   - `200us + no-spin` 比 `200us + spin` 更差
   - `1000us + no-spin` 也比 `1000us + spin` 更差  
   说明“禁用自旋”没有带来系统性收益。

6. **`2ms advance` 虽然平均精度更高，但不可取。**  
   它触发了大量 reschedule storm（平均 `551.73` 次），属于“靠反复提前唤醒刷出精度”，CPU 成本和行为风险都太高。

## 测试方法

新增入口：

- `src/test/java/com/clmcat/tock/worker/RedisHighPrecisionStudyMain.java`

新增 Worker 诊断钩子：

- `DefaultTockWorker.onExecutionReceived(...)`
- `DefaultTockWorker.onExecutionScheduled(...)`
- `DefaultTockWorker.onScheduledCallback(...)`
- `DefaultTockWorker.onExecutionRescheduled(...)`
- `DefaultTockWorker.onExecutionDue(...)`

采集分段：

1. Master push
2. Queue dispatch
3. Worker receive
4. Scheduler submit
5. Scheduler callback
6. Due check pass
7. Business execute

测试配置：

- Cron：`*/1 * * * * ?`
- 每轮：2 次 warmup + 6 次测量
- Redis 场景：10 轮
- Memory 参考场景：4 轮
- JVM：`-Xms2g -Xmx2g`

## 聚合结果

| Scenario | rounds | avg round abs skew (ms) | p95 round abs skew | avg queue | avg final wait overrun | avg reschedules | avg positive remaining | avg due->execute |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| memory-default-worker | 4 | 0.38 | 1.00 | 0.07 | -0.23 | 5.25 | 0.75 | 0.00 |
| memory-high-precision | 4 | 0.08 | 0.17 | 0.06 | 0.31 | 0.08 | 0.50 | 0.00 |
| redis-default-worker | 10 | 0.83 | 1.50 | 0.54 | -0.29 | 6.30 | 1.04 | 0.00 |
| redis-high-precision-auto-default | 10 | 0.32 | 0.67 | 0.46 | -0.63 | 0.82 | 0.95 | 0.00 |
| redis-high-precision-200us-spin | 10 | 1.05 | 2.67 | 0.44 | 0.26 | 0.13 | 0.50 | 0.00 |
| redis-high-precision-200us-nosspin | 10 | 1.88 | 2.50 | 0.42 | 1.23 | 0.02 | 0.10 | 0.00 |
| redis-high-precision-1000us-spin | 10 | 0.28 | 0.83 | 0.38 | -0.63 | 0.63 | 1.07 | 0.00 |
| redis-high-precision-2000us-spin | 10 | 0.18 | 0.50 | 0.37 | -0.99 | 551.73 | 1.07 | 0.00 |
| redis-high-precision-1000us-nosspin | 10 | 0.87 | 1.50 | 0.40 | 0.30 | 0.30 | 0.50 | 0.00 |

## 关键观察

## 1. Redis 队列不是问题根因

`avg queue` 在 Redis 场景里稳定在 `0.38ms ~ 0.54ms`。

这说明：

- Redis 序列化 + List/BLPOP 链路确实比 Memory 多一点成本
- 但它远不足以解释“高精度时间轮为什么更差”

所以“Redis 队列实现不同导致显著掉精度”不是主结论。

## 2. 高精度轮没有用错时间源

代码路径核对结果：

- `DefaultTockWorker.executeJob()` 用 `context.currentTimeMillis()` 算 `delay`
- `HighPrecisionWheelTaskScheduler.schedule()` 只基于 `System.nanoTime()` 做本地等待
- `executeWhenDue()` 再次用 `context.currentTimeMillis()` 复核是否真正到点

所以并不存在：

- HighPrecisionWheelTaskScheduler 在 Redis 模式下回退到 `System.currentTimeMillis()`
- 或者 Memory / Redis 使用了两套不同的当前时间语义

## 3. 200us 默认值对 Redis 场景偏保守

`200us + spin` 的表现：

- `1.05ms`，略差于默认调度器 `0.83ms`

它的问题不是“严重过晚”，而是：

- advance 太小
- 最终回调落点更贴近本地等待极限
- 对 Redis 场景的毫秒级离散抖动容错不足

换句话说，**Memory 模式调出来的 200us，不等于 Redis 模式的最优点**。

## 4. 1ms advance 才是这台 Windows 机器上的更优点

`1000us + spin` 的结果：

- 平均绝对偏差：`0.28ms`
- p95：`0.83ms`

已经优于：

- `redis-default-worker` 的 `0.83ms`
- `redis-high-precision-200us-spin` 的 `1.05ms`

这说明根因更接近：

> **高精度轮默认参数没有针对 Redis + Windows 的组合场景做校准。**

这也是为什么当前代码把某些场景下的默认值改成了 `1ms`。

## 5. 禁用最终自旋没有证明自己更优

结果如下：

| Variant | avg round abs skew (ms) |
| --- | ---: |
| `200us + spin` | 1.05 |
| `200us + no-spin` | 1.88 |
| `1000us + spin` | 0.28 |
| `1000us + no-spin` | 0.87 |

因此：

- 没有证据表明“Windows 自旋行为就是主因”
- 反而更像是 **advance 选择更关键**

## 6. 2ms advance 会制造 reschedule storm

`2000us + spin` 虽然平均偏差最低，但平均 reschedule 达到 `551.73`。

原因来自当前逻辑组合：

1. `executeWhenDue()` 按毫秒计算 `remaining`
2. 如果 `remaining = 1ms`
3. 高精度时间轮又把 `advanceNanos = 2ms`
4. 那么 `schedule(delay=1ms)` 会被内部扣成 `0ms`
5. 回调立刻再次触发，但当前时间可能还没真正跨过目标点
6. 于是形成反复 reschedule

这不是一个健康的配置点。

## 根因判断

按题目里的四个选项逐项回答：

### 1. 只是偶然环境波动吗？

**不是完全偶然，但之前那组 5.69ms / 7.94ms 的差距明显被放大了。**

专门的 10 轮 study 里，差距缩小到：

- 默认：`0.83ms`
- 高精度 200us：`1.05ms`

因此：

- “高精度在 Redis 下略差一点”是可复现的
- 但“高出 2ms 以上”不是这台机器上的稳定结论

### 2. 是高精度时间轮参数不适配吗？

**是，且这是主结论。**

直接证据：

- `200us + spin`：1.05ms
- `1000us + spin`：0.28ms

只改 advance，就把 Redis 高精度从“略差于默认”变成了“明显优于默认”。

### 3. 是 Redis 链路和内存链路有隐藏差异吗？

**链路有差异，但不是根因。**

差异主要是：

- Redis 队列约多出 `0.3 ~ 0.5ms`
- Redis 时间基准来自同步时钟而非本地系统时钟

但代码路径上没有发现高精度轮“走错时间源”或“Redis 模式额外绕路”的缺陷。

### 4. 是 Windows 自旋副作用吗？

**没有证据证明它是主因。**

从数据看：

- no-spin 没有带来系统性改进
- 某些组合下还更差

更合理的判断是：

- Windows 调度行为会影响最终最优参数
- 但主变量仍然是 `advanceNanos`，不是“要不要 spin”

## 建议

## 1. 当前默认行为

当前代码已经内置以下策略：

- 某些运行场景下，高精度时间轮默认 advance 会自动调整到 `1ms`
- 如果业务已经显式设置 `advanceNanos`
  - Tock 不会覆盖你的值

## 2. 建议的生产配置

如果你的生产或压测环境和当前场景接近，直接使用：

```java
TaskSchedulers.highPrecision("worker")
```

即可获得当前默认的分布式自动调优。  
只有在你自己的 benchmark 证明有必要时，再显式调用：

```java
HighPrecisionWheelTaskScheduler scheduler = TaskSchedulers.highPrecision("worker");
scheduler.setAdvanceNanos(1_000_000L);
```

## 3. 不建议的配置

- `200us + no-spin`：平均更差
- `2ms + spin`：reschedule storm 明显

## 4. 平台建议

该 study **没有 Linux 对照结果**，因为执行环境只有 Windows。

因此最终建议是：

- 若生产目标环境是 Linux，请在 Linux 上复跑同一 study
- 再决定是否把 `1ms advance` 固化成业务默认值

## 交付物

代码：

- `src/test/java/com/clmcat/tock/worker/RedisHighPrecisionStudyMain.java`
- `src/main/java/com/clmcat/tock/worker/DefaultTockWorker.java` 诊断钩子

运行产物：

- `target/performance/redis-precision-study/redis-precision-study.md`
- `target/performance/redis-precision-study/redis-precision-study.csv`
- `target/performance/redis-precision-study/redis-precision-study.txt`
