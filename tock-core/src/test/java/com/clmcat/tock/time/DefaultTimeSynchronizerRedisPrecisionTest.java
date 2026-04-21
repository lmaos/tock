package com.clmcat.tock.time;

import com.clmcat.tock.RedisTestSupport;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class DefaultTimeSynchronizerRedisPrecisionTest extends RedisTestSupport {

    @Test
    void shouldStayCloseToRedisTimeAcrossMultipleSyncRounds() {
        TimeProvider provider = redisTimeProvider();
        DefaultTimeSynchronizer synchronizer = new DefaultTimeSynchronizer(provider, 100L, 5);

        long totalSyncError = 0L;
        long totalIntervalError = 0L;
        int rounds = 40;

        for (int i = 0; i < rounds; i++) {
            synchronizer.syncNow();

            long remoteBefore = midpointRemoteTime(provider);
            long observedBefore = synchronizer.currentTimeMillis();
            sleep(20L);
            long remoteAfter = midpointRemoteTime(provider);
            long observedAfter = synchronizer.currentTimeMillis();

            totalSyncError += Math.abs(observedAfter - remoteAfter);
            totalIntervalError += Math.abs((observedAfter - observedBefore) - (remoteAfter - remoteBefore));
        }

        System.out.println(String.format(
                "Redis synchronizer: avgSync=%.2fms avgInterval=%.2fms",
                totalSyncError / (double) rounds,
                totalIntervalError / (double) rounds
        ));

        Assertions.assertTrue(totalSyncError / (double) rounds <= 5.0D, "average Redis sync error should stay within 5ms");
        Assertions.assertTrue(totalIntervalError / (double) rounds <= 3.0D, "average Redis interval error should stay within 3ms");
    }

    @Test
    void shouldMeasureAlgorithmsAgainstRedisTime() {
        TimeProvider provider = redisTimeProvider();
        List<TimeSyncBenchmarkSupport.BenchmarkResult> results = new ArrayList<TimeSyncBenchmarkSupport.BenchmarkResult>();

        for (TimeSyncBenchmarkSupport.TimeSyncAlgorithm algorithm : TimeSyncBenchmarkSupport.createAlgorithms(systemClock())) {
            TimeSyncBenchmarkSupport.BenchmarkResult result = new TimeSyncBenchmarkSupport.BenchmarkResult(algorithm.name());
            for (int round = 0; round < 40; round++) {
                algorithm.sync(provider, 5);

                TimeSyncBenchmarkSupport.Observation first = TimeSyncBenchmarkSupport.observe(provider, algorithm);
                sleep(20L);
                TimeSyncBenchmarkSupport.Observation second = TimeSyncBenchmarkSupport.observe(provider, algorithm);

                result.addSyncError(Math.abs(first.observedMillis - first.remoteMidpointMillis));
                result.addSyncError(Math.abs(second.observedMillis - second.remoteMidpointMillis));

                long observedDelta = second.observedMillis - first.observedMillis;
                long remoteDelta = second.remoteMidpointMillis - first.remoteMidpointMillis;
                result.addIntervalError(Math.abs(observedDelta - remoteDelta), observedDelta - remoteDelta);
            }
            results.add(result);
            System.out.println("Redis benchmark: " + result.summary());
        }

        TimeSyncBenchmarkSupport.BenchmarkResult legacy = find(results, "legacy-average");
        TimeSyncBenchmarkSupport.BenchmarkResult monotonicAverage = find(results, "monotonic-average");
        TimeSyncBenchmarkSupport.BenchmarkResult monotonicMinRtt = find(results, "monotonic-min-rtt");

        Assertions.assertTrue(monotonicAverage.averageIntervalErrorMs() <= legacy.averageIntervalErrorMs() + 1.0D,
                "monotonic average should not regress Redis interval precision");
        Assertions.assertTrue(monotonicAverage.averageSyncErrorMs() <= legacy.averageSyncErrorMs() + 1.0D,
                "monotonic average should not regress Redis sync precision");
        Assertions.assertTrue(monotonicMinRtt.p95IntervalErrorMs() <= legacy.p95IntervalErrorMs() + 2L,
                "best RTT variant should keep Redis interval precision competitive in the steady-state tail");
        Assertions.assertTrue(monotonicMinRtt.p95SyncErrorMs() <= legacy.p95SyncErrorMs() + 2L,
                "best RTT variant should keep Redis sync precision competitive in the steady-state tail");
    }

    private TimeProvider redisTimeProvider() {
        return () -> {
            try (redis.clients.jedis.Jedis jedis = jedisPool.getResource()) {
                List<String> time = jedis.time();
                long seconds = Long.parseLong(time.get(0));
                long micros = Long.parseLong(time.get(1));
                return seconds * 1000L + micros / 1000L;
            }
        };
    }

    private long midpointRemoteTime(TimeProvider provider) {
        long before = provider.currentTimeMillis();
        long after = provider.currentTimeMillis();
        return before + ((after - before) / 2L);
    }

    private TimeSyncBenchmarkSupport.LocalClock systemClock() {
        return new TimeSyncBenchmarkSupport.LocalClock() {
            @Override
            public long currentTimeMillis() {
                return System.currentTimeMillis();
            }

            @Override
            public long nanoTime() {
                return System.nanoTime();
            }
        };
    }

    private TimeSyncBenchmarkSupport.BenchmarkResult find(List<TimeSyncBenchmarkSupport.BenchmarkResult> results, String name) {
        for (TimeSyncBenchmarkSupport.BenchmarkResult result : results) {
            if (result.name().equals(name)) {
                return result;
            }
        }
        throw new IllegalArgumentException("Missing result: " + name);
    }
}
