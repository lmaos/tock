package com.clmcat.tock.time;

import java.util.Objects;

/**
 * 可用于测试的时间生产者。
 * 先模拟请求延迟，再返回带固定偏移量的时间戳。
 */
public final class OffsetTimeProvider implements TimeProvider {

    private final TimeProvider delegate;
    private final long offsetMs;
    private final long delayMs;

    public OffsetTimeProvider(long offsetMs, long delayMs) {
        this(new SystemTimeProvider(), offsetMs, delayMs);
    }

    public OffsetTimeProvider(TimeProvider delegate, long offsetMs, long delayMs) {
        this.delegate = Objects.requireNonNull(delegate, "delegate is null");
        this.offsetMs = offsetMs;
        this.delayMs = Math.max(0L, delayMs);
    }

    @Override
    public long currentTimeMillis() {
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while simulating time provider delay", e);
            }
        }
        return delegate.currentTimeMillis() + offsetMs;
    }
}
