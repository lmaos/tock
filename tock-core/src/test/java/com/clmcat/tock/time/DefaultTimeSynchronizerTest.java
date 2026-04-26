package com.clmcat.tock.time;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

public class DefaultTimeSynchronizerTest {

    @Test
    void shouldUseDirectSystemProviderTime() {
        TimeSyncBenchmarkSupport.FakeClock clock = new TimeSyncBenchmarkSupport.FakeClock(1_700_000_000_000L, 0L, 0L);
        AtomicLong providerTime = new AtomicLong(42_000L);
        SystemTimeProvider provider = new SystemTimeProvider() {
            @Override
            public long currentTimeMillis() {
                return providerTime.get();
            }
        };
        DefaultTimeSynchronizer synchronizer = new DefaultTimeSynchronizer(
                provider,
                100L,
                1,
                clock::currentTimeMillis,
                clock::nanoTime
        );

        long first = synchronizer.currentTimeMillis();
        Assertions.assertEquals(42_000L, first);

        providerTime.set(43_500L);
        clock.advanceRealMillis(995L);
        long second = synchronizer.currentTimeMillis();

        Assertions.assertEquals(43_500L, second);
        Assertions.assertEquals(1_500L, second - first);
    }

    @Test
    void shouldSkipSnapshotForSystemProvider() {
        TimeSyncBenchmarkSupport.FakeClock clock = new TimeSyncBenchmarkSupport.FakeClock(1_700_000_000_000L, 0L, 0L);
        AtomicLong providerTime = new AtomicLong(7_000L);
        SystemTimeProvider provider = new SystemTimeProvider() {
            @Override
            public long currentTimeMillis() {
                return providerTime.get();
            }
        };
        DefaultTimeSynchronizer synchronizer = new DefaultTimeSynchronizer(
                provider,
                100L,
                1,
                clock::currentTimeMillis,
                clock::nanoTime
        );

        long first = synchronizer.currentTimeMillis();
        Assertions.assertNull(synchronizer.snapshot(100L));
        providerTime.set(7_250L);
        clock.advanceRealMillis(995L);
        long second = synchronizer.currentTimeMillis();

        Assertions.assertEquals(250L, second - first);
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

        synchronizer.syncNow();
        long startOffset = synchronizer.offset();
        long previousObserved = synchronizer.currentTimeMillis();

        for (int i = 0; i < 40; i++) {
            synchronizer.syncNow();
            clock.advanceRealMillis(1_000L);

            long observed = synchronizer.currentTimeMillis();
            Assertions.assertTrue(observed >= previousObserved, "current time must stay monotonic");
            previousObserved = observed;
        }

        Assertions.assertTrue(Math.abs(synchronizer.offset() - startOffset) <= 10L,
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
        DefaultTimeSynchronizer synchronizer = new DefaultTimeSynchronizer(new AlwaysFailingProvider(), 100L, 3);

        long before = synchronizer.offset();
        long updated = synchronizer.syncNow();

        Assertions.assertEquals(before, updated);
        Assertions.assertEquals(before, synchronizer.offset());
    }

    @Test
    void shouldPreferBoundSnapshotAndFallBackAfterExpiry() {
        TimeSyncBenchmarkSupport.FakeClock clock = new TimeSyncBenchmarkSupport.FakeClock(1_700_000_000_000L, 0L, 0L);
        AtomicLong offsetMs = new AtomicLong(1_000L);
        TimeProvider provider = () -> clock.remoteTimeMillis(offsetMs.get());

        DefaultTimeSynchronizer withSnapshot = new DefaultTimeSynchronizer(provider, 100L, 1, clock::currentTimeMillis, clock::nanoTime);
        DefaultTimeSynchronizer plain = new DefaultTimeSynchronizer(provider, 100L, 1, clock::currentTimeMillis, clock::nanoTime);

        withSnapshot.syncNow();
        plain.syncNow();

        TimeSnapshot snapshot = withSnapshot.snapshot(100L);
        withSnapshot.bindSnapshot(snapshot);

        long seeded = withSnapshot.currentTimeMillis();

        clock.advanceRealMillis(50L);
        offsetMs.set(5_000L);
        withSnapshot.forceReinitialize();
        plain.forceReinitialize();

        long snapshotValue = withSnapshot.currentTimeMillis();
        long plainValue = plain.currentTimeMillis();
        Assertions.assertEquals(50L, snapshotValue - seeded);
        Assertions.assertTrue(snapshotValue < plainValue, "snapshot should ignore later sync jumps while alive");

        clock.advanceRealMillis(150L);
        withSnapshot.forceReinitialize();
        plain.forceReinitialize();

        long afterExpirySnapshot = withSnapshot.currentTimeMillis();
        long afterExpiryPlain = plain.currentTimeMillis();
        Assertions.assertEquals(afterExpiryPlain, afterExpirySnapshot);
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

    private static final class AlwaysFailingProvider implements TimeProvider {
        @Override
        public long currentTimeMillis() {
            throw new IllegalStateException("simulated provider failure");
        }
    }
}
