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
    void shouldUseSuccessfulSamplesWhenSomeRequestsFail() {
        FlakyTimeProvider provider = new FlakyTimeProvider(2_000L, 2);
        DefaultTimeSynchronizer synchronizer = new DefaultTimeSynchronizer(provider, 100L, 4);

        try {
            synchronizer.start(null);
            long before = synchronizer.offset();
            long updated = synchronizer.syncNow();

            Assertions.assertTrue(Math.abs(before - 2_000L) <= 1L);
            Assertions.assertTrue(Math.abs(updated - 2_000L) <= 1L);
            Assertions.assertEquals(updated, synchronizer.offset());
        } finally {
            synchronizer.stop();
        }
    }

    @Test
    void shouldKeepPreviousOffsetWhenAllSamplesFail() {
        DefaultTimeSynchronizer synchronizer = new DefaultTimeSynchronizer(new OffsetDelayTimeProvider(1_500L, 10L), 100L, 3);
        AlwaysFailAfterWarmupProvider failingProvider = new AlwaysFailAfterWarmupProvider(synchronizer.offset());
        DefaultTimeSynchronizer failingSynchronizer = new DefaultTimeSynchronizer(failingProvider, 100L, 3);

        try {
            long before = failingSynchronizer.offset();
            long updated = failingSynchronizer.syncNow();

            Assertions.assertEquals(before, updated);
            Assertions.assertEquals(before, failingSynchronizer.offset());
        } finally {
            synchronizer.stop();
            failingSynchronizer.stop();
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

    private static final class AlwaysFailAfterWarmupProvider implements TimeProvider {
        private final long initialTime;
        private int calls;

        private AlwaysFailAfterWarmupProvider(long offsetMs) {
            this.initialTime = System.currentTimeMillis() + offsetMs;
        }

        @Override
        public long currentTimeMillis() {
            if (calls++ == 0) {
                return initialTime;
            }
            throw new IllegalStateException("simulated provider failure");
        }
    }
}
