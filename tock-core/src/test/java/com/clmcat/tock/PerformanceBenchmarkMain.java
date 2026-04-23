package com.clmcat.tock;

import com.clmcat.tock.job.JobContext;
import com.clmcat.tock.money.MemoryManager;
import com.clmcat.tock.registry.TockRegister;
import com.clmcat.tock.registry.memory.MemoryTockRegister;
import com.clmcat.tock.registry.redis.RedisTockRegister;
import com.clmcat.tock.schedule.ScheduleConfig;
import com.clmcat.tock.schedule.ScheduleExecutionGuard;
import com.clmcat.tock.schedule.ScheduleStore;
import com.clmcat.tock.schedule.memory.MemoryScheduleStore;
import com.clmcat.tock.schedule.redis.RedisScheduleStore;
import com.clmcat.tock.scheduler.TockScheduler;
import com.clmcat.tock.store.JobExecution;
import com.clmcat.tock.worker.WorkerQueue;
import com.clmcat.tock.worker.memory.MemorySubscribableWorkerQueue;
import com.clmcat.tock.worker.redis.RedisSubscribableWorkerQueue;
import com.clmcat.tock.worker.scheduler.HighPrecisionWheelTaskScheduler;
import com.clmcat.tock.worker.scheduler.ScheduledExecutorTaskScheduler;
import com.clmcat.tock.worker.scheduler.TaskScheduler;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class PerformanceBenchmarkMain {

    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter SAMPLE_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(REPORT_ZONE);
    private static final DecimalFormat DECIMAL = new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(Locale.US));

    private PerformanceBenchmarkMain() {
    }

    public static void main(String[] args) throws Exception {
        BenchmarkConfig config = BenchmarkConfig.defaults();
        Path outputDir = Paths.get("target", "performance");
        Files.createDirectories(outputDir);

        EnvironmentInfo environment = EnvironmentInfo.capture();
        List<ScenarioDefinition> scenarios = Arrays.asList(
                ScenarioDefinition.memoryDefault(),
                ScenarioDefinition.memoryHighPrecision(),
                ScenarioDefinition.redisDefault(),
                ScenarioDefinition.redisHighPrecision()
        );
        scenarios = filterScenarios(scenarios, System.getProperty("benchmark.scenarios"));

        List<PrecisionScenarioResult> precisionResults = new ArrayList<PrecisionScenarioResult>();
        if (!config.skipPrecision) {
            for (ScenarioDefinition scenario : scenarios) {
                System.out.println("Running precision benchmark for " + scenario.label + " ...");
                precisionResults.add(runPrecisionBenchmark(scenario, config.precisionConfig));
            }
        }

        List<ThroughputScenarioResult> throughputResults = new ArrayList<ThroughputScenarioResult>();
        if (!config.skipThroughput) {
            for (ScenarioDefinition scenario : scenarios) {
                System.out.println("Running throughput benchmark for " + scenario.label + " ...");
                throughputResults.add(runThroughputBenchmark(scenario, config.throughputConfig));
            }
        }

        BenchmarkReport report = new BenchmarkReport(environment, config, precisionResults, throughputResults);
        Files.write(outputDir.resolve("precision-rounds.csv"), report.toPrecisionCsv().getBytes(StandardCharsets.UTF_8));
        Files.write(outputDir.resolve("precision-samples.csv"), report.toPrecisionSamplesCsv().getBytes(StandardCharsets.UTF_8));
        Files.write(outputDir.resolve("throughput-rounds.csv"), report.toThroughputCsv().getBytes(StandardCharsets.UTF_8));
        Files.write(outputDir.resolve("benchmark-summary.md"), report.toMarkdown().getBytes(StandardCharsets.UTF_8));
        Files.write(outputDir.resolve("benchmark-summary.txt"), report.toConsoleSummary().getBytes(StandardCharsets.UTF_8));

        System.out.println(report.toConsoleSummary());
    }

    private static List<ScenarioDefinition> filterScenarios(List<ScenarioDefinition> scenarios, String scenarioFilter) {
        if (scenarioFilter == null || scenarioFilter.trim().isEmpty()) {
            return scenarios;
        }
        List<String> requested = Arrays.asList(scenarioFilter.split(","));
        List<ScenarioDefinition> filtered = new ArrayList<ScenarioDefinition>();
        for (ScenarioDefinition scenario : scenarios) {
            if (requested.contains(scenario.key)) {
                filtered.add(scenario);
            }
        }
        if (filtered.isEmpty()) {
            throw new IllegalArgumentException("No benchmark scenarios matched benchmark.scenarios=" + scenarioFilter);
        }
        return filtered;
    }

    private static PrecisionScenarioResult runPrecisionBenchmark(ScenarioDefinition scenario, PrecisionConfig config) throws Exception {
        List<PrecisionRoundResult> rounds = new ArrayList<PrecisionRoundResult>();
        List<FireSample> representativeSamples = Collections.emptyList();
        for (int round = 1; round <= config.rounds; round++) {
            ScenarioRuntime runtime = null;
            try {
                runtime = scenario.start("precision", round, false);
                final Tock activeTock = runtime.tock;
                final CountDownLatch latch = new CountDownLatch(config.measuredSamples);
                final SampleCollector collector = new SampleCollector(config.warmupSamples, config.measuredSamples, latch);
                final String workerGroup = "perf-precision-" + scenario.key;
                final String jobId = "job-" + scenario.key + "-" + round;
                final String scheduleId = "schedule-" + scenario.key + "-" + round;

                activeTock.registerJob(jobId, ctx -> collector.record(ctx, activeTock.currentTimeMillis()));
                activeTock.start();
                activeTock.joinGroup(workerGroup);
                activeTock.addSchedule(ScheduleConfig.builder()
                        .scheduleId(scheduleId)
                        .jobId(jobId)
                        .workerGroup(workerGroup)
                        .cron("*/1 * * * * ?")
                        .zoneId(REPORT_ZONE.getId())
                        .build());
                activeTock.refreshSchedules();

                long timeoutMs = (config.warmupSamples + config.measuredSamples + 8L) * 1000L;
                if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("Precision benchmark timed out for " + scenario.label + " round " + round);
                }

                List<FireSample> samples = collector.snapshot();
                if (round == 1) {
                    representativeSamples = samples;
                }
                rounds.add(PrecisionRoundResult.fromSamples(round, samples));
            } finally {
                if (runtime != null) {
                    runtime.close();
                }
            }
        }
        return PrecisionScenarioResult.aggregate(scenario, rounds, representativeSamples);
    }

    private static ThroughputScenarioResult runThroughputBenchmark(ScenarioDefinition scenario, ThroughputConfig config) throws Exception {
        List<ThroughputRoundResult> rounds = new ArrayList<ThroughputRoundResult>();
        for (int round = 1; round <= config.rounds; round++) {
            runThroughputRound(scenario, config.warmupTasks, round, true);
            rounds.add(runThroughputRound(scenario, config.measuredTasks, round, false));
        }
        return ThroughputScenarioResult.aggregate(scenario, rounds);
    }

    private static ThroughputRoundResult runThroughputRound(ScenarioDefinition scenario, int taskCount, int round, boolean warmupOnly) throws Exception {
        ScenarioRuntime runtime = null;
        try {
            runtime = scenario.start(warmupOnly ? "throughput-warmup" : "throughput", round, true);
            final CountDownLatch latch = new CountDownLatch(taskCount);
            final AtomicInteger completed = new AtomicInteger();
            final String workerGroup = "perf-throughput-" + scenario.key;
            final String jobId = "throughput-job-" + scenario.key + "-" + round;

            runtime.tock.registerJob(jobId, ctx -> {
                completed.incrementAndGet();
                latch.countDown();
            });
            runtime.tock.start();
            runtime.tock.joinGroup(workerGroup);
            Thread.sleep(200L);

            List<JobExecution> jobs = prepareThroughputJobs(runtime, workerGroup, jobId, taskCount, round, warmupOnly);
            ResourceSampler sampler = new ResourceSampler();
            long startNs = System.nanoTime();
            sampler.start();
            for (JobExecution job : jobs) {
                runtime.workerQueue.push(job, workerGroup);
            }
            long pushDoneNs = System.nanoTime();
            if (!latch.await(Math.max(30L, taskCount / 50L), TimeUnit.SECONDS)) {
                throw new IllegalStateException("Throughput benchmark timed out for " + scenario.label + " round " + round);
            }
            sampler.stop();
            long endNs = System.nanoTime();

            double totalSeconds = (endNs - startNs) / 1_000_000_000.0D;
            double pushSeconds = (pushDoneNs - startNs) / 1_000_000_000.0D;
            if (warmupOnly) {
                return ThroughputRoundResult.warmup(round, taskCount, totalSeconds, pushSeconds);
            }
            return ThroughputRoundResult.measured(
                    round,
                    taskCount,
                    totalSeconds,
                    pushSeconds,
                    taskCount / totalSeconds,
                    sampler.processCpuLoadPercent(),
                    sampler.peakHeapUsedBytes(),
                    completed.get()
            );
        } finally {
            if (runtime != null) {
                runtime.close();
            }
        }
    }

    private static List<JobExecution> prepareThroughputJobs(ScenarioRuntime runtime, String workerGroup, String jobId,
                                                            int taskCount, int round, boolean warmupOnly) {
        List<JobExecution> jobs = new ArrayList<JobExecution>(taskCount);
        for (int i = 0; i < taskCount; i++) {
            String scheduleId = (warmupOnly ? "warmup" : "throughput") + "-" + runtime.scenario.key + "-" + round + "-" + i;
            ScheduleConfig scheduleConfig = ScheduleConfig.builder()
                    .scheduleId(scheduleId)
                    .jobId(jobId)
                    .workerGroup(workerGroup)
                    .fixedDelayMs(60_000L)
                    .zoneId("UTC")
                    .build();
            runtime.tock.addSchedule(scheduleConfig);
            jobs.add(JobExecution.builder()
                    .executionId("execution-" + scheduleId)
                    .scheduleId(scheduleId)
                    .jobId(jobId)
                    .workerGroup(workerGroup)
                    .nextFireTime(runtime.tock.currentTimeMillis())
                    .scheduleFingerprint(ScheduleExecutionGuard.fingerprint(scheduleConfig))
                    .params(Collections.<String, Object>emptyMap())
                    .build());
        }
        return jobs;
    }

    private static final class ScenarioDefinition {
        private final String key;
        private final String label;
        private final boolean redis;
        private final TaskSchedulerFactory workerExecutorFactory;

        private ScenarioDefinition(String key, String label, boolean redis, TaskSchedulerFactory workerExecutorFactory) {
            this.key = key;
            this.label = label;
            this.redis = redis;
            this.workerExecutorFactory = workerExecutorFactory;
        }

        private static ScenarioDefinition memoryDefault() {
            return new ScenarioDefinition("memory-default-worker", "memory-default-worker", false,
                    name -> new ScheduledExecutorTaskScheduler(Math.max(2, Runtime.getRuntime().availableProcessors() * 2), name));
        }

        private static ScenarioDefinition memoryHighPrecision() {
            return new ScenarioDefinition("memory-high-precision", "memory-high-precision", false,
                    name -> new HighPrecisionWheelTaskScheduler(Math.max(2, Runtime.getRuntime().availableProcessors() * 2), name));
        }

        private static ScenarioDefinition redisDefault() {
            return new ScenarioDefinition("redis-default-worker", "redis-default-worker", true,
                    name -> new ScheduledExecutorTaskScheduler(Math.max(2, Runtime.getRuntime().availableProcessors() * 2), name));
        }

        private static ScenarioDefinition redisHighPrecision() {
            return new ScenarioDefinition("redis-high-precision", "redis-high-precision", true,
                    name -> new HighPrecisionWheelTaskScheduler(Math.max(2, Runtime.getRuntime().availableProcessors() * 2), name));
        }

        private ScenarioRuntime start(String phase, int round, boolean noOpScheduler) {
            String scope = phase + ":" + key + ":" + round + ":" + UUID.randomUUID().toString().replace("-", "");
            TaskScheduler workerExecutor = workerExecutorFactory.create("perf-" + key + "-" + round);
            if (redis) {
                String namespace = "perf:" + scope;
                JedisPool jedisPool = new JedisPool("127.0.0.1", 6379);
                ensureRedisAvailable(jedisPool);
                RedisTockRegister register = new RedisTockRegister(namespace, jedisPool);
                WorkerQueue workerQueue = RedisSubscribableWorkerQueue.create(namespace, jedisPool);
                ScheduleStore scheduleStore = RedisScheduleStore.create(namespace, jedisPool);
                Config config = Config.builder()
                        .register(register)
                        .scheduleStore(scheduleStore)
                        .workerQueue(workerQueue)
                        .workerExecutor(workerExecutor)
                        .scheduler(noOpScheduler ? new NoOpScheduler() : null)
                        .build();
                return new ScenarioRuntime(this, Tock.configure(config), register, workerQueue, scheduleStore, jedisPool, "tock:redis:" + namespace);
            }

            MemoryTockRegister register = new MemoryTockRegister(scope, MemoryManager.create());
            WorkerQueue workerQueue = MemorySubscribableWorkerQueue.create();
            ScheduleStore scheduleStore = MemoryScheduleStore.create();
            Config config = Config.builder()
                    .register(register)
                    .scheduleStore(scheduleStore)
                    .workerQueue(workerQueue)
                    .workerExecutor(workerExecutor)
                    .scheduler(noOpScheduler ? new NoOpScheduler() : null)
                    .build();
            return new ScenarioRuntime(this, Tock.configure(config), register, workerQueue, scheduleStore, null, null);
        }
    }

    private interface TaskSchedulerFactory {
        TaskScheduler create(String name);
    }

    private static final class ScenarioRuntime implements AutoCloseable {
        private final ScenarioDefinition scenario;
        private final Tock tock;
        private final TockRegister register;
        private final WorkerQueue workerQueue;
        private final ScheduleStore scheduleStore;
        private final JedisPool jedisPool;
        private final String redisNamespacePrefix;

        private ScenarioRuntime(ScenarioDefinition scenario, Tock tock, TockRegister register, WorkerQueue workerQueue,
                                ScheduleStore scheduleStore, JedisPool jedisPool, String redisNamespacePrefix) {
            this.scenario = scenario;
            this.tock = tock;
            this.register = register;
            this.workerQueue = workerQueue;
            this.scheduleStore = scheduleStore;
            this.jedisPool = jedisPool;
            this.redisNamespacePrefix = redisNamespacePrefix;
        }

        @Override
        public void close() {
            try {
                if (tock != null) {
                    tock.shutdown();
                }
            } finally {
                Tock.resetForTest();
                if (jedisPool != null) {
                    cleanupRedisNamespace(jedisPool, redisNamespacePrefix);
                    jedisPool.close();
                }
            }
        }
    }

    private static final class NoOpScheduler implements TockScheduler {
        private final AtomicBoolean running = new AtomicBoolean(false);

        @Override
        public void start(TockContext context) {
            running.set(true);
        }

        @Override
        public void stop() {
            running.set(false);
        }

        @Override
        public void refreshSchedules() {
        }

        @Override
        public boolean isStarted() {
            return running.get();
        }
    }

    private static final class SampleCollector {
        private final int warmupSamples;
        private final int measuredSamples;
        private final CountDownLatch latch;
        private final List<FireSample> samples = new ArrayList<FireSample>();
        private int seen;

        private SampleCollector(int warmupSamples, int measuredSamples, CountDownLatch latch) {
            this.warmupSamples = warmupSamples;
            this.measuredSamples = measuredSamples;
            this.latch = latch;
        }

        private synchronized void record(JobContext context, long observedCurrentTimeMillis) {
            seen++;
            if (seen <= warmupSamples) {
                return;
            }
            if (samples.size() >= measuredSamples) {
                return;
            }
            samples.add(new FireSample(
                    context.getScheduledTime(),
                    context.getActualFireTime(),
                    observedCurrentTimeMillis
            ));
            latch.countDown();
        }

        private synchronized List<FireSample> snapshot() {
            return new ArrayList<FireSample>(samples);
        }
    }

    private static final class FireSample {
        private final long scheduledTimeMs;
        private final long actualFireTimeMs;
        private final long callbackObservedTimeMs;

        private FireSample(long scheduledTimeMs, long actualFireTimeMs, long callbackObservedTimeMs) {
            this.scheduledTimeMs = scheduledTimeMs;
            this.actualFireTimeMs = actualFireTimeMs;
            this.callbackObservedTimeMs = callbackObservedTimeMs;
        }

        private long skewMs() {
            return actualFireTimeMs - scheduledTimeMs;
        }

        private long callbackLagMs() {
            return callbackObservedTimeMs - actualFireTimeMs;
        }
    }

    private static final class PrecisionRoundResult {
        private final int round;
        private final int sampleCount;
        private final double averageAbsoluteSkewMs;
        private final long p50AbsoluteSkewMs;
        private final long p95AbsoluteSkewMs;
        private final long p99AbsoluteSkewMs;
        private final long maxEarlyMs;
        private final long maxLateMs;
        private final double averageCallbackLagMs;
        private final List<FireSample> samples;

        private PrecisionRoundResult(int round, int sampleCount, double averageAbsoluteSkewMs, long p50AbsoluteSkewMs,
                                     long p95AbsoluteSkewMs, long p99AbsoluteSkewMs, long maxEarlyMs, long maxLateMs,
                                     double averageCallbackLagMs, List<FireSample> samples) {
            this.round = round;
            this.sampleCount = sampleCount;
            this.averageAbsoluteSkewMs = averageAbsoluteSkewMs;
            this.p50AbsoluteSkewMs = p50AbsoluteSkewMs;
            this.p95AbsoluteSkewMs = p95AbsoluteSkewMs;
            this.p99AbsoluteSkewMs = p99AbsoluteSkewMs;
            this.maxEarlyMs = maxEarlyMs;
            this.maxLateMs = maxLateMs;
            this.averageCallbackLagMs = averageCallbackLagMs;
            this.samples = samples;
        }

        private static PrecisionRoundResult fromSamples(int round, List<FireSample> samples) {
            List<Long> absoluteSkews = new ArrayList<Long>(samples.size());
            long maxEarlyMs = 0L;
            long maxLateMs = 0L;
            long callbackLagTotal = 0L;
            for (FireSample sample : samples) {
                long skew = sample.skewMs();
                absoluteSkews.add(Math.abs(skew));
                if (skew < maxEarlyMs) {
                    maxEarlyMs = skew;
                }
                if (skew > maxLateMs) {
                    maxLateMs = skew;
                }
                callbackLagTotal += sample.callbackLagMs();
            }
            return new PrecisionRoundResult(
                    round,
                    samples.size(),
                    averageLongs(absoluteSkews),
                    percentile(absoluteSkews, 50),
                    percentile(absoluteSkews, 95),
                    percentile(absoluteSkews, 99),
                    maxEarlyMs,
                    maxLateMs,
                    samples.isEmpty() ? 0.0D : callbackLagTotal / (double) samples.size(),
                    new ArrayList<FireSample>(samples)
            );
        }
    }

    private static final class PrecisionScenarioResult {
        private final ScenarioDefinition scenario;
        private final List<PrecisionRoundResult> rounds;
        private final PrecisionRoundResult aggregate;
        private final List<FireSample> representativeSamples;

        private PrecisionScenarioResult(ScenarioDefinition scenario, List<PrecisionRoundResult> rounds,
                                        PrecisionRoundResult aggregate, List<FireSample> representativeSamples) {
            this.scenario = scenario;
            this.rounds = rounds;
            this.aggregate = aggregate;
            this.representativeSamples = representativeSamples;
        }

        private static PrecisionScenarioResult aggregate(ScenarioDefinition scenario, List<PrecisionRoundResult> rounds,
                                                         List<FireSample> representativeSamples) {
            List<FireSample> allSamples = new ArrayList<FireSample>();
            for (PrecisionRoundResult round : rounds) {
                allSamples.addAll(round.samples);
            }
            return new PrecisionScenarioResult(
                    scenario,
                    rounds,
                    PrecisionRoundResult.fromSamples(0, allSamples),
                    representativeSamples == null ? Collections.<FireSample>emptyList() : representativeSamples
            );
        }
    }

    private static final class ThroughputRoundResult {
        private final int round;
        private final int taskCount;
        private final double totalSeconds;
        private final double pushSeconds;
        private final double throughputPerSecond;
        private final double processCpuLoadPercent;
        private final long peakHeapUsedBytes;
        private final int completedTasks;
        private final boolean warmupOnly;

        private ThroughputRoundResult(int round, int taskCount, double totalSeconds, double pushSeconds,
                                      double throughputPerSecond, double processCpuLoadPercent, long peakHeapUsedBytes,
                                      int completedTasks, boolean warmupOnly) {
            this.round = round;
            this.taskCount = taskCount;
            this.totalSeconds = totalSeconds;
            this.pushSeconds = pushSeconds;
            this.throughputPerSecond = throughputPerSecond;
            this.processCpuLoadPercent = processCpuLoadPercent;
            this.peakHeapUsedBytes = peakHeapUsedBytes;
            this.completedTasks = completedTasks;
            this.warmupOnly = warmupOnly;
        }

        private static ThroughputRoundResult warmup(int round, int taskCount, double totalSeconds, double pushSeconds) {
            return new ThroughputRoundResult(round, taskCount, totalSeconds, pushSeconds, 0.0D, 0.0D, 0L, taskCount, true);
        }

        private static ThroughputRoundResult measured(int round, int taskCount, double totalSeconds, double pushSeconds,
                                                      double throughputPerSecond, double processCpuLoadPercent,
                                                      long peakHeapUsedBytes, int completedTasks) {
            return new ThroughputRoundResult(round, taskCount, totalSeconds, pushSeconds, throughputPerSecond,
                    processCpuLoadPercent, peakHeapUsedBytes, completedTasks, false);
        }
    }

    private static final class ThroughputScenarioResult {
        private final ScenarioDefinition scenario;
        private final List<ThroughputRoundResult> rounds;
        private final double averageThroughputPerSecond;
        private final double p95ThroughputPerSecond;
        private final double averageProcessCpuLoadPercent;
        private final long peakHeapUsedBytes;
        private final double averagePushSeconds;

        private ThroughputScenarioResult(ScenarioDefinition scenario, List<ThroughputRoundResult> rounds,
                                         double averageThroughputPerSecond, double p95ThroughputPerSecond,
                                         double averageProcessCpuLoadPercent, long peakHeapUsedBytes,
                                         double averagePushSeconds) {
            this.scenario = scenario;
            this.rounds = rounds;
            this.averageThroughputPerSecond = averageThroughputPerSecond;
            this.p95ThroughputPerSecond = p95ThroughputPerSecond;
            this.averageProcessCpuLoadPercent = averageProcessCpuLoadPercent;
            this.peakHeapUsedBytes = peakHeapUsedBytes;
            this.averagePushSeconds = averagePushSeconds;
        }

        private static ThroughputScenarioResult aggregate(ScenarioDefinition scenario, List<ThroughputRoundResult> rounds) {
            List<Double> throughputValues = new ArrayList<Double>(rounds.size());
            List<Double> cpuValues = new ArrayList<Double>(rounds.size());
            double pushTotal = 0.0D;
            long peakHeap = 0L;
            for (ThroughputRoundResult round : rounds) {
                if (!round.warmupOnly) {
                    throughputValues.add(round.throughputPerSecond);
                    cpuValues.add(round.processCpuLoadPercent);
                    pushTotal += round.pushSeconds;
                    peakHeap = Math.max(peakHeap, round.peakHeapUsedBytes);
                }
            }
            return new ThroughputScenarioResult(
                    scenario,
                    rounds,
                    averageDoubles(throughputValues),
                    percentileDouble(throughputValues, 95),
                    averageDoubles(cpuValues),
                    peakHeap,
                    rounds.isEmpty() ? 0.0D : pushTotal / rounds.size()
            );
        }
    }

    private static final class ResourceSampler {
        private final MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
        private final com.sun.management.OperatingSystemMXBean osMxBean =
                ManagementFactory.getPlatformMXBean(com.sun.management.OperatingSystemMXBean.class);
        private final AtomicBoolean running = new AtomicBoolean(false);
        private final AtomicLong peakHeapUsedBytes = new AtomicLong(0L);
        private Thread samplerThread;
        private long startWallNs;
        private long endWallNs;
        private long startCpuNs;
        private long endCpuNs;

        private void start() {
            captureHeap();
            startWallNs = System.nanoTime();
            startCpuNs = processCpuTime();
            running.set(true);
            samplerThread = new Thread(() -> {
                while (running.get()) {
                    captureHeap();
                    try {
                        Thread.sleep(50L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "perf-resource-sampler");
            samplerThread.setDaemon(true);
            samplerThread.start();
        }

        private void stop() {
            running.set(false);
            if (samplerThread != null) {
                samplerThread.interrupt();
                try {
                    samplerThread.join(1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            captureHeap();
            endWallNs = System.nanoTime();
            endCpuNs = processCpuTime();
        }

        private void captureHeap() {
            MemoryUsage usage = memoryMXBean.getHeapMemoryUsage();
            long used = usage == null ? 0L : usage.getUsed();
            updatePeak(used);
        }

        private void updatePeak(long value) {
            for (;;) {
                long current = peakHeapUsedBytes.get();
                if (value <= current) {
                    return;
                }
                if (peakHeapUsedBytes.compareAndSet(current, value)) {
                    return;
                }
            }
        }

        private long peakHeapUsedBytes() {
            return peakHeapUsedBytes.get();
        }

        private double processCpuLoadPercent() {
            long wallNs = Math.max(1L, endWallNs - startWallNs);
            long cpuNs = Math.max(0L, endCpuNs - startCpuNs);
            int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
            return (cpuNs / (double) wallNs) * 100.0D / processors;
        }

        private long processCpuTime() {
            return osMxBean == null ? 0L : osMxBean.getProcessCpuTime();
        }
    }

    private static final class BenchmarkConfig {
        private final PrecisionConfig precisionConfig;
        private final ThroughputConfig throughputConfig;
        private final boolean skipPrecision;
        private final boolean skipThroughput;

        private BenchmarkConfig(PrecisionConfig precisionConfig, ThroughputConfig throughputConfig,
                                boolean skipPrecision, boolean skipThroughput) {
            this.precisionConfig = precisionConfig;
            this.throughputConfig = throughputConfig;
            this.skipPrecision = skipPrecision;
            this.skipThroughput = skipThroughput;
        }

        private static BenchmarkConfig defaults() {
            return new BenchmarkConfig(
                    new PrecisionConfig(
                            intProperty("benchmark.precision.rounds", 4),
                            intProperty("benchmark.precision.warmupSamples", 2),
                            intProperty("benchmark.precision.measuredSamples", 8)
                    ),
                    new ThroughputConfig(
                            intProperty("benchmark.throughput.rounds", 4),
                            intProperty("benchmark.throughput.warmupTasks", 500),
                            intProperty("benchmark.throughput.measuredTasks", 4000)
                    ),
                    booleanProperty("benchmark.skipPrecision", false),
                    booleanProperty("benchmark.skipThroughput", false)
            );
        }
    }

    private static final class PrecisionConfig {
        private final int rounds;
        private final int warmupSamples;
        private final int measuredSamples;

        private PrecisionConfig(int rounds, int warmupSamples, int measuredSamples) {
            this.rounds = rounds;
            this.warmupSamples = warmupSamples;
            this.measuredSamples = measuredSamples;
        }
    }

    private static final class ThroughputConfig {
        private final int rounds;
        private final int warmupTasks;
        private final int measuredTasks;

        private ThroughputConfig(int rounds, int warmupTasks, int measuredTasks) {
            this.rounds = rounds;
            this.warmupTasks = warmupTasks;
            this.measuredTasks = measuredTasks;
        }
    }

    private static final class EnvironmentInfo {
        private final String generatedAt;
        private final String osName;
        private final String osVersion;
        private final String javaVersion;
        private final String availableProcessors;
        private final String maxHeapMiB;
        private final String redisVersion;

        private EnvironmentInfo(String generatedAt, String osName, String osVersion, String javaVersion,
                                String availableProcessors, String maxHeapMiB, String redisVersion) {
            this.generatedAt = generatedAt;
            this.osName = osName;
            this.osVersion = osVersion;
            this.javaVersion = javaVersion;
            this.availableProcessors = availableProcessors;
            this.maxHeapMiB = maxHeapMiB;
            this.redisVersion = redisVersion;
        }

        private static EnvironmentInfo capture() {
            String generatedAt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US) {{
                setTimeZone(TimeZone.getTimeZone(REPORT_ZONE));
            }}.format(new Date());
            long maxHeapMiB = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
            return new EnvironmentInfo(
                    generatedAt,
                    System.getProperty("os.name", "unknown"),
                    System.getProperty("os.version", "unknown"),
                    System.getProperty("java.version", "unknown"),
                    String.valueOf(Runtime.getRuntime().availableProcessors()),
                    String.valueOf(maxHeapMiB),
                    detectRedisVersion()
            );
        }
    }

    private static final class BenchmarkReport {
        private final EnvironmentInfo environment;
        private final BenchmarkConfig config;
        private final List<PrecisionScenarioResult> precisionResults;
        private final List<ThroughputScenarioResult> throughputResults;

        private BenchmarkReport(EnvironmentInfo environment, BenchmarkConfig config,
                                List<PrecisionScenarioResult> precisionResults,
                                List<ThroughputScenarioResult> throughputResults) {
            this.environment = environment;
            this.config = config;
            this.precisionResults = precisionResults;
            this.throughputResults = throughputResults;
        }

        private String toPrecisionCsv() {
            StringBuilder builder = new StringBuilder();
            builder.append("scenario,round,samples,avg_abs_skew_ms,p50_abs_skew_ms,p95_abs_skew_ms,p99_abs_skew_ms,max_early_ms,max_late_ms,avg_callback_lag_ms\n");
            for (PrecisionScenarioResult scenario : precisionResults) {
                for (PrecisionRoundResult round : scenario.rounds) {
                    builder.append(scenario.scenario.label).append(',')
                            .append(round.round).append(',')
                            .append(round.sampleCount).append(',')
                            .append(format(round.averageAbsoluteSkewMs)).append(',')
                            .append(round.p50AbsoluteSkewMs).append(',')
                            .append(round.p95AbsoluteSkewMs).append(',')
                            .append(round.p99AbsoluteSkewMs).append(',')
                            .append(round.maxEarlyMs).append(',')
                            .append(round.maxLateMs).append(',')
                            .append(format(round.averageCallbackLagMs)).append('\n');
                }
            }
            return builder.toString();
        }

        private String toPrecisionSamplesCsv() {
            StringBuilder builder = new StringBuilder();
            builder.append("scenario,round,sample_index,scheduled_time_ms,actual_fire_time_ms,callback_observed_time_ms,skew_ms,callback_lag_ms\n");
            for (PrecisionScenarioResult scenario : precisionResults) {
                for (PrecisionRoundResult round : scenario.rounds) {
                    for (int i = 0; i < round.samples.size(); i++) {
                        FireSample sample = round.samples.get(i);
                        builder.append(scenario.scenario.label).append(',')
                                .append(round.round).append(',')
                                .append(i + 1).append(',')
                                .append(sample.scheduledTimeMs).append(',')
                                .append(sample.actualFireTimeMs).append(',')
                                .append(sample.callbackObservedTimeMs).append(',')
                                .append(sample.skewMs()).append(',')
                                .append(sample.callbackLagMs()).append('\n');
                    }
                }
            }
            return builder.toString();
        }

        private String toThroughputCsv() {
            StringBuilder builder = new StringBuilder();
            builder.append("scenario,round,tasks,total_seconds,push_seconds,throughput_per_second,process_cpu_percent,peak_heap_bytes,completed_tasks\n");
            for (ThroughputScenarioResult scenario : throughputResults) {
                for (ThroughputRoundResult round : scenario.rounds) {
                    if (round.warmupOnly) {
                        continue;
                    }
                    builder.append(scenario.scenario.label).append(',')
                            .append(round.round).append(',')
                            .append(round.taskCount).append(',')
                            .append(format(round.totalSeconds)).append(',')
                            .append(format(round.pushSeconds)).append(',')
                            .append(format(round.throughputPerSecond)).append(',')
                            .append(format(round.processCpuLoadPercent)).append(',')
                            .append(round.peakHeapUsedBytes).append(',')
                            .append(round.completedTasks).append('\n');
                }
            }
            return builder.toString();
        }

        private String toConsoleSummary() {
            StringBuilder builder = new StringBuilder();
            if (!precisionResults.isEmpty()) {
                builder.append("=== Precision Benchmark (avgAbs / p95 / p99 ms) ===\n");
                for (PrecisionScenarioResult result : precisionResults) {
                    builder.append(result.scenario.label).append(": ")
                            .append(format(result.aggregate.averageAbsoluteSkewMs)).append(" / ")
                            .append(result.aggregate.p95AbsoluteSkewMs).append(" / ")
                            .append(result.aggregate.p99AbsoluteSkewMs).append('\n');
                }
            }
            if (!throughputResults.isEmpty()) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append("=== Throughput Benchmark (avg tasks/s / avg CPU% / peak heap MiB) ===\n");
                for (ThroughputScenarioResult result : throughputResults) {
                    builder.append(result.scenario.label).append(": ")
                            .append(format(result.averageThroughputPerSecond)).append(" / ")
                            .append(format(result.averageProcessCpuLoadPercent)).append(" / ")
                            .append(formatMiB(result.peakHeapUsedBytes)).append('\n');
                }
            }
            return builder.toString();
        }

        private String toMarkdown() {
            StringBuilder builder = new StringBuilder();
            builder.append("# Tock Core Benchmark Summary\n\n");
            builder.append("## Environment\n\n");
            builder.append("| Item | Value |\n");
            builder.append("| --- | --- |\n");
            builder.append("| Generated at | ").append(environment.generatedAt).append(" |\n");
            builder.append("| OS | ").append(environment.osName).append(' ').append(environment.osVersion).append(" |\n");
            builder.append("| Java | ").append(environment.javaVersion).append(" |\n");
            builder.append("| Available processors | ").append(environment.availableProcessors).append(" |\n");
            builder.append("| JVM max heap (MiB) | ").append(environment.maxHeapMiB).append(" |\n");
            builder.append("| Redis version | ").append(environment.redisVersion).append(" |\n");
            builder.append("| Precision benchmark | ")
                    .append(config.precisionConfig.rounds).append(" rounds, ")
                    .append(config.precisionConfig.warmupSamples).append(" warmup samples, ")
                    .append(config.precisionConfig.measuredSamples).append(" measured samples per round |\n");
            builder.append("| Throughput benchmark | ")
                    .append(config.throughputConfig.rounds).append(" rounds, ")
                    .append(config.throughputConfig.warmupTasks).append(" warmup tasks, ")
                    .append(config.throughputConfig.measuredTasks).append(" measured tasks per round |\n");

            if (!precisionResults.isEmpty()) {
                PrecisionScenarioResult precisionBaseline = findPrecision("memory-high-precision");
                builder.append("\n## Whole-second Precision Summary\n\n");
                builder.append("| Scenario | avg abs skew (ms) | p50 | p95 | p99 | max early | max late | avg callback lag | vs baseline |\n");
                builder.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
                for (PrecisionScenarioResult result : precisionResults) {
                    double baselineDelta = result.aggregate.averageAbsoluteSkewMs - precisionBaseline.aggregate.averageAbsoluteSkewMs;
                    builder.append("| ").append(result.scenario.label)
                            .append(" | ").append(format(result.aggregate.averageAbsoluteSkewMs))
                            .append(" | ").append(result.aggregate.p50AbsoluteSkewMs)
                            .append(" | ").append(result.aggregate.p95AbsoluteSkewMs)
                            .append(" | ").append(result.aggregate.p99AbsoluteSkewMs)
                            .append(" | ").append(result.aggregate.maxEarlyMs)
                            .append(" | ").append(result.aggregate.maxLateMs)
                            .append(" | ").append(format(result.aggregate.averageCallbackLagMs))
                            .append(" | ").append(format(baselineDelta))
                            .append(" |\n");
                }

                builder.append("\n### Precision bar chart (avg abs skew, lower is better)\n\n```text\n");
                appendBarChart(builder, precisionResults, true);
                builder.append("```\n");

                builder.append("\n### Representative whole-second samples\n\n");
                for (PrecisionScenarioResult result : precisionResults) {
                    builder.append("#### ").append(result.scenario.label).append('\n').append('\n');
                    builder.append("| scheduled | actual | callback now | skew (ms) | callback lag (ms) |\n");
                    builder.append("| --- | --- | --- | ---: | ---: |\n");
                    int limit = Math.min(5, result.representativeSamples.size());
                    for (int i = 0; i < limit; i++) {
                        FireSample sample = result.representativeSamples.get(i);
                        builder.append("| ").append(formatEpoch(sample.scheduledTimeMs))
                                .append(" | ").append(formatEpoch(sample.actualFireTimeMs))
                                .append(" | ").append(formatEpoch(sample.callbackObservedTimeMs))
                                .append(" | ").append(sample.skewMs())
                                .append(" | ").append(sample.callbackLagMs())
                                .append(" |\n");
                    }
                    builder.append('\n');
                }

                builder.append("## Per-round Precision Results\n\n");
                builder.append("| Scenario | Round | Samples | avg abs skew | p95 | p99 | max early | max late |\n");
                builder.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
                for (PrecisionScenarioResult result : precisionResults) {
                    for (PrecisionRoundResult round : result.rounds) {
                        builder.append("| ").append(result.scenario.label)
                                .append(" | ").append(round.round)
                                .append(" | ").append(round.sampleCount)
                                .append(" | ").append(format(round.averageAbsoluteSkewMs))
                                .append(" | ").append(round.p95AbsoluteSkewMs)
                                .append(" | ").append(round.p99AbsoluteSkewMs)
                                .append(" | ").append(round.maxEarlyMs)
                                .append(" | ").append(round.maxLateMs)
                                .append(" |\n");
                    }
                }
            }

            if (!throughputResults.isEmpty()) {
                ThroughputScenarioResult throughputBaseline = findThroughput("memory-high-precision");
                builder.append("\n## Throughput Summary\n\n");
                builder.append("| Scenario | avg tasks/s | p95 tasks/s | avg push seconds | avg CPU% | peak heap (MiB) | vs baseline |\n");
                builder.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: |\n");
                for (ThroughputScenarioResult result : throughputResults) {
                    double ratio = throughputBaseline.averageThroughputPerSecond == 0.0D ? 0.0D :
                            result.averageThroughputPerSecond / throughputBaseline.averageThroughputPerSecond;
                    builder.append("| ").append(result.scenario.label)
                            .append(" | ").append(format(result.averageThroughputPerSecond))
                            .append(" | ").append(format(result.p95ThroughputPerSecond))
                            .append(" | ").append(format(result.averagePushSeconds))
                            .append(" | ").append(format(result.averageProcessCpuLoadPercent))
                            .append(" | ").append(formatMiB(result.peakHeapUsedBytes))
                            .append(" | ").append(format(ratio)).append("x")
                            .append(" |\n");
                }

                builder.append("\n### Throughput bar chart (tasks/s, higher is better)\n\n```text\n");
                appendThroughputBarChart(builder, throughputResults);
                builder.append("```\n");

                builder.append("\n## Per-round Throughput Results\n\n");
                builder.append("| Scenario | Round | Tasks | Total seconds | Push seconds | Tasks/s | CPU% | Peak heap (MiB) |\n");
                builder.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
                for (ThroughputScenarioResult result : throughputResults) {
                    for (ThroughputRoundResult round : result.rounds) {
                        if (round.warmupOnly) {
                            continue;
                        }
                        builder.append("| ").append(result.scenario.label)
                                .append(" | ").append(round.round)
                                .append(" | ").append(round.taskCount)
                                .append(" | ").append(format(round.totalSeconds))
                                .append(" | ").append(format(round.pushSeconds))
                                .append(" | ").append(format(round.throughputPerSecond))
                                .append(" | ").append(format(round.processCpuLoadPercent))
                                .append(" | ").append(formatMiB(round.peakHeapUsedBytes))
                                .append(" |\n");
                    }
                }
            }

            builder.append("\n## Capability Notes\n\n");
            builder.append("- `actualFireTime - scheduledTime` is the primary user-facing precision signal for whole-second cron jobs.\n");
            builder.append("- `callback now` records the value returned by `tock.currentTimeMillis()` inside the business callback, showing what the application actually observes.\n");
            builder.append("- Throughput is measured as **immediate JobExecution dispatch capacity** through the WorkerQueue + Worker + JobExecutor path with unique schedule IDs, not as cron generation rate.\n");
            builder.append("- CPU% and heap values are sampled during throughput rounds only, because that phase has sustained load.\n");
            return builder.toString();
        }

        private PrecisionScenarioResult findPrecision(String scenarioKey) {
            for (PrecisionScenarioResult result : precisionResults) {
                if (result.scenario.key.equals(scenarioKey)) {
                    return result;
                }
            }
            throw new IllegalArgumentException("Missing precision scenario: " + scenarioKey);
        }

        private ThroughputScenarioResult findThroughput(String scenarioKey) {
            for (ThroughputScenarioResult result : throughputResults) {
                if (result.scenario.key.equals(scenarioKey)) {
                    return result;
                }
            }
            throw new IllegalArgumentException("Missing throughput scenario: " + scenarioKey);
        }

        private void appendBarChart(StringBuilder builder, List<PrecisionScenarioResult> results, boolean lowerIsBetter) {
            double max = 0.0D;
            for (PrecisionScenarioResult result : results) {
                max = Math.max(max, result.aggregate.averageAbsoluteSkewMs);
            }
            if (max <= 0.0D) {
                max = 1.0D;
            }
            for (PrecisionScenarioResult result : results) {
                int width = Math.max(1, (int) Math.round((result.aggregate.averageAbsoluteSkewMs / max) * 24.0D));
                builder.append(padRight(result.scenario.label, 24))
                        .append(" | ")
                        .append(repeat('█', width))
                        .append(' ')
                        .append(format(result.aggregate.averageAbsoluteSkewMs))
                        .append(lowerIsBetter ? " ms\n" : "\n");
            }
        }

        private void appendThroughputBarChart(StringBuilder builder, List<ThroughputScenarioResult> results) {
            double max = 0.0D;
            for (ThroughputScenarioResult result : results) {
                max = Math.max(max, result.averageThroughputPerSecond);
            }
            if (max <= 0.0D) {
                max = 1.0D;
            }
            for (ThroughputScenarioResult result : results) {
                int width = Math.max(1, (int) Math.round((result.averageThroughputPerSecond / max) * 24.0D));
                builder.append(padRight(result.scenario.label, 24))
                        .append(" | ")
                        .append(repeat('█', width))
                        .append(' ')
                        .append(format(result.averageThroughputPerSecond))
                        .append(" tasks/s\n");
            }
        }
    }

    private static double averageLongs(List<Long> values) {
        if (values.isEmpty()) {
            return 0.0D;
        }
        long total = 0L;
        for (Long value : values) {
            total += value;
        }
        return total / (double) values.size();
    }

    private static double averageDoubles(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0D;
        }
        double total = 0.0D;
        for (Double value : values) {
            total += value;
        }
        return total / values.size();
    }

    private static long percentile(List<Long> values, int percentile) {
        if (values.isEmpty()) {
            return 0L;
        }
        List<Long> sorted = new ArrayList<Long>(values);
        Collections.sort(sorted);
        int index = (int) Math.ceil((percentile / 100.0D) * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }

    private static double percentileDouble(List<Double> values, int percentile) {
        if (values.isEmpty()) {
            return 0.0D;
        }
        List<Double> sorted = new ArrayList<Double>(values);
        Collections.sort(sorted, Comparator.naturalOrder());
        int index = (int) Math.ceil((percentile / 100.0D) * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }

    private static String format(double value) {
        return DECIMAL.format(value);
    }

    private static String formatMiB(long bytes) {
        return format(bytes / (1024.0D * 1024.0D));
    }

    private static String formatEpoch(long epochMs) {
        Instant instant = Instant.ofEpochMilli(epochMs);
        return SAMPLE_TIME_FORMATTER.format(instant) + " (" + epochMs + ")";
    }

    private static String repeat(char c, int times) {
        StringBuilder builder = new StringBuilder(times);
        for (int i = 0; i < times; i++) {
            builder.append(c);
        }
        return builder.toString();
    }

    private static String padRight(String value, int width) {
        StringBuilder builder = new StringBuilder(value == null ? "" : value);
        while (builder.length() < width) {
            builder.append(' ');
        }
        return builder.toString();
    }

    private static int intProperty(String key, int defaultValue) {
        String value = System.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return Integer.parseInt(value.trim());
    }

    private static boolean booleanProperty(String key, boolean defaultValue) {
        String value = System.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private static void ensureRedisAvailable(JedisPool jedisPool) {
        try (Jedis jedis = jedisPool.getResource()) {
            if (!"PONG".equalsIgnoreCase(jedis.ping())) {
                throw new IllegalStateException("Redis 127.0.0.1:6379 is not available");
            }
        }
    }

    private static void cleanupRedisNamespace(JedisPool jedisPool, String namespacePrefix) {
        try (Jedis jedis = jedisPool.getResource()) {
            java.util.Set<String> keys = jedis.keys(namespacePrefix + "*");
            if (keys != null && !keys.isEmpty()) {
                jedis.del(keys.toArray(new String[0]));
            }
        }
    }

    private static String detectRedisVersion() {
        JedisPool pool = new JedisPool("127.0.0.1", 6379);
        try (Jedis jedis = pool.getResource()) {
            String serverInfo = jedis.info("server");
            if (serverInfo == null) {
                return "unknown";
            }
            String[] lines = serverInfo.split("\\r?\\n");
            for (String line : lines) {
                if (line.startsWith("redis_version:")) {
                    return line.substring("redis_version:".length()).trim();
                }
            }
            return "unknown";
        } catch (Exception e) {
            return "unavailable";
        } finally {
            pool.close();
        }
    }
}
