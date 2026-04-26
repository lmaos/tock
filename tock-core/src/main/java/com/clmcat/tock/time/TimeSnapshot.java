package com.clmcat.tock.time;

import java.util.function.LongSupplier;

/**
 * 线程级时间快照。
 * <p>
 * 快照从创建时刻开始独立推进，不会再受后续时间同步偏移影响。它是线程执行期间的时间镜像，
 * 由业务线程绑定、使用并在结束时清理。
 * </p>
 */
public final class TimeSnapshot implements TimeSource {

    private final long seedTimeMs;
    private final long capturedNanoTime;
    private final LongSupplier nanoTimeSupplier;
    private final LongSupplier directTimeSupplier;

    TimeSnapshot(long seedTimeMs, long capturedNanoTime, LongSupplier nanoTimeSupplier) {
        this(seedTimeMs, capturedNanoTime, nanoTimeSupplier, null);
    }

    TimeSnapshot(long seedTimeMs, long capturedNanoTime, LongSupplier nanoTimeSupplier, LongSupplier directTimeSupplier) {
        this.seedTimeMs = seedTimeMs;
        this.capturedNanoTime = capturedNanoTime;
        this.nanoTimeSupplier = nanoTimeSupplier;
        this.directTimeSupplier = directTimeSupplier;
    }

    /**
     * 基于快照返回单调递增的当前时间。
     */
    public long currentTimeMillis() {
        if (directTimeSupplier != null) {
            return directTimeSupplier.getAsLong();
        }
        long elapsedNs = nanoTimeSupplier.getAsLong() - capturedNanoTime;
        return seedTimeMs + java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(elapsedNs);
    }

    static TimeSnapshot systemTime(long currentTimeMs, LongSupplier nanoTimeSupplier, LongSupplier directTimeSupplier) {
        return new TimeSnapshot(currentTimeMs, nanoTimeSupplier.getAsLong(), nanoTimeSupplier, directTimeSupplier);
    }
}
