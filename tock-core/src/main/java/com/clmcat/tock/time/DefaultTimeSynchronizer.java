package com.clmcat.tock.time;

import com.clmcat.tock.TockContext;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 默认的时间同步器实现，提供单调递增且经过远程时间源校正的当前时间。
 * <p>
 * 该类通过定期采样远程时间源（由 {@link TimeProvider} 提供），计算本地时钟与远程时钟的偏移量，
 * 并将偏移应用于本地系统时间，从而为调度器等组件提供统一的时间基准。
 * </p>
 *
 * <h3>核心算法</h3>
 * <ul>
 *     <li><b>采样中点补偿</b>：
 *         每次采样记录本地请求开始与结束的纳秒时间，计算 RTT 中点对应的本地毫秒时间，
 *         与远程时间戳差值作为单次偏移；多次采样时优先采用 RTT 最小的样本，以降低网络抖动和链路非对称带来的偏差。
 *     </li>
 *     <li><b>单调递增保证</b>：
 *         使用 CAS 自旋和上一次返回值缓存，确保并发调用返回的时间戳不会小于前一次，
 *         避免因系统时钟回拨或偏移突变导致的时间倒退，保障调度器顺序性。
 *     </li>
 *     <li><b>周期性同步</b>：
 *         后台线程以固定间隔执行采样，动态更新偏移量，使同步时间与远程源保持最终一致。
 *     </li>
 *     <li><b>系统时间优化</b>：
 *         若 {@link TimeProvider} 为 {@link SystemTimeProvider} 实例，则退化为基于本地墙钟锚点 +
 *         单调时钟生成时间，不启动后台线程，不进行偏移计算，以避免墙钟跳变与本地等待逻辑失配。
 *     </li>
 * </ul>
 *
 * <h3>使用场景</h3>
 * 适用于需要跨节点时间一致、且要求时间戳单调递增的分布式调度系统。
 * 可作为 {@link com.clmcat.tock.TockContext} 中的时间基准注入调度器和 Worker。
 *
 * @author clmcat
 * @see TimeSynchronizer
 * @see TimeProvider
 * @see SystemTimeProvider
 */
@Slf4j
public class DefaultTimeSynchronizer implements TimeSynchronizer {

    /** 默认同步间隔：1 秒 */
    private static final long DEFAULT_SYNC_INTERVAL_MS = 1000L;
    /** 默认每次同步的采样次数 */
    private static final int DEFAULT_SAMPLE_COUNT = 3;
    /** 偏移量变化超过此阈值时记录警告日志 */
    private static final long LARGE_OFFSET_WARN_MS = 1000L;
    /** 偏移量单次调整的最大步长（毫秒），用于平滑跳变 */
    private static final long MAX_OFFSET_ADJUST_STEP_MS = 5L;
    /** 本轮样本允许的最大跨样本变化（毫秒） */
    private static final long MAX_STABLE_SAMPLE_DRIFT_MS = 1L;
    /** 本轮样本允许的最大离散度（毫秒） */
    private static final long MAX_SAMPLE_SPREAD_MS = 2L;
    /** 连续稳定多少轮之后，才允许向目标偏移继续推进 */
    private static final int REQUIRED_STABLE_SAMPLE_ROUNDS = 2;

