package com.clmcat.tock.time;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class DefaultTimeSynchronizerBenchmarkTest {

    @Test
    void shouldKeepBoundaryTicksAndAbsoluteErrorTightUnderRedisJitter() {
        TimeSyncBenchmarkSupport.FakeClock clock = new TimeSyncBenchmarkSupport.FakeClock(1_700_000_000_000L, 0L, 0L);
        long baseOffsetMs = 5_000L;
        DefaultTimeSynchronizer synchronizer = new DefaultTimeSynchronizer(
                new OscillatingRedisTimeProvider(clock, baseOffsetMs, -5L, 5L, -5L, 5L, 0L, 0L),
                1_000L,
                5,
                clock::currentTimeMillis,
                clock::nanoTime
        );

        TimeSyncBenchmarkSupport.BenchmarkResult result = new TimeSyncBenchmarkSupport.BenchmarkResult("default-time-synchronizer");
        long previousObserved = synchronizer.currentTimeMillis();

        for (int i = 0; i < 60; i++) {
            synchronizer.syncNow();
            clock.advanceRealMillis(1_000L);

            long observed = synchronizer.currentTimeMillis();
            long expected = clock.trueTimeMillis() + baseOffsetMs;
            result.addSyncError(Math.abs(observed - expected));
            result.addIntervalError(Math.abs((observed - previousObserved) - 1_000L),
                    observed - previousObserved - 1_000L);
            previousObserved = observed;
        }

        System.out.println("Redis jitter benchmark: " + result.summary());

        Assertions.assertTrue(result.averageSyncErrorMs() <= 1.0D, "average absolute error should stay within 1ms");
        Assertions.assertTrue(result.p95SyncErrorMs() <= 2L, "tail sync error should stay within 2ms");
        Assertions.assertTrue(result.averageIntervalErrorMs() <= 1.0D, "average interval error should stay within 1ms");
        Assertions.assertTrue(result.p95IntervalErrorMs() <= 1L, "periodic interval p95 should stay within 1ms");
    }

    private static final class OscillatingRedisTimeProvider implements TimeProvider {
        private final TimeSyncBenchmarkSupport.FakeClock clock;
        private final long baseOffsetMs;
        private final long[] jitterPatternMs;
        private int calls;

        private OscillatingRedisTimeProvider(TimeSyncBenchmarkSupport.FakeClock clock, long baseOffsetMs, long... jitterPatternMs) {
            this.clock = clock;
            this.baseOffsetMs = baseOffsetMs;
            this.jitterPatternMs = jitterPatternMs;
        }

        @Override
        public long currentTimeMillis() {
            long jitterMs = jitterPatternMs[calls++ % jitterPatternMs.length];
            return clock.remoteTimeMillis(baseOffsetMs + jitterMs);
        }
    }
}
