package com.clmcat.tock.time;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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

    @Test
    void shouldUseMonotonicClockForSystemTimeProvider() {
        AtomicLong wallClockMs = new AtomicLong(10_000L);
        AtomicLong nanoTime = new AtomicLong(1_000_000_000L);
        DefaultTimeSynchronizer synchronizer = new DefaultTimeSynchronizer(
                new SystemTimeProvider(),
                100L,
                1,
                wallClockMs::get,
                nanoTime::get
        );

        long first = synchronizer.currentTimeMillis();
        nanoTime.addAndGet(TimeUnit.MILLISECONDS.toNanos(995L));
        wallClockMs.addAndGet(1_900L);
        long second = synchronizer.currentTimeMillis();

        Assertions.assertEquals(995L, second - first,
                "system time provider should advance according to monotonic nano time even if wall clock jumps");
    }

    @Test
    void shouldClampLargeOffsetJumpAfterInitialization() {
        AtomicLong wallClockMs = new AtomicLong(10_000L);
        AtomicLong nanoTime = new AtomicLong(1_000_000_000L);
        ScriptedTimeProvider provider = new ScriptedTimeProvider(wallClockMs, nanoTime);
        provider.nextStep(new ProviderStep(0L, 0L));

        DefaultTimeSynchronizer synchronizer = new DefaultTimeSynchronizer(
                provider,
                100L,
                1,
                wallClockMs::get,
                nanoTime::get
        );

        wallClockMs.addAndGet(1_000L);
        nanoTime.addAndGet(TimeUnit.MILLISECONDS.toNanos(1_000L));
        provider.nextStep(new ProviderStep(1_600L, 0L));

        long updated = synchronizer.syncNow();

        Assertions.assertEquals(5L, updated,
                "large sampled offset jumps should be slewed instead of being applied in a single step");
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

    private static final class ScriptedTimeProvider implements TimeProvider {
        private final AtomicLong wallClockMs;
        private final AtomicLong nanoTime;
        private final AtomicReference<ProviderStep> nextStep = new AtomicReference<>(new ProviderStep(0L, 0L));

        private ScriptedTimeProvider(AtomicLong wallClockMs, AtomicLong nanoTime) {
            this.wallClockMs = wallClockMs;
            this.nanoTime = nanoTime;
        }

        private void nextStep(ProviderStep step) {
            nextStep.set(step);
        }

        @Override
        public long currentTimeMillis() {
            ProviderStep step = nextStep.getAndSet(new ProviderStep(0L, 0L));
            wallClockMs.addAndGet(step.delayMs);
            nanoTime.addAndGet(TimeUnit.MILLISECONDS.toNanos(step.delayMs));
            return wallClockMs.get() + step.remoteOffsetMs;
        }
    }

    private static final class ProviderStep {
        private final long delayMs;
        private final long remoteOffsetMs;

        private ProviderStep(long delayMs, long remoteOffsetMs) {
            this.delayMs = delayMs;
            this.remoteOffsetMs = remoteOffsetMs;
        }
    }
}
