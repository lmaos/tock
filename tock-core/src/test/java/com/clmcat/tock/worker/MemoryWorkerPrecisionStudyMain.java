package com.clmcat.tock.worker;

import com.clmcat.tock.Config;
import com.clmcat.tock.Tock;
import com.clmcat.tock.job.JobContext;
import com.clmcat.tock.money.MemoryManager;
import com.clmcat.tock.registry.memory.MemoryTockRegister;
import com.clmcat.tock.schedule.ScheduleConfig;
import com.clmcat.tock.schedule.ScheduleExecutionGuard;
import com.clmcat.tock.schedule.ScheduleStore;
import com.clmcat.tock.schedule.memory.MemoryScheduleStore;
import com.clmcat.tock.store.JobExecution;
import com.clmcat.tock.worker.memory.MemorySubscribableWorkerQueue;
import com.clmcat.tock.worker.scheduler.HighPrecisionWheelTaskScheduler;
import com.clmcat.tock.worker.scheduler.ScheduledExecutorTaskScheduler;
import com.clmcat.tock.worker.scheduler.TaskScheduler;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MemoryWorkerPrecisionStudyMain {

    private static final DecimalFormat DECIMAL = new DecimalFormat("0.000", DecimalFormatSymbols.getInstance(Locale.US));
    private static final int WARMUP_SAMPLES = Integer.getInteger("study.warmup", 16);
    private static final int MEASURED_SAMPLES = Integer.getInteger("study.measured", 60);
    private static final long DELAY_MS = Long.getLong("study.delayMs", 150L);
    private static final long SAMPLE_TIMEOUT_SECONDS = Long.getLong("study.timeoutSeconds", 5L);

    private MemoryWorkerPrecisionStudyMain() {
    }

    public static void main(String[] args) throws Exception {
        Path outputDir = Paths.get("target", "memory-worker-precision-study");
        Files.createDirectories(outputDir);

        List<StudyDefinition> studies = Arrays.asList(
                new StudyDefinition("default-worker", name -> new ScheduledExecutorTaskScheduler(Math.max(2, Runtime.getRuntime().availableProcessors() * 2), name)),
                new StudyDefinition("high-precision", name -> new HighPrecisionWheelTaskScheduler(Math.max(2, Runtime.getRuntime().availableProcessors() * 2), name))
        );

        List<StudyResult> results = new ArrayList<StudyResult>();
        for (StudyDefinition study : studies) {
            results.add(runStudy(study));
        }

        StudyReport report = new StudyReport(results);
        Files.write(outputDir.resolve("summary.md"), report.toMarkdown().getBytes(StandardCharsets.UTF_8));
        Files.write(outputDir.resolve("summary.csv"), report.toCsv().getBytes(StandardCharsets.UTF_8));
        System.out.println(report.toConsoleSummary());
    }

    private static StudyResult runStudy(StudyDefinition definition) throws Exception {
        TaskScheduler schedulerOnly = definition.createScheduler("memory-study-scheduler-" + definition.key);
        MetricSummary schedulerSummary;
        try {
            schedulerSummary = runSchedulerOnlyStudy(schedulerOnly);
        } finally {
            schedulerOnly.stop();
        }

        TaskScheduler workerScheduler = definition.createScheduler("memory-study-worker-" + definition.key);
        MemoryWorkerSummary workerSummary = runWorkerChainStudy(definition, workerScheduler);
        return new StudyResult(definition.key, schedulerSummary, workerSummary.schedulerSummary, workerSummary.samples);
    }

    private static MetricSummary runSchedulerOnlyStudy(TaskScheduler scheduler) throws Exception {
        scheduler.start(null);
        int total = WARMUP_SAMPLES + MEASURED_SAMPLES;
        List<Double> skewsMs = new ArrayList<Double>(MEASURED_SAMPLES);
        for (int i = 0; i < total; i++) {
            CountDownLatch latch = new CountDownLatch(1);
            long targetNano = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DELAY_MS);
            final long[] actualNano = new long[1];
            scheduler.schedule(() -> {
                actualNano[0] = System.nanoTime();
                latch.countDown();
            }, DELAY_MS, TimeUnit.MILLISECONDS);
            if (!latch.await(SAMPLE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Scheduler-only study timed out for " + scheduler.getClass().getSimpleName());
            }
            if (i >= WARMUP_SAMPLES) {
                skewsMs.add((actualNano[0] - targetNano) / 1_000_000.0D);
            }
        }
        return MetricSummary.from(skewsMs);
    }

    private static MemoryWorkerSummary runWorkerChainStudy(StudyDefinition definition, TaskScheduler workerScheduler) throws Exception {
        String scope = "memory-study:" + definition.key + ":" + UUID.randomUUID().toString().replace("-", "");
        MemoryTockRegister register = new MemoryTockRegister(scope, MemoryManager.create());
        ScheduleStore scheduleStore = MemoryScheduleStore.create();
        WorkerQueue workerQueue = MemorySubscribableWorkerQueue.create();
        NoOpScheduler noOpScheduler = new NoOpScheduler();
        Config config = Config.builder()
                .register(register)
                .scheduleStore(scheduleStore)
                .workerQueue(workerQueue)
                .workerExecutor(workerScheduler)
                .scheduler(noOpScheduler)
                .build();

        Tock tock = null;
        try {
            tock = Tock.configure(config);
            final Tock[] tockHolder = new Tock[]{tock};
            final String workerGroup = "group-" + definition.key;
            final String jobId = "job-" + definition.key;
            final String scheduleId = "schedule-" + definition.key;
            final ArrayBlockingQueue<WorkerFireSample> queue = new ArrayBlockingQueue<WorkerFireSample>(1);
            final ScheduleConfig scheduleConfig = ScheduleConfig.builder()
                    .scheduleId(scheduleId)
                    .jobId(jobId)
                    .workerGroup(workerGroup)
                    .fixedDelayMs(60_000L)
                    .zoneId("UTC")
                    .build();

            tock.registerJob(jobId, ctx -> {
                WorkerFireSample sample = new WorkerFireSample(
                        ((Number) ctx.getParams().get("sample")).intValue(),
                        ctx.getScheduledTime(),
                        ctx.getActualFireTime(),
                        tockHolder[0].currentTimeMillis(),
                        System.currentTimeMillis()
                );
                if (!queue.offer(sample)) {
                    throw new IllegalStateException("Worker study sample queue overflow");
                }
            });
            tock.start();
            tock.joinGroup(workerGroup);
            tock.addSchedule(scheduleConfig);

            List<WorkerFireSample> measured = new ArrayList<WorkerFireSample>(MEASURED_SAMPLES);
            int total = WARMUP_SAMPLES + MEASURED_SAMPLES;
            for (int i = 0; i < total; i++) {
                long scheduledTime = tock.currentTimeMillis() + DELAY_MS;
                JobExecution execution = JobExecution.builder()
                        .executionId("execution-" + definition.key + "-" + i)
                        .scheduleId(scheduleId)
                        .jobId(jobId)
                        .workerGroup(workerGroup)
                        .nextFireTime(scheduledTime)
                        .scheduleFingerprint(ScheduleExecutionGuard.fingerprint(scheduleConfig))
                        .params(Collections.<String, Object>singletonMap("sample", i))
                        .build();
                workerQueue.push(execution, workerGroup);
                WorkerFireSample sample = queue.poll(SAMPLE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (sample == null) {
                    throw new IllegalStateException("Worker-chain study timed out for " + definition.key + " sample " + i);
                }
                if (i >= WARMUP_SAMPLES) {
                    measured.add(sample);
                }
            }
            return new MemoryWorkerSummary(MetricSummary.fromWorkerSamples(measured), measured);
        } finally {
            if (tock != null) {
                tock.shutdown();
            }
            resetTockForTest();
        }
    }

    private static void resetTockForTest() {
        try {
            java.lang.reflect.Method method = Tock.class.getDeclaredMethod("resetForTest");
            method.setAccessible(true);
            method.invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to reset Tock test singleton", e);
        }
    }

    private interface TaskSchedulerFactory {
        TaskScheduler create(String name);
    }

    private static final class StudyDefinition {
        private final String key;
        private final TaskSchedulerFactory factory;

        private StudyDefinition(String key, TaskSchedulerFactory factory) {
            this.key = key;
            this.factory = factory;
        }

        private TaskScheduler createScheduler(String name) {
            return factory.create(name);
        }
    }

    private static final class MetricSummary {
        private final int sampleCount;
        private final double averageAbsSkewMs;
        private final double p95AbsSkewMs;
        private final double maxLateMs;
        private final double maxEarlyMs;

        private MetricSummary(int sampleCount, double averageAbsSkewMs, double p95AbsSkewMs, double maxLateMs, double maxEarlyMs) {
            this.sampleCount = sampleCount;
            this.averageAbsSkewMs = averageAbsSkewMs;
            this.p95AbsSkewMs = p95AbsSkewMs;
            this.maxLateMs = maxLateMs;
            this.maxEarlyMs = maxEarlyMs;
        }

        private static MetricSummary from(List<Double> signedSkewsMs) {
            if (signedSkewsMs.isEmpty()) {
                return new MetricSummary(0, 0.0D, 0.0D, 0.0D, 0.0D);
            }
            List<Double> absolute = new ArrayList<Double>(signedSkewsMs.size());
            double absSum = 0.0D;
            double maxLate = Double.NEGATIVE_INFINITY;
            double maxEarly = Double.POSITIVE_INFINITY;
            for (Double value : signedSkewsMs) {
                double skew = value.doubleValue();
                absolute.add(Math.abs(skew));
                absSum += Math.abs(skew);
                maxLate = Math.max(maxLate, skew);
                maxEarly = Math.min(maxEarly, skew);
            }
            Collections.sort(absolute);
            return new MetricSummary(
                    signedSkewsMs.size(),
                    absSum / signedSkewsMs.size(),
                    percentile(absolute, 95.0D),
                    maxLate,
                    maxEarly
            );
        }

        private static MetricSummary fromWorkerSamples(List<WorkerFireSample> samples) {
            List<Double> skews = new ArrayList<Double>(samples.size());
            for (WorkerFireSample sample : samples) {
                skews.add((double) (sample.actualFireTime - sample.scheduledTime));
            }
            return from(skews);
        }
    }

    private static final class WorkerFireSample {
        private final int index;
        private final long scheduledTime;
        private final long actualFireTime;
        private final long callbackNow;
        private final long callbackSystemNow;

        private WorkerFireSample(int index, long scheduledTime, long actualFireTime, long callbackNow, long callbackSystemNow) {
            this.index = index;
            this.scheduledTime = scheduledTime;
            this.actualFireTime = actualFireTime;
            this.callbackNow = callbackNow;
            this.callbackSystemNow = callbackSystemNow;
        }
    }

    private static final class MemoryWorkerSummary {
        private final MetricSummary schedulerSummary;
        private final List<WorkerFireSample> samples;

        private MemoryWorkerSummary(MetricSummary schedulerSummary, List<WorkerFireSample> samples) {
            this.schedulerSummary = schedulerSummary;
            this.samples = samples;
        }
    }

    private static final class StudyResult {
        private final String scenario;
        private final MetricSummary schedulerOnly;
        private final MetricSummary workerChain;
        private final List<WorkerFireSample> workerSamples;

        private StudyResult(String scenario, MetricSummary schedulerOnly, MetricSummary workerChain, List<WorkerFireSample> workerSamples) {
            this.scenario = scenario;
            this.schedulerOnly = schedulerOnly;
            this.workerChain = workerChain;
            this.workerSamples = workerSamples;
        }
    }

    private static final class StudyReport {
        private final List<StudyResult> results;

        private StudyReport(List<StudyResult> results) {
            this.results = results;
        }

        private String toConsoleSummary() {
            StringBuilder builder = new StringBuilder();
            builder.append("=== Memory scheduler / worker precision study ===\n");
            for (StudyResult result : results) {
                builder.append(result.scenario)
                        .append(" | scheduler-only avg abs ").append(format(result.schedulerOnly.averageAbsSkewMs)).append(" ms")
                        .append(" | worker-chain avg abs ").append(format(result.workerChain.averageAbsSkewMs)).append(" ms")
                        .append('\n');
            }
            return builder.toString();
        }

        private String toCsv() {
            StringBuilder builder = new StringBuilder();
            builder.append("scenario,layer,samples,avg_abs_skew_ms,p95_abs_skew_ms,max_late_ms,max_early_ms\n");
            for (StudyResult result : results) {
                appendCsvRow(builder, result.scenario, "scheduler-only", result.schedulerOnly);
                appendCsvRow(builder, result.scenario, "worker-chain", result.workerChain);
            }
            return builder.toString();
        }

        private void appendCsvRow(StringBuilder builder, String scenario, String layer, MetricSummary summary) {
            builder.append(scenario).append(',')
                    .append(layer).append(',')
                    .append(summary.sampleCount).append(',')
                    .append(format(summary.averageAbsSkewMs)).append(',')
                    .append(format(summary.p95AbsSkewMs)).append(',')
                    .append(format(summary.maxLateMs)).append(',')
                    .append(format(summary.maxEarlyMs)).append('\n');
        }

        private String toMarkdown() {
            StringBuilder builder = new StringBuilder();
            builder.append("# Memory scheduler / worker precision study\n\n");
            builder.append("- warmup samples: ").append(WARMUP_SAMPLES).append('\n');
            builder.append("- measured samples: ").append(MEASURED_SAMPLES).append('\n');
            builder.append("- delay per sample: ").append(DELAY_MS).append(" ms\n\n");
            builder.append("| scenario | layer | samples | avg abs skew (ms) | p95 abs skew | max late | max early |\n");
            builder.append("| --- | --- | ---: | ---: | ---: | ---: | ---: |\n");
            for (StudyResult result : results) {
                appendMarkdownRow(builder, result.scenario, "scheduler-only", result.schedulerOnly);
                appendMarkdownRow(builder, result.scenario, "worker-chain", result.workerChain);
            }
            builder.append("\n## Worker-chain representative samples\n\n");
            for (StudyResult result : results) {
                builder.append("### ").append(result.scenario).append('\n').append('\n');
                builder.append("| sample | scheduled | actual | callback now | callback system | skew |\n");
                builder.append("| ---: | ---: | ---: | ---: | ---: | ---: |\n");
                List<WorkerFireSample> samples = new ArrayList<WorkerFireSample>(result.workerSamples);
                samples.sort(Comparator.comparingInt(sample -> sample.index));
                for (int i = 0; i < Math.min(8, samples.size()); i++) {
                    WorkerFireSample sample = samples.get(i);
                    builder.append("| ").append(sample.index)
                            .append(" | ").append(sample.scheduledTime)
                            .append(" | ").append(sample.actualFireTime)
                            .append(" | ").append(sample.callbackNow)
                            .append(" | ").append(sample.callbackSystemNow)
                            .append(" | ").append(sample.actualFireTime - sample.scheduledTime)
                            .append(" |\n");
                }
                builder.append('\n');
            }
            return builder.toString();
        }

        private void appendMarkdownRow(StringBuilder builder, String scenario, String layer, MetricSummary summary) {
            builder.append("| ").append(scenario)
                    .append(" | ").append(layer)
                    .append(" | ").append(summary.sampleCount)
                    .append(" | ").append(format(summary.averageAbsSkewMs))
                    .append(" | ").append(format(summary.p95AbsSkewMs))
                    .append(" | ").append(format(summary.maxLateMs))
                    .append(" | ").append(format(summary.maxEarlyMs))
                    .append(" |\n");
        }
    }

    private static double percentile(List<Double> sortedValues, double percentile) {
        if (sortedValues.isEmpty()) {
            return 0.0D;
        }
        int index = (int) Math.ceil((percentile / 100.0D) * sortedValues.size()) - 1;
        index = Math.max(0, Math.min(sortedValues.size() - 1, index));
        return sortedValues.get(index);
    }

    private static String format(double value) {
        return DECIMAL.format(value);
    }

    private static final class NoOpScheduler implements com.clmcat.tock.scheduler.TockScheduler {
        private final AtomicBoolean running = new AtomicBoolean(false);

        @Override
        public void start(com.clmcat.tock.TockContext context) {
            running.set(true);
        }

        @Override
        public void stop() {
            running.set(false);
        }

        @Override
        public boolean isRunning() {
            return running.get();
        }

        @Override
        public void refreshSchedules() {
        }
    }
}