    /** 是否使用系统时间提供者（本地时间），若为 true 则不进行远程同步 */
    private final boolean isSystemTimeProvider;
    /** 远程时间源（如 Redis） */
    private final TimeProvider timeProvider;
    /** 实际使用的同步间隔（毫秒） */
    private final long syncIntervalMs;
    /** 每次同步的采样次数 */
    private final int sampleCount;
    /** 提供当前系统毫秒时间（墙钟）的供应器 */
    private final LongSupplier wallClockMsSupplier;
    /** 提供当前单调纳秒时间的供应器 */
    private final LongSupplier nanoTimeSupplier;
    /** 基准锚点：本地墙钟毫秒 */
    private final long monotonicBaseWallClockMs;
    /** 基准锚点：对应的单调纳秒时间 */
    private final long monotonicBaseNanoTime;
    /** 当前应用的偏移量（毫秒），对外时间 = 单调本地时间 + offsetMs */
    private final AtomicLong offsetMs = new AtomicLong(0L);
    /** 上一次返回的时间戳，用于保证单调递增 */
    private final AtomicLong lastReturnedTimeMs = new AtomicLong(Long.MIN_VALUE);
    /** 偏移量是否已经过首次初始化 */
    private final AtomicBoolean offsetInitialized = new AtomicBoolean(false);
    /** 同步器是否正在运行 */
    private final AtomicBoolean started = new AtomicBoolean(false);
    /** 上一次参与稳定性判断的样本偏移 */
    private long lastSampledOffsetMs = Long.MIN_VALUE;
    /** 上一次同步发生时的单调纳秒时间 */
    private long lastSyncNanoTime = Long.MIN_VALUE;
    /** 连续稳定样本轮次 */
    private int stableSampleRounds = 0;

    /** 执行周期性同步的调度线程池 */
    private ScheduledExecutorService scheduler;
    /** 周期性同步任务的 Future，用于取消 */
    private ScheduledFuture<?> future;

    public DefaultTimeSynchronizer() {
        this(new SystemTimeProvider());
    }

    public DefaultTimeSynchronizer(TimeProvider timeProvider) {
        this(timeProvider, DEFAULT_SYNC_INTERVAL_MS, DEFAULT_SAMPLE_COUNT);
    }

    public DefaultTimeSynchronizer(TimeProvider timeProvider, long syncIntervalMs, int sampleCount) {
        this(timeProvider, syncIntervalMs, sampleCount, System::currentTimeMillis, System::nanoTime);
    }

    DefaultTimeSynchronizer(TimeProvider timeProvider, long syncIntervalMs, int sampleCount,
                            LongSupplier wallClockMsSupplier, LongSupplier nanoTimeSupplier) {
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider is null");
        this.syncIntervalMs = syncIntervalMs > 0 ? syncIntervalMs : DEFAULT_SYNC_INTERVAL_MS;
        this.sampleCount = Math.max(1, sampleCount);
        this.isSystemTimeProvider = timeProvider instanceof SystemTimeProvider;
        this.wallClockMsSupplier = Objects.requireNonNull(wallClockMsSupplier, "wallClockMsSupplier is null");
        this.nanoTimeSupplier = Objects.requireNonNull(nanoTimeSupplier, "nanoTimeSupplier is null");
        MonotonicAnchor anchor = captureMonotonicAnchor(this.wallClockMsSupplier, this.nanoTimeSupplier);
        this.monotonicBaseWallClockMs = anchor.wallClockMs;
        this.monotonicBaseNanoTime = anchor.nanoTime;
        syncNow();
    }

    /**
     * 获取经过同步和单调性保护后的当前时间戳（毫秒）。
     * <p>
     * 实现公式：adjusted = monotonicTimeMillis() + offsetMs。
     * 再通过 CAS 保证返回值不会小于之前的调用结果，即使底层 offsetMs 意外减小。
     * </p>
     */
    @Override
    public long currentTimeMillis() {
        long adjusted = monotonicTimeMillis();
        if (!isSystemTimeProvider) {
            adjusted += offsetMs.get();
        }
        for (;;) {
            long previous = lastReturnedTimeMs.get();
            long candidate = adjusted <= previous ? previous : adjusted;
            if (lastReturnedTimeMs.compareAndSet(previous, candidate)) {
                return candidate;
            }
        }
    }

    @Override
    public long offset() {
        return offsetMs.get();
    }

