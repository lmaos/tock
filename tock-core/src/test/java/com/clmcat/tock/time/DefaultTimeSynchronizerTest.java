package com.clmcat.tock.time;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class DefaultTimeSynchronizerTest {

    @Test
    void shouldKeepStableTimeWithSimulatedDelay() {
        DefaultTimeSynchronizer synchronizer = new DefaultTimeSynchronizer(new OffsetDelayTimeProvider(5_000L, 80L), 100L, 5);
        try {
            synchronizer.start(null);
            long observed1 = synchronizer.currentTimeMillis();
            sleep(60L);
            long observed2 = synchronizer.currentTimeMillis();

            Assertions.assertTrue(observed2 >= observed1, "synced time should be monotonic");
            Assertions.assertTrue(Math.abs((observed2 - observed1) - 60L) < 120L, "clock should advance steadily");
        } finally {
            synchronizer.stop();
        }
    }

    @Test
    void shouldIgnoreFailedSamplesAndKeepPreviousOffset() {
        FlakyTimeProvider provider = new FlakyTimeProvider(2_000L, 2);
        DefaultTimeSynchronizer synchronizer = new DefaultTimeSynchronizer(provider, 100L, 4);

        try {
            synchronizer.start(null);
            long before = synchronizer.offset();
            long updated = synchronizer.syncNow();

            Assertions.assertEquals(before, updated);
            Assertions.assertEquals(before, synchronizer.offset());
        } finally {
            synchronizer.stop();
        }
    }

    @Test
    void shouldExposeStableOffsetAfterDelay() {
        DefaultTimeSynchronizer synchronizer = new DefaultTimeSynchronizer(new OffsetDelayTimeProvider(1_234L, 30L), 100L, 3);
        try {
            synchronizer.start(null);
            Assertions.assertTrue(Math.abs(synchronizer.offset() - 1_234L) < 150L);
        } finally {
            synchronizer.stop();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static final class OffsetDelayTimeProvider implements TimeProvider {
        private final long offsetMs;
        private final long delayMs;

        private OffsetDelayTimeProvider(long offsetMs, long delayMs) {
            this.offsetMs = offsetMs;
            this.delayMs = delayMs;
        }

        @Override
        public long currentTimeMillis() {
            sleep(delayMs);
            return System.currentTimeMillis() + offsetMs;
        }
    }

    private static final class FlakyTimeProvider implements TimeProvider {
        private final long offsetMs;
        private final int failCount;
        private int calls;

        private FlakyTimeProvider(long offsetMs, int failCount) {
            this.offsetMs = offsetMs;
            this.failCount = failCount;
        }

        @Override
        public long currentTimeMillis() {
            if (calls++ < failCount) {
                throw new IllegalStateException("simulated provider failure");
            }
            return System.currentTimeMillis() + offsetMs;
        }
    }
}
