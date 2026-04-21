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
 *         若 {@link TimeProvider} 为 {@link SystemTimeProvider} 实例，则退化为直接返回本地系统时间，
 *         不启动后台线程，不进行偏移计算，仅保留单调性保护，以节省资源。
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

    private static final long DEFAULT_SYNC_INTERVAL_MS = 1000L;
    private static final int DEFAULT_SAMPLE_COUNT = 3;
    private static final long LARGE_OFFSET_WARN_MS = 1000L;

    private final boolean isSystemTimeProvider;
    private final TimeProvider timeProvider;
    private final long syncIntervalMs;
    private final int sampleCount;
    private final LongSupplier wallClockMsSupplier;
    private final LongSupplier nanoTimeSupplier;
    private final long monotonicBaseWallClockMs;
    private final long monotonicBaseNanoTime;
    private final AtomicLong offsetMs = new AtomicLong(0L);
    private final AtomicLong lastReturnedTimeMs = new AtomicLong(Long.MIN_VALUE);
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ScheduledExecutorService scheduler;
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

    @Override
    public long currentTimeMillis() {
        long adjusted;
        if (isSystemTimeProvider) {
            adjusted = timeProvider.currentTimeMillis();
        } else {
            adjusted = monotonicTimeMillis() + offsetMs.get();
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

    public synchronized long syncNow() {

        if (isSystemTimeProvider) {
            return 0;
        }

        long totalOffset = 0L;
        int successCount = 0;
        long bestOffset = 0L;
        long bestRttNs = Long.MAX_VALUE;

        for (int i = 0; i < sampleCount; i++) {
            try {
                long localStartNs = nanoTimeSupplier.getAsLong();
                long remoteTimeMs = timeProvider.currentTimeMillis();
                long localEndNs = nanoTimeSupplier.getAsLong();
                long rttNs = localEndNs - localStartNs;
                long midpointLocalNs = localStartNs + (rttNs / 2L);
                long midpointLocalMs = monotonicTimeMillisAt(midpointLocalNs);
                long sampleOffset = remoteTimeMs - midpointLocalMs;
                totalOffset += sampleOffset;
                if (rttNs < bestRttNs) {
                    bestRttNs = rttNs;
                    bestOffset = sampleOffset;
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

        long averageOffset = Math.round(totalOffset / (double) successCount);
        long newOffset = bestOffset;
        long oldOffset = offsetMs.getAndSet(newOffset);
        if (Math.abs(newOffset - oldOffset) >= LARGE_OFFSET_WARN_MS) {
            log.warn("Large time offset change detected: old={}ms, new={}ms, avg={}ms, bestRtt={}ns",
                    oldOffset, newOffset, averageOffset, bestRttNs);
        }
        return newOffset;
    }

    @Override
    public synchronized void start(TockContext context) {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        if (isSystemTimeProvider) {
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
        if (!running.compareAndSet(true, false)) {
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

    public boolean isRunning() {
        return running.get();
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

    private static MonotonicAnchor captureMonotonicAnchor(LongSupplier wallClockMsSupplier, LongSupplier nanoTimeSupplier) {
        long startNs = nanoTimeSupplier.getAsLong();
        long wallClockMs = wallClockMsSupplier.getAsLong();
        long endNs = nanoTimeSupplier.getAsLong();
        return new MonotonicAnchor(wallClockMs, startNs + ((endNs - startNs) / 2L));
    }

    private static final class MonotonicAnchor {
        private final long wallClockMs;
        private final long nanoTime;

        private MonotonicAnchor(long wallClockMs, long nanoTime) {
            this.wallClockMs = wallClockMs;
            this.nanoTime = nanoTime;
        }
    }
}