    /**
     * 执行一次同步采样，更新内部偏移量。
     * <p>
     * 采样过程：
     * <ol>
     *   <li>进行 sampleCount 次采样，每次记录 RTT 中点对应的本地单调毫秒时间，与远程时间比较得到样本偏移。</li>
     *   <li>优先使用最小 RTT 样本；如果本轮样本离散度明显偏大，则退回到均值作为代表偏移，避免单点抖动。</li>
     *   <li>调用 stabilizeOffset 对偏移量进行平滑处理，避免突变。</li>
     *   <li>更新 offsetMs，并记录显著变化。</li>
     * </ol>
     * </p>
     *
     * @return 本次同步后应用的新偏移量
     */
    public synchronized long syncNow() {

        if (isSystemTimeProvider) {
            return 0;
        }

        long totalOffset = 0L;
        int successCount = 0;
        long bestOffset = 0L;
        long bestRttNs = Long.MAX_VALUE;
        long minSampleOffset = Long.MAX_VALUE;
        long maxSampleOffset = Long.MIN_VALUE;
        long syncStartNs = nanoTimeSupplier.getAsLong();

        for (int i = 0; i < sampleCount; i++) {
            try {
                long localStartNs = nanoTimeSupplier.getAsLong();
                long remoteTimeMs = timeProvider.currentTimeMillis();
                long localEndNs = nanoTimeSupplier.getAsLong();
                long rttNs = localEndNs - localStartNs; // 本地请求的 RTT 纳秒时间
                long midpointLocalNs = localStartNs + (rttNs / 2L); // 当前纳秒
                long midpointLocalMs = monotonicTimeMillisAt(midpointLocalNs); // 当前毫秒 monotonicBaseWallClockMs + TimeUnit.NANOSECONDS.toMillis(nanoTime - monotonicBaseNanoTime);
                long sampleOffset = remoteTimeMs - midpointLocalMs; // 远程时间 - 当前毫秒 = 偏移
                totalOffset += sampleOffset;
                if (sampleOffset < minSampleOffset) {
                    minSampleOffset = sampleOffset;
                }
                if (sampleOffset > maxSampleOffset) {
                    maxSampleOffset = sampleOffset;
                }
                if (rttNs < bestRttNs) { // 选择最小的 RTT 样本作为最佳偏移
                    bestRttNs = rttNs;
                    bestOffset = sampleOffset; // 最佳偏移值
                }
                successCount++;
            } catch (RuntimeException e) {
                log.warn("Time sync sample failed", e);
            }
        }

        if (successCount == 0) {
            log.error("Time sync failed, keeping previous offset={}", offsetMs.get());
            return offsetMs.get();
        }

        long averageOffset = Math.round(totalOffset / (double) successCount); // 平均偏移
        long oldOffset = offsetMs.get(); // 历史偏移
        long sampleSpreadMs = maxSampleOffset - minSampleOffset;
        long elapsedSinceLastSyncMs = lastSyncNanoTime == Long.MIN_VALUE
                ? syncIntervalMs
                : Math.max(1L, TimeUnit.NANOSECONDS.toMillis(syncStartNs - lastSyncNanoTime));
        long representativeOffset = sampleSpreadMs <= MAX_SAMPLE_SPREAD_MS ? bestOffset : averageOffset;
        boolean sampleStable = isSampleStable(representativeOffset, sampleSpreadMs, elapsedSinceLastSyncMs);
        updateStableSampleRounds(representativeOffset, sampleStable);
        long newOffset = stabilizeOffset(oldOffset, representativeOffset, bestRttNs, sampleStable, sampleSpreadMs, elapsedSinceLastSyncMs); // 新的偏移
        offsetMs.set(newOffset); // 设置
        lastSampledOffsetMs = representativeOffset;
        lastSyncNanoTime = syncStartNs;
        if (Math.abs(newOffset - oldOffset) >= LARGE_OFFSET_WARN_MS) {
            log.warn("Large time offset change detected: old={}ms, new={}ms, avg={}ms, bestRtt={}ns",
                    oldOffset, newOffset, averageOffset, bestRttNs);
        }
        return newOffset;
    }

