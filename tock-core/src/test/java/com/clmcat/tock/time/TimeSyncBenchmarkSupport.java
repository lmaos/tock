package com.clmcat.tock.time;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class TimeSyncBenchmarkSupport {

    private TimeSyncBenchmarkSupport() {
    }

    static List<TimeSyncAlgorithm> createAlgorithms(LocalClock clock) {
        return Arrays.asList(
                new LegacyAverageAlgorithm(clock),
                new MonotonicAverageAlgorithm(clock),
                new MonotonicMinRttAlgorithm(clock)
        );
    }

    static Observation observe(TimeProvider provider, TimeSyncAlgorithm algorithm) {
        long remoteBefore = provider.currentTimeMillis();
        long observed = algorithm.currentTimeMillis();
        long remoteAfter = provider.currentTimeMillis();
        long remoteMidpoint = remoteBefore + ((remoteAfter - remoteBefore) / 2L);
        return new Observation(observed, remoteMidpoint);
    }

    interface LocalClock {
        long currentTimeMillis();

        long nanoTime();
    }

    interface TimeSyncAlgorithm {
        String name();

        void sync(TimeProvider provider, int sampleCount);

        long currentTimeMillis();
    }

    static final class Observation {
        final long observedMillis;
        final long remoteMidpointMillis;

        Observation(long observedMillis, long remoteMidpointMillis) {
            this.observedMillis = observedMillis;
            this.remoteMidpointMillis = remoteMidpointMillis;
        }
    }

    static final class BenchmarkResult {
        private final String name;
        private final List<Long> syncErrors = new ArrayList<Long>();
        private final List<Long> intervalErrors = new ArrayList<Long>();
        private long intervalBiasTotal;

        BenchmarkResult(String name) {
            this.name = name;
        }

        void addSyncError(long errorMs) {
            syncErrors.add(errorMs);
        }

        void addIntervalError(long errorMs, long biasMs) {
            intervalErrors.add(errorMs);
            intervalBiasTotal += biasMs;
        }

        String name() {
            return name;
        }

        double averageSyncErrorMs() {
            return average(syncErrors);
        }

        double averageIntervalErrorMs() {
            return average(intervalErrors);
        }

        double averageIntervalBiasMs() {
            return intervalErrors.isEmpty() ? 0.0D : intervalBiasTotal / (double) intervalErrors.size();
        }

        long p95SyncErrorMs() {
            return percentile(syncErrors, 95);
        }

        long p95IntervalErrorMs() {
            return percentile(intervalErrors, 95);
        }

        String summary() {
            return String.format(
                    "%s avgSync=%.2fms p95Sync=%dms avgInterval=%.2fms p95Interval=%dms avgBias=%.2fms",
                    name,
                    averageSyncErrorMs(),
                    p95SyncErrorMs(),
                    averageIntervalErrorMs(),
                    p95IntervalErrorMs(),
                    averageIntervalBiasMs()
            );
        }

        private static double average(List<Long> values) {
            if (values.isEmpty()) {
                return 0.0D;
            }
            long total = 0L;
            for (Long value : values) {
                total += value;
            }
            return total / (double) values.size();
        }

        private static long percentile(List<Long> values, int percentile) {
            if (values.isEmpty()) {
                return 0L;
            }
            List<Long> sorted = new ArrayList<Long>(values);
            sorted.sort(Long::compareTo);
            int index = (int) Math.ceil((percentile / 100.0D) * sorted.size()) - 1;
            index = Math.max(0, Math.min(index, sorted.size() - 1));
            return sorted.get(index);
        }
    }

    static final class FakeClock implements LocalClock {
        private final long correctionEveryRealMs;
        private final long wallClockCorrectionMs;
        private final long initialEpochMs;
        private long trueTimeMs;
        private long wallClockMs;
        private long nanoTimeNs;
        private long nextCorrectionAtMs;

        FakeClock(long initialEpochMs, long correctionEveryRealMs, long wallClockCorrectionMs) {
            this.initialEpochMs = initialEpochMs;
            this.correctionEveryRealMs = correctionEveryRealMs;
            this.wallClockCorrectionMs = wallClockCorrectionMs;
            this.trueTimeMs = initialEpochMs;
            this.wallClockMs = initialEpochMs;
            this.nanoTimeNs = 0L;
            this.nextCorrectionAtMs = correctionEveryRealMs <= 0 ? Long.MAX_VALUE : initialEpochMs + correctionEveryRealMs;
        }

        @Override
        public long currentTimeMillis() {
            return wallClockMs;
        }

        @Override
        public long nanoTime() {
            return nanoTimeNs;
        }

        long remoteTimeMillis(long remoteOffsetMs) {
            return trueTimeMs + remoteOffsetMs;
        }

        void advanceRealMillis(long elapsedMs) {
            long remaining = elapsedMs;
            while (remaining > 0L) {
                if (trueTimeMs >= nextCorrectionAtMs) {
                    wallClockMs += wallClockCorrectionMs;
                    nextCorrectionAtMs += correctionEveryRealMs;
                    continue;
                }
                long untilCorrection = nextCorrectionAtMs - trueTimeMs;
                long step = Math.min(remaining, untilCorrection);
                if (step > 0L) {
                    trueTimeMs += step;
                    wallClockMs += step;
                    nanoTimeNs += TimeUnit.MILLISECONDS.toNanos(step);
                    remaining -= step;
                }
            }
        }

        long trueTimeMillis() {
            return trueTimeMs;
        }

        FakeClock copy() {
            return new FakeClock(initialEpochMs, correctionEveryRealMs, wallClockCorrectionMs);
        }
    }

    static final class SimulatedRemoteTimeProvider implements TimeProvider {
        private final FakeClock clock;
        private final long remoteOffsetMs;
        private final long outboundLatencyMs;
        private final long inboundLatencyMs;

        SimulatedRemoteTimeProvider(FakeClock clock, long remoteOffsetMs, long outboundLatencyMs, long inboundLatencyMs) {
            this.clock = clock;
            this.remoteOffsetMs = remoteOffsetMs;
            this.outboundLatencyMs = outboundLatencyMs;
            this.inboundLatencyMs = inboundLatencyMs;
        }

        @Override
        public long currentTimeMillis() {
            clock.advanceRealMillis(outboundLatencyMs);
            long remoteTimeMs = clock.remoteTimeMillis(remoteOffsetMs);
            clock.advanceRealMillis(inboundLatencyMs);
            return remoteTimeMs;
        }
    }

    private abstract static class AbstractAlgorithm implements TimeSyncAlgorithm {
        private final String name;
        private final LocalClock clock;
        private long offsetMs;

        private AbstractAlgorithm(String name, LocalClock clock) {
            this.name = name;
            this.clock = clock;
        }

        @Override
        public final String name() {
            return name;
        }

        @Override
        public final void sync(TimeProvider provider, int sampleCount) {
            offsetMs = estimateOffset(provider, sampleCount);
        }

        @Override
        public final long currentTimeMillis() {
            return localTimeMillis() + offsetMs;
        }

        protected final LocalClock clock() {
            return clock;
        }

        protected abstract long localTimeMillis();

        protected abstract long estimateOffset(TimeProvider provider, int sampleCount);
    }

    private static final class LegacyAverageAlgorithm extends AbstractAlgorithm {
        private LegacyAverageAlgorithm(LocalClock clock) {
            super("legacy-average", clock);
        }

        @Override
        protected long localTimeMillis() {
            return clock().currentTimeMillis();
        }

        @Override
        protected long estimateOffset(TimeProvider provider, int sampleCount) {
            long totalOffset = 0L;
            for (int i = 0; i < sampleCount; i++) {
                long localStartMs = clock().currentTimeMillis();
                long localStartNs = clock().nanoTime();
                long remoteTimeMs = provider.currentTimeMillis();
                long localElapsedNs = clock().nanoTime() - localStartNs;
                long midpointLocalMs = localStartMs + TimeUnit.NANOSECONDS.toMillis(localElapsedNs / 2L);
                totalOffset += remoteTimeMs - midpointLocalMs;
            }
            return Math.round(totalOffset / (double) sampleCount);
        }
    }

    private abstract static class AbstractMonotonicAlgorithm extends AbstractAlgorithm {
        private final long anchorWallClockMs;
        private final long anchorNanoTime;

        private AbstractMonotonicAlgorithm(String name, LocalClock clock) {
            super(name, clock);
            long startNs = clock.nanoTime();
            long wallClockMs = clock.currentTimeMillis();
            long endNs = clock.nanoTime();
            this.anchorWallClockMs = wallClockMs;
            this.anchorNanoTime = startNs + ((endNs - startNs) / 2L);
        }

        @Override
        protected final long localTimeMillis() {
            return localTimeMillisAt(clock().nanoTime());
        }

        protected final long localTimeMillisAt(long nanoTime) {
            return anchorWallClockMs + TimeUnit.NANOSECONDS.toMillis(nanoTime - anchorNanoTime);
        }
    }

    private static final class MonotonicAverageAlgorithm extends AbstractMonotonicAlgorithm {
        private MonotonicAverageAlgorithm(LocalClock clock) {
            super("monotonic-average", clock);
        }

        @Override
        protected long estimateOffset(TimeProvider provider, int sampleCount) {
            long totalOffset = 0L;
            for (int i = 0; i < sampleCount; i++) {
                long localStartNs = clock().nanoTime();
                long remoteTimeMs = provider.currentTimeMillis();
                long localEndNs = clock().nanoTime();
                long midpointLocalNs = localStartNs + ((localEndNs - localStartNs) / 2L);
                totalOffset += remoteTimeMs - localTimeMillisAt(midpointLocalNs);
            }
            return Math.round(totalOffset / (double) sampleCount);
        }
    }

    private static final class MonotonicMinRttAlgorithm extends AbstractMonotonicAlgorithm {
        private MonotonicMinRttAlgorithm(LocalClock clock) {
            super("monotonic-min-rtt", clock);
        }

        @Override
        protected long estimateOffset(TimeProvider provider, int sampleCount) {
            long bestOffset = 0L;
            long bestRttNs = Long.MAX_VALUE;
            for (int i = 0; i < sampleCount; i++) {
                long localStartNs = clock().nanoTime();
                long remoteTimeMs = provider.currentTimeMillis();
                long localEndNs = clock().nanoTime();
                long rttNs = localEndNs - localStartNs;
                long midpointLocalNs = localStartNs + (rttNs / 2L);
                long offset = remoteTimeMs - localTimeMillisAt(midpointLocalNs);
                if (rttNs < bestRttNs) {
                    bestRttNs = rttNs;
                    bestOffset = offset;
                }
            }
            return bestOffset;
        }
    }
}
