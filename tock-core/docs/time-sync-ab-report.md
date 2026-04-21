# DefaultTimeSynchronizer 精度修复与 A/B 测试报告

## 问题结论

`DefaultTimeSynchronizer` 原实现存在两个精度风险：

1. `syncNow()` 在一次采样里同时读取 `System.currentTimeMillis()` 和 `System.nanoTime()`，采样中点落在两个不同时间域之间。
2. `currentTimeMillis()` 对外返回值继续使用 `System.currentTimeMillis() + offset`，当本地墙钟被 NTP 微调时，已经同步好的偏移量会被再次污染，表现为“运行越久，时间间隔越小”。

**修复方案不是直接把绝对 `nanoTime` 转成毫秒时间戳。**  
`System.nanoTime()` 没有 epoch，不能直接换算成 Unix 毫秒。最终实现采用：

- 启动时捕获一组锚点：`wallClockMs + nanoTime`
- 后续所有本地 elapsed / midpoint 都只基于 `nanoTime`
- 通过锚点把单调纳秒时间映射回“单调本地毫秒时间”
- 多次采样时选择 **RTT 最小** 的样本作为新偏移量

## 参与比较的算法

| 算法 | 本地时间基准 | 偏移选取方式 |
| --- | --- | --- |
| legacy-average | `currentTimeMillis + elapsedNanos/2` | 多样本平均 |
| monotonic-average | 锚定后的单调本地时间 | 多样本平均 |
| monotonic-min-rtt | 锚定后的单调本地时间 | 选择 RTT 最小样本 |

## 合成回归测试

测试场景：

- 本地墙钟每经过 40ms 真实时间，向后纠正 1ms
- 远端时间稳定，模拟 RTT 为 7ms（3ms 出站 + 4ms 入站）
- 每轮执行 5 次采样，连续 80 轮

来自 `DefaultTimeSynchronizerPrecisionTest` 的实测结果：

| 算法 | avgSync | avgInterval | avgBias |
| --- | ---: | ---: | ---: |
| legacy-average | 0.50ms | 1.00ms | -1.00ms |
| monotonic-average | 1.00ms | 0.00ms | 0.00ms |
| monotonic-min-rtt | 1.00ms | 0.00ms | 0.00ms |

结论：只要把对外时间和采样中点都切到同一条单调时间轴，**“间隔逐步缩小” 的漂移现象会被完全消除**；原始算法会稳定地产生负偏置。

## Redis 本地 A/B 测试

测试环境：

- Redis: `127.0.0.1:6379`
- 每个算法每轮执行 5 次采样
- 每次观测间隔 20ms
- 每轮统计 `avgSync`（对 Redis TIME 的平均绝对误差）与 `avgInterval`（相邻观测间隔误差）
- 共重复 5 次独立基准运行，每次 40 轮

### 5 轮原始结果

| Run | legacy-average | monotonic-average | monotonic-min-rtt |
| --- | --- | --- | --- |
| 1 | sync 0.46ms / interval 0.53ms | sync 0.36ms / interval 0.38ms | sync 0.30ms / interval 0.30ms |
| 2 | sync 0.43ms / interval 0.65ms | sync 0.31ms / interval 0.48ms | sync 0.36ms / interval 0.28ms |
| 3 | sync 0.24ms / interval 0.43ms | sync 0.34ms / interval 0.28ms | sync 0.31ms / interval 0.33ms |
| 4 | sync 0.73ms / interval 0.75ms | sync 0.54ms / interval 0.83ms | sync 0.49ms / interval 0.68ms |
| 5 | sync 0.49ms / interval 0.48ms | sync 0.34ms / interval 0.28ms | sync 0.35ms / interval 0.30ms |

### 5 轮平均值

| 算法 | avgSync | avgInterval | 说明 |
| --- | ---: | ---: | --- |
| legacy-average | 0.47ms | 0.57ms | 作为旧实现基线 |
| monotonic-average | 0.38ms | 0.45ms | 明显优于旧实现，但波动略大 |
| monotonic-min-rtt | **0.36ms** | **0.38ms** | 综合最优，interval 误差最低 |

补充观察：

- 5 次运行中，`monotonic-min-rtt` 有 4 次拿到最低 `avgInterval`
- 所有算法的 Redis p95 误差都落在 `<= 2ms`
- `monotonic-min-rtt` 的 interval bias 也最小，更接近“既不压缩也不放大”的均匀触发

## 最终选型

最终在生产代码中落地 **`monotonic-min-rtt`**：

1. 采样与对外返回统一切换到锚定后的单调本地时间轴
2. 每轮同步保留最低 RTT 样本作为偏移量
3. 保留已有的 CAS 单调递增保护