    /**
     * 启动后台周期性同步任务。
     * <p>
     * 注意：若时间提供者为 {@link SystemTimeProvider}，则不会启动任何后台线程，所有同步操作均为空操作。
     * </p>
     *
     * @param context Tock 上下文（未使用，保留扩展）
     */
    @Override
    public synchronized void start(TockContext context) {
        if (isSystemTimeProvider) {
            started.set(false);
            return;
        }

        if (!started.compareAndSet(false, true)) {
            return;
        }

        scheduler = createScheduler();
        syncNow();
        future = scheduler.scheduleWithFixedDelay(() -> {
            try {
                syncNow();
            } catch (RuntimeException e) {
                log.error("Time synchronizer refresh failed", e);
            }
        }, syncIntervalMs, syncIntervalMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        if (future != null) {
            future.cancel(true);
            future = null;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    public boolean isStarted() {
        return started.get();
    }

    private ScheduledExecutorService createScheduler() {
        ThreadFactory factory = r -> {
            Thread thread = new Thread(r, "tock-time-sync");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadScheduledExecutor(factory);
    }

    private long monotonicTimeMillis() {
        return monotonicTimeMillisAt(nanoTimeSupplier.getAsLong());
    }

    private long monotonicTimeMillisAt(long nanoTime) {
        return monotonicBaseWallClockMs + TimeUnit.NANOSECONDS.toMillis(nanoTime - monotonicBaseNanoTime);
    }
    /**
     * 计算基于锚点的本地单调毫秒时间。
     * <p>
     * 原理：monotonicBaseWallClockMs + (currentNanoTime - monotonicBaseNanoTime) 的毫秒转换。
     * 该时间不受系统墙钟跳变影响，严格单调递增（前提是 nanoTime 单调）。
     * </p>
     */
    private static MonotonicAnchor captureMonotonicAnchor(LongSupplier wallClockMsSupplier, LongSupplier nanoTimeSupplier) {
        long startNs = nanoTimeSupplier.getAsLong();
        long wallClockMs = wallClockMsSupplier.getAsLong();
        long endNs = nanoTimeSupplier.getAsLong();
        return new MonotonicAnchor(wallClockMs, startNs + ((endNs - startNs) / 2L));
    }
    /**
     * 偏移量稳定化处理。
     * <p>
     * 主要目的：防止因网络瞬时抖动、Redis TIME 微小跳变或本地同步周期异常导致的偏移量剧烈变化，
     * 从而避免对外时间出现持续性的 5ms 级别锯齿。
     * </p>
     * <p>
     * 策略：
     * <ul>
     *   <li>首次采样直接采用，不做限幅。</li>
     *   <li>后续采样必须先通过稳定性检查：本轮样本离散度、与上轮样本的变化率、以及本地同步间隔都要处于可接受范围。</li>
     *   <li>稳定样本连续出现后，才允许以 MAX_OFFSET_ADJUST_STEP_MS 为上限向目标偏移推进。</li>
     *   <li>如果 Redis 时间本身持续抖动，则保持当前偏移不变，等待下一轮更稳定的样本。</li>
     * </ul>
     * </p>
     *
     * @param currentOffset 当前偏移量
     * @param sampledOffset 本次采样计算出的原始偏移量（最小 RTT 样本）
     * @param bestRttNs     最小 RTT 值（纳秒），仅用于日志
     * @param sampleStable  本轮样本是否稳定
     * @param sampleSpreadMs 本轮样本离散度（最大值 - 最小值）
     * @param elapsedSinceLastSyncMs 距离上次同步的本地单调时间间隔
     * @return 经过平滑处理后的新偏移量
     */
    private long stabilizeOffset(long currentOffset, long sampledOffset, long bestRttNs,
                                 boolean sampleStable, long sampleSpreadMs, long elapsedSinceLastSyncMs) {
        if (offsetInitialized.compareAndSet(false, true)) {
            return sampledOffset;
        }
        if (!sampleStable) {
            log.info("Skip unstable time offset sample: current={}ms, sampled={}ms, spread={}ms, elapsed={}ms",
                    currentOffset, sampledOffset, sampleSpreadMs, elapsedSinceLastSyncMs);
            return currentOffset;
        }
        long delta = sampledOffset - currentOffset;
        log.info("Old offset: {}, New Offset: {}, spread={}ms, elapsed={}ms",
                currentOffset, sampledOffset, sampleSpreadMs, elapsedSinceLastSyncMs);
        if (Math.abs(delta) <= MAX_OFFSET_ADJUST_STEP_MS) {
            return sampledOffset;
        }
        if (stableSampleRounds < REQUIRED_STABLE_SAMPLE_ROUNDS) {
            log.debug("Deferring time offset slew until the sample stays stable: current={}ms, sampled={}ms, stableRounds={}, bestRtt={}ns",
                    currentOffset, sampledOffset, stableSampleRounds, bestRttNs);
            return currentOffset;
        }
        long boundedOffset = currentOffset + Math.max(-MAX_OFFSET_ADJUST_STEP_MS, Math.min(MAX_OFFSET_ADJUST_STEP_MS, delta));
        log.warn("Clamped time offset adjustment: current={}ms, sampled={}ms, applied={}ms, bestRtt={}ns",
                currentOffset, sampledOffset, boundedOffset, bestRttNs);
        return boundedOffset;
    }

    private boolean isSampleStable(long sampledOffset, long sampleSpreadMs, long elapsedSinceLastSyncMs) {
        if (lastSampledOffsetMs == Long.MIN_VALUE || lastSyncNanoTime == Long.MIN_VALUE) {
            return true;
        }
        long sampleDelta = Math.abs(sampledOffset - lastSampledOffsetMs);
        long maxAllowedDelta = Math.max(1L, (elapsedSinceLastSyncMs * MAX_STABLE_SAMPLE_DRIFT_MS) / 1000L);
        return sampleDelta <= maxAllowedDelta;
    }

    private void updateStableSampleRounds(long sampledOffset, boolean sampleStable) {
        if (!sampleStable) {
            stableSampleRounds = 0;
            return;
        }
        if (lastSampledOffsetMs == Long.MIN_VALUE) {
            stableSampleRounds = 1;
            return;
        }
        long sampleDelta = Math.abs(sampledOffset - lastSampledOffsetMs);
        if (sampleDelta <= MAX_STABLE_SAMPLE_DRIFT_MS) {
            stableSampleRounds++;
        } else {
            stableSampleRounds = 1;
        }
    }

    private static final class MonotonicAnchor {
        private final long wallClockMs;
        private final long nanoTime;

        private MonotonicAnchor(long wallClockMs, long nanoTime) {
            this.wallClockMs = wallClockMs;
            this.nanoTime = nanoTime;
        }
    }

    /**
     * 重置偏移量初始化状态，强制下一次同步采用采样值作为新偏移量。
     * 适用于 Master 切换后需要快速重新对齐时间的场景。
     */
    @Override
    public void resetInitialization() {
        if (isSystemTimeProvider) {
            return;
        }
        offsetInitialized.set(false);
        stableSampleRounds = 0;
        lastSampledOffsetMs = Long.MIN_VALUE;
        lastSyncNanoTime = Long.MIN_VALUE;
        log.info("Time synchronizer initialization reset. Next sync will force adopt sampled offset.");
    }

    @Override
    public synchronized void forceReinitialize() {
        resetInitialization();
        syncNow(); // 立即采样并应用，建立新基准
        log.info("Time synchronizer forcibly reinitialized. New offset: {}ms", offsetMs.get());
    }
}
