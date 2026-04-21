package com.clmcat.tock.time;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class DefaultTimeSynchronizerPrecisionTest {

    @Test
    void shouldKeepIntervalsStableWhenWallClockIsCorrectedBackwards() {
        TimeSyncBenchmarkSupport.FakeClock clock = new TimeSyncBenchmarkSupport.FakeClock(1_700_000_000_000L, 40L, -1L);
        long remoteOffsetMs = 2_500L;
        DefaultTimeSynchronizer synchronizer = new DefaultTimeSynchronizer(
                new TimeSyncBenchmarkSupport.SimulatedRemoteTimeProvider(clock, remoteOffsetMs, 3L, 3L),
                100L,
                5,
                clock::currentTimeMillis,
                clock::nanoTime
        );

        long totalSyncError = 0L;
        long totalIntervalError = 0L;
        int samples = 120;

        for (int i = 0; i < samples; i++) {
            synchronizer.syncNow();
            long observedBefore = synchronizer.currentTimeMillis();
            long remoteBefore = clock.remoteTimeMillis(remoteOffsetMs);

            clock.advanceRealMillis(25L);

            long observedAfter = synchronizer.currentTimeMillis();
            long remoteAfter = clock.remoteTimeMillis(remoteOffsetMs);

            totalSyncError += Math.abs(observedAfter - remoteAfter);
            totalIntervalError += Math.abs((observedAfter - observedBefore) - (remoteAfter - remoteBefore));
        }

        Assertions.assertTrue(totalSyncError / (double) samples <= 1.0D, "average sync error should stay within 1ms under wall-clock corrections");
        Assertions.assertTrue(totalIntervalError / (double) samples <= 0.5D, "average interval error should stay within 0.5ms under wall-clock corrections");
    }

    @Test
    void shouldBeatLegacyAlgorithmInSyntheticAbBenchmark() {
        TimeSyncBenchmarkSupport.BenchmarkResult legacy = runSyntheticBenchmark("legacy-average");
        TimeSyncBenchmarkSupport.BenchmarkResult monotonicAverage = runSyntheticBenchmark("monotonic-average");
        TimeSyncBenchmarkSupport.BenchmarkResult monotonicMinRtt = runSyntheticBenchmark("monotonic-min-rtt");

        System.out.println("Synthetic benchmark: " + legacy.summary());
        System.out.println("Synthetic benchmark: " + monotonicAverage.summary());
        System.out.println("Synthetic benchmark: " + monotonicMinRtt.summary());

        Assertions.assertTrue(monotonicAverage.averageIntervalErrorMs() < legacy.averageIntervalErrorMs() / 8.0D,
                "monotonic average should materially reduce interval error versus legacy");
        Assertions.assertTrue(Math.abs(monotonicAverage.averageIntervalBiasMs()) < Math.abs(legacy.averageIntervalBiasMs()),
                "monotonic average should reduce shrinking-interval bias");
        Assertions.assertTrue(monotonicMinRtt.averageIntervalErrorMs() < legacy.averageIntervalErrorMs() / 8.0D,
                "all monotonic variants should outperform legacy under wall-clock corrections");
    }

    private TimeSyncBenchmarkSupport.BenchmarkResult runSyntheticBenchmark(String algorithmName) {
        TimeSyncBenchmarkSupport.FakeClock clock = new TimeSyncBenchmarkSupport.FakeClock(1_700_000_000_000L, 40L, -1L);
        TimeProvider provider = new TimeSyncBenchmarkSupport.SimulatedRemoteTimeProvider(clock, 2_500L, 3L, 4L);
        TimeSyncBenchmarkSupport.TimeSyncAlgorithm algorithm = createAlgorithm(algorithmName, clock);
        TimeSyncBenchmarkSupport.BenchmarkResult result = new TimeSyncBenchmarkSupport.BenchmarkResult(algorithm.name());

        for (int round = 0; round < 80; round++) {
            algorithm.sync(provider, 5);

            TimeSyncBenchmarkSupport.Observation first = TimeSyncBenchmarkSupport.observe(provider, algorithm);
            clock.advanceRealMillis(25L);
            TimeSyncBenchmarkSupport.Observation second = TimeSyncBenchmarkSupport.observe(provider, algorithm);

            result.addSyncError(Math.abs(first.observedMillis - first.remoteMidpointMillis));
            result.addSyncError(Math.abs(second.observedMillis - second.remoteMidpointMillis));

            long observedDelta = second.observedMillis - first.observedMillis;
            long remoteDelta = second.remoteMidpointMillis - first.remoteMidpointMillis;
            result.addIntervalError(Math.abs(observedDelta - remoteDelta), observedDelta - remoteDelta);
        }
        return result;
    }

    private TimeSyncBenchmarkSupport.TimeSyncAlgorithm createAlgorithm(String algorithmName, TimeSyncBenchmarkSupport.FakeClock clock) {
        List<TimeSyncBenchmarkSupport.TimeSyncAlgorithm> algorithms = new ArrayList<TimeSyncBenchmarkSupport.TimeSyncAlgorithm>(
                TimeSyncBenchmarkSupport.createAlgorithms(clock)
        );
        for (TimeSyncBenchmarkSupport.TimeSyncAlgorithm algorithm : algorithms) {
            if (algorithm.name().equals(algorithmName)) {
                return algorithm;
            }
        }
        throw new IllegalArgumentException("Unknown algorithm: " + algorithmName);
    }
}