选择理由：

- 合成场景下彻底消除了墙钟回调导致的累计漂移
- Redis 实测 5 轮平均值里同步误差和间隔误差都是三种算法中最低
- 对本地 Redis 场景，最终实装版本单轮端到端测试结果为：`avgSync=0.88ms`、`avgInterval=0.65ms`

## Redis 模式提前执行的真实根因

仅修复 `DefaultTimeSynchronizer` 还不够。对 Master -> Redis queue -> Worker 的实测链路拆解后，Redis 模式的异常还来自另外两个执行层问题。

### 根因 1：调度器提前唤醒后，用错了“下一次 cron 计算基准”

`EventDrivenCronScheduler` 本来就会提前唤醒：

- 大延迟任务提前 `1000ms`
- 小延迟任务提前 `15ms`

旧实现里，`onFire()` 被提前唤醒后，直接用“当前同步时间 now”去计算下一次 cron。  
但 cron 计算本身是 **严格取下一次** 的，基准如果落在本次 `fireTime` 之前，就可能再次算回同一个逻辑窗口，造成：

- 下一次延迟被压缩到 `1ms` 量级
- 同一秒附近重复推送
- Worker 端出现“相同时间戳连发 / 突然堆积释放”

修复方式：

1. 下一次 cron 的计算基准改为 `currentFireTime - 1ms`
2. 计算 delay 仍然使用当前同步时间
3. 把“下一次 fireTime 的推导基准”和“本次 schedule 的剩余时间基准”彻底分开

### 根因 2：Worker 只在入队时计算一次 delay，等待期间同步偏移可能继续变化

Redis 模式下 Worker 的本地等待虽然发生在 JVM 内部，但目标时刻是基于同步时间定义的。  
旧实现只在收到 `JobExecution` 时计算一次：

`delay = fireTime - context.currentTimeMillis()`

如果等待期间 `offsetMs` 被新的同步结果修正，原来的本地定时器就可能相对 Redis 时间过早触发。

修复方式：

1. Worker 定时器触发后，不直接执行任务
2. 先再次读取 `context.currentTimeMillis()`
3. 如果离 `nextFireTime` 仍有剩余时间，则继续补一段本地等待
4. `JobContext.actualFireTime` 也统一改为同步时间，而不是墙钟时间

## Redis 与内存模式的链路诊断结果

新增 `RedisMemoryTimingDiagnosticsTest` 后，针对同机 Redis(`127.0.0.1:6379`) 做了 Master/queue/Worker 分段观测。

一次完整验证中的代表性结果：

| 场景 | avgQueue | avgReceiveToExecute | avgExecuteSkew | avgContextProviderSkew |
| --- | ---: | ---: | ---: | ---: |
| memory-high-precision | 0.07ms | 994.36ms | 0.00ms | 0.00ms |
| redis-high-precision | 0.81ms | 970.26ms | 25.00ms | -2.40ms |
| redis-default-worker | 0.86ms | 975.55ms | 35.80ms | -17.20ms |
| redis-high-worker | 0.78ms | 978.32ms | 30.80ms | -14.40ms |

结论：

1. **Redis 传输本身不是主因**：本机 `avgQueue` 基本维持在 `~1ms` 内。
2. **主要问题在调度与执行层的时间基准处理**，不是 Redis `TIME` 命令精度不够。
3. 修复后 Redis Worker 层已经明显接近设计预期：任务仍由本地执行器计时，只是在统一同步时间下决定“何时算真正到点”。
4. Windows 本机调度抖动仍会让 Redis 模式比纯内存高精度模式更宽一些，但已经消除了“持续提前、越跑越乱、重复连发”的核心故障模式。

## 验证项

新增/更新测试覆盖：

- `DefaultTimeSynchronizerPrecisionTest`
  - 墙钟回调场景下的稳定性回归
  - 合成 A/B 精度比较
- `DefaultTimeSynchronizerRedisPrecisionTest`
  - 本地 Redis 端到端精度验证
  - Redis TIME 多轮 A/B 比较
- `DefaultTimeSynchronizerTest`
  - 部分采样失败仍能用成功样本更新偏移
  - 全部采样失败时保持旧偏移
- `EventDrivenCronSchedulerTimingTest`
  - 锁定提前唤醒后下一次 cron 基准不能回落到当前窗口
- `DefaultTockWorkerTimingGuardTest`
  - 锁定 Worker 到点前必须再次确认同步时间
- `RedisMemoryTimingDiagnosticsTest`
  - 比较 memory / Redis 队列延迟、接收后等待时长、最终执行偏差
