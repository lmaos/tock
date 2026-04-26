package com.clmcat.tock.time;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 线程级时间快照。
 * <p>
 * 快照从创建时刻开始独立推进，不会再受后续时间同步偏移影响；当超过有效期后，
 * 调用方应回退到时间同步器的默认逻辑。
 * </p>
 */
public final class TimeSnapshot implements TimeSource {

    private final long seedTimeMs;
    private final long capturedNanoTime;
    private final long expiresAtMs;
    private final LongSupplier wallClockMsSupplier;
    private final LongSupplier nanoTimeSupplier;

    TimeSnapshot(long seedTimeMs, long capturedNanoTime, long expiresAtMs,
                 LongSupplier wallClockMsSupplier, LongSupplier nanoTimeSupplier) {
        this.seedTimeMs = seedTimeMs;
        this.capturedNanoTime = capturedNanoTime;
        this.expiresAtMs = expiresAtMs;
        this.wallClockMsSupplier = wallClockMsSupplier;
        this.nanoTimeSupplier = nanoTimeSupplier;
    }

    /**
     * 基于快照返回单调递增的当前时间。
     */
    public long currentTimeMillis() {
        long elapsedNs = nanoTimeSupplier.getAsLong() - capturedNanoTime;
        return seedTimeMs + TimeUnit.NANOSECONDS.toMillis(elapsedNs);
    }

    /**
     * 快照是否已过期。
     */
    public boolean isExpired() {
        return wallClockMsSupplier.getAsLong() >= expiresAtMs;
    }

    public long getExpiresAtMs() {
        return expiresAtMs;
    }
}
