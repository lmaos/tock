package com.clmcat.tock.time;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class DefaultTimeSynchronizerTest {

    @Test
    void shouldKeepSystemProviderMonotonic() {
        TimeSyncBenchmarkSupport.FakeClock clock = new TimeSyncBenchmarkSupport.FakeClock(1_700_000_000_000L, 0L, 0L);
        DefaultTimeSynchronizer synchronizer = new DefaultTimeSynchronizer(
                new SystemTimeProvider(),
                100L,
                1,
                clock::currentTimeMillis,
                clock::nanoTime
        );

        long first = synchronizer.currentTimeMillis();
        clock.advanceRealMillis(995L);
        long second = synchronizer.currentTimeMillis();

        Assertions.assertEquals(995L, second - first);
    }

    @Test
    void shouldNotMarkSystemProviderAsRunning() {
        TimeSyncBenchmarkSupport.FakeClock clock = new TimeSyncBenchmarkSupport.FakeClock(1_700_000_000_000L, 0L, 0L);
        DefaultTimeSynchronizer synchronizer = new DefaultTimeSynchronizer(
                new SystemTimeProvider(),
                100L,
                1,
                clock::currentTimeMillis,
                clock::nanoTime
        );

        synchronizer.start(null);

        Assertions.assertFalse(synchronizer.isRunning());
    }

    @Test
    void shouldIgnoreOscillatingRedisJitter() {
        TimeSyncBenchmarkSupport.FakeClock clock = new TimeSyncBenchmarkSupport.FakeClock(1_700_000_000_000L, 0L, 0L);
        DefaultTimeSynchronizer synchronizer = new DefaultTimeSynchronizer(
                new OscillatingRedisTimeProvider(clock, 5_000L, -5L, 5L, -5L, 5L),
                1_000L,
                5,
                clock::currentTimeMillis,
                clock::nanoTime
        );

        long startOffset = synchronizer.offset();
        long previousObserved = synchronizer.currentTimeMillis();
        long totalIntervalError = 0L;
        long maxIntervalError = 0L;

        for (int i = 0; i < 40; i++) {
            synchronizer.syncNow();
            clock.advanceRealMillis(1_000L);

            long observed = synchronizer.currentTimeMillis();
            long intervalError = Math.abs((observed - previousObserved) - 1_000L);
            totalIntervalError += intervalError;
            if (intervalError > maxIntervalError) {
                maxIntervalError = intervalError;
            }
            previousObserved = observed;
        }

        Assertions.assertTrue(maxIntervalError <= 1L, "periodic ticks should stay smooth even if Redis jitters");
        Assertions.assertTrue(totalIntervalError / 40.0D <= 0.5D, "average interval error should stay below 0.5ms");
        Assertions.assertTrue(Math.abs(synchronizer.offset() - startOffset) <= 1L,
                "oscillating Redis samples should not staircase the offset");
    }

    @Test
    void shouldKeepMultipleSynchronizersAlignedAcrossStaggeredStarts() {
        TimeSyncBenchmarkSupport.FakeClock clock = new TimeSyncBenchmarkSupport.FakeClock(1_700_000_000_000L, 0L, 0L);
        OscillatingRedisTimeProvider provider = new OscillatingRedisTimeProvider(clock, 4_000L, -5L, 5L, -5L, 5L);

        DefaultTimeSynchronizer first = new DefaultTimeSynchronizer(provider, 1_000L, 5, clock::currentTimeMillis, clock::nanoTime);
        clock.advanceRealMillis(317L);
        DefaultTimeSynchronizer second = new DefaultTimeSynchronizer(provider, 1_000L, 5, clock::currentTimeMillis, clock::nanoTime);
        clock.advanceRealMillis(211L);
        DefaultTimeSynchronizer third = new DefaultTimeSynchronizer(provider, 1_000L, 5, clock::currentTimeMillis, clock::nanoTime);

        for (int i = 0; i < 30; i++) {
            first.syncNow();
            second.syncNow();
            third.syncNow();

            long a = first.currentTimeMillis();
            long b = second.currentTimeMillis();
            long c = third.currentTimeMillis();
            long skew = Math.max(Math.max(a, b), c) - Math.min(Math.min(a, b), c);

            Assertions.assertTrue(skew <= 2L, "staggered synchronizers should stay aligned");
            clock.advanceRealMillis(1_000L);
        }
    }

    @Test
    void shouldTrackStableRedisOffsetAccurately() {
        TimeSyncBenchmarkSupport.FakeClock clock = new TimeSyncBenchmarkSupport.FakeClock(1_700_000_000_000L, 0L, 0L);
        DefaultTimeSynchronizer synchronizer = new DefaultTimeSynchronizer(
                new OscillatingRedisTimeProvider(clock, 1_234L, 0L),
                1_000L,
                3,
                clock::currentTimeMillis,
                clock::nanoTime
        );

        for (int i = 0; i < 10; i++) {
            synchronizer.syncNow();
            clock.advanceRealMillis(1_000L);
        }

        Assertions.assertTrue(Math.abs(synchronizer.offset() - 1_234L) <= 1L,
                "stable Redis time should converge to the correct offset");
    }

    @Test
    void shouldKeepPreviousOffsetWhenAllSamplesFail() {
        DefaultTimeSynchronizer synchronizer = new DefaultTimeSynchronizer(new OneShotFailingProvider(1_500L), 100L, 3);

        long before = synchronizer.offset();
        long updated = synchronizer.syncNow();

        Assertions.assertEquals(before, updated);
        Assertions.assertEquals(before, synchronizer.offset());
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

    private static final class OneShotFailingProvider implements TimeProvider {
        private final long offsetMs;
        private int calls;

        private OneShotFailingProvider(long offsetMs) {
            this.offsetMs = offsetMs;
        }

        @Override
        public long currentTimeMillis() {
            if (calls++ == 0) {
                return System.currentTimeMillis() + offsetMs;
            }
            throw new IllegalStateException("simulated provider failure");
        }
    }
}
