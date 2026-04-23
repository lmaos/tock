package com.clmcat.tock.worker;

import com.clmcat.tock.Config;
import com.clmcat.tock.Tock;
import com.clmcat.tock.TockContext;
import com.clmcat.tock.TockContextAware;
import com.clmcat.tock.money.MemoryManager;
import com.clmcat.tock.registry.TockRegister;
import com.clmcat.tock.registry.memory.MemoryTockRegister;
import com.clmcat.tock.registry.redis.RedisTockRegister;
import com.clmcat.tock.schedule.ScheduleConfig;
import com.clmcat.tock.schedule.ScheduleStore;
import com.clmcat.tock.schedule.memory.MemoryScheduleStore;
import com.clmcat.tock.schedule.redis.RedisScheduleStore;
import com.clmcat.tock.store.JobExecution;
import com.clmcat.tock.time.TimeSynchronizer;
import com.clmcat.tock.worker.memory.MemorySubscribableWorkerQueue;
import com.clmcat.tock.worker.redis.RedisSubscribableWorkerQueue;
import com.clmcat.tock.worker.scheduler.HighPrecisionWheelTaskScheduler;
import com.clmcat.tock.worker.scheduler.ScheduledExecutorTaskScheduler;
import com.clmcat.tock.worker.scheduler.TaskScheduler;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.concurrent.locks.LockSupport;

public final class RedisHighPrecisionStudyMain {

    private static final DecimalFormat DECIMAL = new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(Locale.US));
    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(REPORT_ZONE);
    private static final int WARMUP_SAMPLES = 2;
    private static final int MEASURED_SAMPLES = 6;
    private static final long ROUND_TIMEOUT_SECONDS = 20L;
    private static final int MEMORY_ROUNDS = Integer.getInteger("study.rounds.memory", 4);
    private static final int REDIS_ROUNDS = Integer.getInteger("study.rounds.redis", 10);

    private RedisHighPrecisionStudyMain() {
    }

    public static void main(String[] args) throws Exception {
        Path outputDir = Paths.get("target", "performance", "redis-precision-study");
        Files.createDirectories(outputDir);

        List<ScenarioDefinition> scenarios = Arrays.asList(
                ScenarioDefinition.memoryDefault(),
                ScenarioDefinition.memoryHighPrecision(),
                ScenarioDefinition.redisDefault(),
                ScenarioDefinition.redisHighPrecisionAutoDefault(),
                ScenarioDefinition.redisHighPrecision200Spin(),
                ScenarioDefinition.redisHighPrecision200NoSpin(),
                ScenarioDefinition.redisHighPrecision1000Spin(),
                ScenarioDefinition.redisHighPrecision2000Spin(),
                ScenarioDefinition.redisHighPrecision1000NoSpin()
        );
        String scenarioFilter = System.getProperty("study.scenarios");
        if (scenarioFilter != null && !scenarioFilter.trim().isEmpty()) {
            List<String> requested = Arrays.asList(scenarioFilter.split(","));
            List<ScenarioDefinition> filtered = new ArrayList<ScenarioDefinition>();
            for (ScenarioDefinition scenario : scenarios) {
                if (requested.contains(scenario.key)) {
                    filtered.add(scenario);
                }
            }
            scenarios = filtered;
        }

        List<ScenarioStudy> studies = new ArrayList<ScenarioStudy>();
        for (ScenarioDefinition scenario : scenarios) {
            System.out.println("Running study for " + scenario.label + " ...");
            studies.add(runScenarioStudy(scenario));
        }

        StudyReport report = new StudyReport(studies);
        Files.write(outputDir.resolve("redis-precision-study.md"), report.toMarkdown().getBytes(StandardCharsets.UTF_8));
        Files.write(outputDir.resolve("redis-precision-study.csv"), report.toCsv().getBytes(StandardCharsets.UTF_8));
        Files.write(outputDir.resolve("redis-precision-study-traces.csv"), report.toTraceCsv().getBytes(StandardCharsets.UTF_8));
        Files.write(outputDir.resolve("redis-precision-study.txt"), report.toConsoleSummary().getBytes(StandardCharsets.UTF_8));
        System.out.println(report.toConsoleSummary());
    }

    private static ScenarioStudy runScenarioStudy(ScenarioDefinition scenario) throws Exception {
        List<RoundStudy> rounds = new ArrayList<RoundStudy>();
        List<Trace> representativeSamples = Collections.emptyList();
        for (int round = 1; round <= scenario.rounds; round++) {
            ScenarioRuntime runtime = null;
            try {
                runtime = scenario.start(round);
                TraceRecorder recorder = new TraceRecorder(WARMUP_SAMPLES + MEASURED_SAMPLES);
                DiagnosticWorker worker = new DiagnosticWorker(recorder);
                RecordingSubscribableWorkerQueue queue = new RecordingSubscribableWorkerQueue(runtime.workerQueueDelegate);
                queue.attachRecorder(recorder);

                Config config = Config.builder()
                        .register(runtime.register)
                        .scheduleStore(runtime.scheduleStore)
                        .workerQueue(queue)
                        .workerExecutor(runtime.workerExecutor)
                        .worker(worker)
                        .build();

                Tock tock = Tock.configure(config);
                runtime.attachTock(tock);
                final String workerGroup = "study-" + scenario.key;
                final String jobId = "job-" + scenario.key + "-" + round;
                final String scheduleId = "schedule-" + scenario.key + "-" + round;
                tock.registerJob(jobId, ctx -> {
                    // real execution path only; timing already captured in worker hooks
                });
                tock.start();
                tock.joinGroup(workerGroup);
                tock.addSchedule(ScheduleConfig.builder()
                        .scheduleId(scheduleId)
                        .jobId(jobId)
                        .workerGroup(workerGroup)
                        .cron("*/1 * * * * ?")
                        .zoneId(REPORT_ZONE.getId())
                        .build());
                tock.refreshSchedules();

                if (!recorder.await(ROUND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting for samples in " + scenario.label + " round " + round);
                }
                List<Trace> traces = recorder.completedTraces(WARMUP_SAMPLES);
                if (round == 1) {
                    representativeSamples = traces;
                }
                rounds.add(RoundStudy.from(round, traces));
            } finally {
                if (runtime != null) {
                    runtime.close();
                }
            }
        }
        return ScenarioStudy.from(scenario, rounds, representativeSamples);
    }

    private static final class ScenarioDefinition {
        private final String key;
        private final String label;
        private final boolean redis;
        private final int rounds;
        private final TaskSchedulerFactory schedulerFactory;

        private ScenarioDefinition(String key, String label, boolean redis, int rounds, TaskSchedulerFactory schedulerFactory) {
            this.key = key;
            this.label = label;
            this.redis = redis;
            this.rounds = rounds;
            this.schedulerFactory = schedulerFactory;
        }

        private static ScenarioDefinition memoryDefault() {
            return new ScenarioDefinition("memory-default-worker", "memory-default-worker", false, MEMORY_ROUNDS,
                    name -> new ScheduledExecutorTaskScheduler(Math.max(2, Runtime.getRuntime().availableProcessors() * 2), name));
        }

        private static ScenarioDefinition memoryHighPrecision() {
            return new ScenarioDefinition("memory-high-precision", "memory-high-precision", false, MEMORY_ROUNDS,
                    name -> new HighPrecisionWheelTaskScheduler(Math.max(2, Runtime.getRuntime().availableProcessors() * 2), name));
        }

        private static ScenarioDefinition redisDefault() {
            return new ScenarioDefinition("redis-default-worker", "redis-default-worker", true, REDIS_ROUNDS,
                    name -> new ScheduledExecutorTaskScheduler(Math.max(2, Runtime.getRuntime().availableProcessors() * 2), name));
        }

        private static ScenarioDefinition redisHighPrecision200Spin() {
            return new ScenarioDefinition("redis-high-precision-200us-spin", "redis-high-precision-200us-spin", true, REDIS_ROUNDS,
                    name -> createHighPrecision(name, 200_000L, false));
        }

        private static ScenarioDefinition redisHighPrecisionAutoDefault() {
            return new ScenarioDefinition("redis-high-precision-auto-default", "redis-high-precision-auto-default", true, REDIS_ROUNDS,
                    name -> new HighPrecisionWheelTaskScheduler(Math.max(2, Runtime.getRuntime().availableProcessors() * 2), name));
        }

        private static ScenarioDefinition redisHighPrecision1000Spin() {
            return new ScenarioDefinition("redis-high-precision-1000us-spin", "redis-high-precision-1000us-spin", true, REDIS_ROUNDS,
                    name -> createHighPrecision(name, 1_000_000L, false));
        }

        private static ScenarioDefinition redisHighPrecision200NoSpin() {
            return new ScenarioDefinition("redis-high-precision-200us-nosspin", "redis-high-precision-200us-nosspin", true, REDIS_ROUNDS,
                    name -> createHighPrecision(name, 200_000L, true));
        }

        private static ScenarioDefinition redisHighPrecision2000Spin() {
            return new ScenarioDefinition("redis-high-precision-2000us-spin", "redis-high-precision-2000us-spin", true, REDIS_ROUNDS,
                    name -> createHighPrecision(name, 2_000_000L, false));
        }

        private static ScenarioDefinition redisHighPrecision1000NoSpin() {
            return new ScenarioDefinition("redis-high-precision-1000us-nosspin", "redis-high-precision-1000us-nosspin", true, REDIS_ROUNDS,
                    name -> createHighPrecision(name, 1_000_000L, true));
        }

        private ScenarioRuntime start(int round) {
            String scope = "study:" + key + ":" + round + ":" + UUID.randomUUID().toString().replace("-", "");
            TaskScheduler scheduler = schedulerFactory.create("study-" + key + "-" + round);
            if (redis) {
                String namespace = scope;
                JedisPool jedisPool = new JedisPool("127.0.0.1", 6379);
                ensureRedisAvailable(jedisPool);
                RedisTockRegister register = new RedisTockRegister(namespace, jedisPool);
                ScheduleStore scheduleStore = RedisScheduleStore.create(namespace, jedisPool);
                WorkerQueue workerQueue = RedisSubscribableWorkerQueue.create(namespace, jedisPool);
                return new ScenarioRuntime(this, register, scheduleStore, workerQueue, scheduler, jedisPool, "tock:redis:" + namespace);
            }
            MemoryTockRegister register = new MemoryTockRegister(scope, MemoryManager.create());
            ScheduleStore scheduleStore = MemoryScheduleStore.create();
            WorkerQueue workerQueue = MemorySubscribableWorkerQueue.create();
            return new ScenarioRuntime(this, register, scheduleStore, workerQueue, scheduler, null, null);
        }

        private static TaskScheduler createHighPrecision(String name, long advanceNanos, boolean disableSpin) {
            TunableHighPrecisionWheelTaskScheduler scheduler = new TunableHighPrecisionWheelTaskScheduler(
                    Math.max(2, Runtime.getRuntime().availableProcessors() * 2), name, disableSpin);
//            scheduler.setAdvanceNanos(advanceNanos); 作废了。 这个值没用了。
            return scheduler;
        }
    }

    private interface TaskSchedulerFactory {
        TaskScheduler create(String name);
    }

    private static final class ScenarioRuntime implements AutoCloseable {
        private final ScenarioDefinition scenario;
        private final TockRegister register;
        private final ScheduleStore scheduleStore;
        private final WorkerQueue workerQueueDelegate;
        private final TaskScheduler workerExecutor;
        private final JedisPool jedisPool;
        private final String redisNamespacePrefix;
        private Tock tock;

        private ScenarioRuntime(ScenarioDefinition scenario, TockRegister register, ScheduleStore scheduleStore,
                                WorkerQueue workerQueueDelegate, TaskScheduler workerExecutor,
                                JedisPool jedisPool, String redisNamespacePrefix) {
            this.scenario = scenario;
            this.register = register;
            this.scheduleStore = scheduleStore;
            this.workerQueueDelegate = workerQueueDelegate;
            this.workerExecutor = workerExecutor;
            this.jedisPool = jedisPool;
            this.redisNamespacePrefix = redisNamespacePrefix;
        }

        private void attachTock(Tock tock) {
            this.tock = tock;
        }

        @Override
        public void close() {
            try {
                if (tock != null) {
                    tock.shutdown();
                }
            } finally {
                resetTockForTest();
                if (jedisPool != null) {
                    cleanupRedisNamespace(jedisPool, redisNamespacePrefix);
                    jedisPool.close();
                }
            }
        }
    }

    private static final class RecordingSubscribableWorkerQueue implements SubscribableWorkerQueue, TockContextAware, com.clmcat.tock.Lifecycle {
        private final WorkerQueue delegate;
        private TraceRecorder recorder;

        private RecordingSubscribableWorkerQueue(WorkerQueue delegate) {
            this.delegate = delegate;
        }

        private void attachRecorder(TraceRecorder recorder) {
            this.recorder = recorder;
        }

        @Override
        public void push(JobExecution execution, String workerGroup) {
            recorder.onPush(execution, System.nanoTime(), System.currentTimeMillis());
            delegate.push(execution, workerGroup);
        }

        @Override
        public void subscribe(String workerGroup, Consumer<JobExecution> consumer) {
            ((SubscribableWorkerQueue) delegate).subscribe(workerGroup, execution -> {
                recorder.onDispatch(execution, System.nanoTime(), System.currentTimeMillis());
                consumer.accept(execution);
            });
        }

        @Override
        public void unsubscribe(String workerGroup) {
            ((SubscribableWorkerQueue) delegate).unsubscribe(workerGroup);
        }

        @Override
        public void setTockContext(TockContext context) {
            if (delegate instanceof TockContextAware) {
                ((TockContextAware) delegate).setTockContext(context);
            }
        }

        @Override
        public void start(TockContext context) {
            if (delegate instanceof com.clmcat.tock.Lifecycle) {
                ((com.clmcat.tock.Lifecycle) delegate).start(context);
            }
        }

        @Override
        public void stop() {
            if (delegate instanceof com.clmcat.tock.Lifecycle) {
                ((com.clmcat.tock.Lifecycle) delegate).stop();
            }
        }

        @Override
        public boolean isStarted() {
            return !(delegate instanceof com.clmcat.tock.Lifecycle) || ((com.clmcat.tock.Lifecycle) delegate).isStarted();
        }
    }

    private static final class DiagnosticWorker extends DefaultTockWorker {
        private final TraceRecorder recorder;
        private TockContext context;

        private DiagnosticWorker(TraceRecorder recorder) {
            this.recorder = recorder;
        }

        @Override
        public synchronized void start(TockContext context) {
            this.context = context;
            super.start(context);
        }

        @Override
        protected void onExecutionReceived(JobExecution jobExecution, long currentTimeMs, long nextFireTimeMs, long delayMs) {
            recorder.onReceive(jobExecution, System.nanoTime(), System.currentTimeMillis(), currentTimeMs,
                    context.getTimeProvider().currentTimeMillis(), currentOffsetMs(), delayMs);
        }

        @Override
        protected void onExecutionScheduled(JobExecution jobExecution, long delayMs, boolean immediate) {
            recorder.onSchedule(jobExecution, System.nanoTime(), delayMs, immediate);
        }

        @Override
        protected void onScheduledCallback(JobExecution jobExecution, long remainingMs) {
            recorder.onSchedulerCallback(jobExecution, System.nanoTime(), remainingMs);
        }

        @Override
        protected void onExecutionRescheduled(JobExecution jobExecution, long remainingMs) {
            recorder.onReschedule(jobExecution, remainingMs);
        }

        @Override
        protected void onExecutionDue(JobExecution jobExecution, long currentTimeMs) {
            recorder.onDue(jobExecution, System.nanoTime(), currentTimeMs);
        }

        @Override
        void doExecuteJob(JobExecution jobExecution) {
            recorder.onExecute(jobExecution, System.nanoTime(), System.currentTimeMillis(), context.currentTimeMillis(),
                    context.getTimeProvider().currentTimeMillis(), currentOffsetMs());
            super.doExecuteJob(jobExecution);
        }

        private long currentOffsetMs() {
            return context.getTimeSource() instanceof TimeSynchronizer
                    ? ((TimeSynchronizer) context.getTimeSource()).offset()
                    : 0L;
        }
    }

    private static final class TraceRecorder {
        private final CountDownLatch latch;
        private final Map<String, Trace> traces = new ConcurrentHashMap<String, Trace>();

        private TraceRecorder(int expectedExecutions) {
            this.latch = new CountDownLatch(expectedExecutions);
        }

        private void onPush(JobExecution execution, long nanoTime, long systemMs) {
            trace(execution).recordPush(nanoTime, systemMs);
        }

        private void onDispatch(JobExecution execution, long nanoTime, long systemMs) {
            trace(execution).recordDispatch(nanoTime, systemMs);
        }

        private void onReceive(JobExecution execution, long nanoTime, long systemMs, long contextMs, long providerMs, long offsetMs, long delayMs) {
            trace(execution).recordReceive(nanoTime, systemMs, contextMs, providerMs, offsetMs, delayMs);
        }

        private void onSchedule(JobExecution execution, long nanoTime, long delayMs, boolean immediate) {
            trace(execution).recordSchedule(nanoTime, delayMs, immediate);
        }

        private void onSchedulerCallback(JobExecution execution, long nanoTime, long remainingMs) {
            trace(execution).recordCallback(nanoTime, remainingMs);
        }

        private void onReschedule(JobExecution execution, long remainingMs) {
            trace(execution).recordReschedule(remainingMs);
        }

        private void onDue(JobExecution execution, long nanoTime, long currentTimeMs) {
            trace(execution).recordDue(nanoTime, currentTimeMs);
        }

        private void onExecute(JobExecution execution, long nanoTime, long systemMs, long contextMs, long providerMs, long offsetMs) {
            if (trace(execution).recordExecute(nanoTime, systemMs, contextMs, providerMs, offsetMs)) {
                latch.countDown();
            }
        }

        private boolean await(long timeout, TimeUnit unit) {
            try {
                return latch.await(timeout, unit);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private List<Trace> completedTraces(int warmupSamples) {
            List<Trace> completed = new ArrayList<Trace>();
            for (Trace trace : traces.values()) {
                if (trace.isComplete()) {
                    completed.add(trace);
                }
            }
            Collections.sort(completed, Comparator.comparingLong(trace -> trace.nextFireTime));
            if (completed.size() > warmupSamples) {
                return new ArrayList<Trace>(completed.subList(warmupSamples, completed.size()));
            }
            return Collections.emptyList();
        }

        private Trace trace(JobExecution execution) {
            return traces.computeIfAbsent(execution.getExecutionId(),
                    key -> new Trace(execution.getExecutionId(), execution.getNextFireTime()));
        }
    }

    private static final class Trace {
        private final String executionId;
        private final long nextFireTime;
        private final List<ScheduleAttempt> attempts = new ArrayList<ScheduleAttempt>();

        private volatile long pushNanoTime;
        private volatile long pushSystemMs;
        private volatile long dispatchNanoTime;
        private volatile long dispatchSystemMs;
        private volatile long receiveNanoTime;
        private volatile long receiveSystemMs;
        private volatile long receiveContextMs;
        private volatile long receiveProviderMs;
        private volatile long receiveOffsetMs;
        private volatile long initialDelayMs;
        private volatile long dueNanoTime;
        private volatile long dueContextMs;
        private volatile long executeNanoTime;
        private volatile long executeSystemMs;
        private volatile long executeContextMs;
        private volatile long executeProviderMs;
        private volatile long executeOffsetMs;
        private volatile int rescheduleCount;

        private Trace(String executionId, long nextFireTime) {
            this.executionId = executionId;
            this.nextFireTime = nextFireTime;
        }

        private synchronized void recordPush(long nanoTime, long systemMs) {
            this.pushNanoTime = nanoTime;
            this.pushSystemMs = systemMs;
        }

        private synchronized void recordDispatch(long nanoTime, long systemMs) {
            this.dispatchNanoTime = nanoTime;
            this.dispatchSystemMs = systemMs;
        }

        private synchronized void recordReceive(long nanoTime, long systemMs, long contextMs, long providerMs, long offsetMs, long delayMs) {
            this.receiveNanoTime = nanoTime;
            this.receiveSystemMs = systemMs;
            this.receiveContextMs = contextMs;
            this.receiveProviderMs = providerMs;
            this.receiveOffsetMs = offsetMs;
            this.initialDelayMs = delayMs;
        }

        private synchronized void recordSchedule(long nanoTime, long delayMs, boolean immediate) {
            attempts.add(new ScheduleAttempt(nanoTime, delayMs, immediate));
        }

        private synchronized void recordCallback(long nanoTime, long remainingMs) {
            if (attempts.isEmpty()) {
                attempts.add(new ScheduleAttempt(nanoTime, 0L, true));
            }
            attempts.get(attempts.size() - 1).recordCallback(nanoTime, remainingMs);
        }

        private synchronized void recordReschedule(long remainingMs) {
            rescheduleCount++;
        }

        private synchronized void recordDue(long nanoTime, long currentTimeMs) {
            this.dueNanoTime = nanoTime;
            this.dueContextMs = currentTimeMs;
        }

        private synchronized boolean recordExecute(long nanoTime, long systemMs, long contextMs, long providerMs, long offsetMs) {
            if (executeProviderMs > 0L) {
                return false;
            }
            this.executeNanoTime = nanoTime;
            this.executeSystemMs = systemMs;
            this.executeContextMs = contextMs;
            this.executeProviderMs = providerMs;
            this.executeOffsetMs = offsetMs;
            return true;
        }

        private synchronized boolean isComplete() {
            return pushNanoTime > 0L
                    && dispatchNanoTime > 0L
                    && receiveNanoTime > 0L
                    && dueNanoTime > 0L
                    && executeNanoTime > 0L
                    && !attempts.isEmpty()
                    && attempts.get(attempts.size() - 1).callbackNanoTime > 0L;
        }

        private synchronized ScheduleAttempt firstAttempt() {
            return attempts.get(0);
        }

        private synchronized ScheduleAttempt lastAttempt() {
            return attempts.get(attempts.size() - 1);
        }
    }

    private static final class ScheduleAttempt {
        private final long submitNanoTime;
        private final long requestedDelayMs;
        private final boolean immediate;
        private volatile long callbackNanoTime;
        private volatile long callbackRemainingMs;

        private ScheduleAttempt(long submitNanoTime, long requestedDelayMs, boolean immediate) {
            this.submitNanoTime = submitNanoTime;
            this.requestedDelayMs = requestedDelayMs;
            this.immediate = immediate;
        }

        private void recordCallback(long callbackNanoTime, long callbackRemainingMs) {
            this.callbackNanoTime = callbackNanoTime;
            this.callbackRemainingMs = callbackRemainingMs;
        }
    }

    private static final class RoundStudy {
        private final int round;
        private final int sampleCount;
        private final double averageAbsoluteSkewMs;
        private final double averageQueueMs;
        private final double averagePushLeadMs;
        private final double averageDispatchToReceiveMs;
        private final double averageReceiveToSubmitMs;
        private final double averageFinalWaitOverrunMs;
        private final double averageCallbackToDueMs;
        private final double averageDueToExecuteMs;
        private final double averageReschedules;
        private final double averagePositiveRemainingMs;
        private final double averageReceiveContextProviderSkewMs;
        private final double averageExecuteContextProviderSkewMs;
        private final List<Trace> traces;

        private RoundStudy(int round, int sampleCount, double averageAbsoluteSkewMs, double averageQueueMs,
                           double averagePushLeadMs,
                           double averageDispatchToReceiveMs, double averageReceiveToSubmitMs,
                           double averageFinalWaitOverrunMs, double averageCallbackToDueMs, double averageDueToExecuteMs,
                           double averageReschedules, double averagePositiveRemainingMs,
                           double averageReceiveContextProviderSkewMs, double averageExecuteContextProviderSkewMs,
                           List<Trace> traces) {
            this.round = round;
            this.sampleCount = sampleCount;
            this.averageAbsoluteSkewMs = averageAbsoluteSkewMs;
            this.averageQueueMs = averageQueueMs;
            this.averagePushLeadMs = averagePushLeadMs;
            this.averageDispatchToReceiveMs = averageDispatchToReceiveMs;
            this.averageReceiveToSubmitMs = averageReceiveToSubmitMs;
            this.averageFinalWaitOverrunMs = averageFinalWaitOverrunMs;
            this.averageCallbackToDueMs = averageCallbackToDueMs;
            this.averageDueToExecuteMs = averageDueToExecuteMs;
            this.averageReschedules = averageReschedules;
            this.averagePositiveRemainingMs = averagePositiveRemainingMs;
            this.averageReceiveContextProviderSkewMs = averageReceiveContextProviderSkewMs;
            this.averageExecuteContextProviderSkewMs = averageExecuteContextProviderSkewMs;
            this.traces = traces;
        }

        private static RoundStudy from(int round, List<Trace> traces) {
            if (traces.isEmpty()) {
                return new RoundStudy(round, 0, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, traces);
            }
            double absoluteSkew = 0.0D;
            double queue = 0.0D;
            double pushLead = 0.0D;
            double dispatchToReceive = 0.0D;
            double receiveToSubmit = 0.0D;
            double finalWaitOverrun = 0.0D;
            double callbackToDue = 0.0D;
            double dueToExecute = 0.0D;
            double reschedules = 0.0D;
            double positiveRemaining = 0.0D;
            int positiveRemainingCount = 0;
            double receiveContextProvider = 0.0D;
            double executeContextProvider = 0.0D;

            for (Trace trace : traces) {
                ScheduleAttempt first = trace.firstAttempt();
                ScheduleAttempt last = trace.lastAttempt();
                absoluteSkew += Math.abs(trace.executeProviderMs - trace.nextFireTime);
                queue += nanosToMillis(trace.receiveNanoTime - trace.pushNanoTime);
                pushLead += trace.nextFireTime - trace.pushSystemMs;
                dispatchToReceive += nanosToMillis(trace.receiveNanoTime - trace.dispatchNanoTime);
                receiveToSubmit += nanosToMillis(first.submitNanoTime - trace.receiveNanoTime);
                finalWaitOverrun += nanosToMillis(last.callbackNanoTime - last.submitNanoTime) - last.requestedDelayMs;
                callbackToDue += nanosToMillis(trace.dueNanoTime - last.callbackNanoTime);
                dueToExecute += nanosToMillis(trace.executeNanoTime - trace.dueNanoTime);
                reschedules += trace.rescheduleCount;
                receiveContextProvider += trace.receiveContextMs - trace.receiveProviderMs;
                executeContextProvider += trace.executeContextMs - trace.executeProviderMs;
                for (ScheduleAttempt attempt : trace.attempts) {
                    if (attempt.callbackRemainingMs > 0L) {
                        positiveRemaining += attempt.callbackRemainingMs;
                        positiveRemainingCount++;
                    }
                }
            }

            int count = traces.size();
            return new RoundStudy(
                    round,
                    count,
                    absoluteSkew / count,
                    queue / count,
                    pushLead / count,
                    dispatchToReceive / count,
                    receiveToSubmit / count,
                    finalWaitOverrun / count,
                    callbackToDue / count,
                    dueToExecute / count,
                    reschedules / count,
                    positiveRemainingCount == 0 ? 0.0D : positiveRemaining / positiveRemainingCount,
                    receiveContextProvider / count,
                    executeContextProvider / count,
                    traces
            );
        }
    }

    private static final class ScenarioStudy {
        private final ScenarioDefinition scenario;
        private final List<RoundStudy> rounds;
        private final double averageRoundAbsSkewMs;
        private final double p95RoundAbsSkewMs;
        private final double averageQueueMs;
        private final double averagePushLeadMs;
        private final double averageFinalWaitOverrunMs;
        private final double averageReschedules;
        private final double averagePositiveRemainingMs;
        private final double averageDueToExecuteMs;
        private final List<Trace> representativeSamples;

        private ScenarioStudy(ScenarioDefinition scenario, List<RoundStudy> rounds, double averageRoundAbsSkewMs,
                              double p95RoundAbsSkewMs, double averageQueueMs, double averagePushLeadMs, double averageFinalWaitOverrunMs,
                              double averageReschedules, double averagePositiveRemainingMs,
                              double averageDueToExecuteMs, List<Trace> representativeSamples) {
            this.scenario = scenario;
            this.rounds = rounds;
            this.averageRoundAbsSkewMs = averageRoundAbsSkewMs;
            this.p95RoundAbsSkewMs = p95RoundAbsSkewMs;
            this.averageQueueMs = averageQueueMs;
            this.averagePushLeadMs = averagePushLeadMs;
            this.averageFinalWaitOverrunMs = averageFinalWaitOverrunMs;
            this.averageReschedules = averageReschedules;
            this.averagePositiveRemainingMs = averagePositiveRemainingMs;
            this.averageDueToExecuteMs = averageDueToExecuteMs;
            this.representativeSamples = representativeSamples;
        }

        private static ScenarioStudy from(ScenarioDefinition scenario, List<RoundStudy> rounds, List<Trace> representativeSamples) {
            List<Double> roundSkews = new ArrayList<Double>(rounds.size());
            double queue = 0.0D;
            double pushLead = 0.0D;
            double finalWaitOverrun = 0.0D;
            double reschedules = 0.0D;
            double positiveRemaining = 0.0D;
            double dueToExecute = 0.0D;
            for (RoundStudy round : rounds) {
                roundSkews.add(round.averageAbsoluteSkewMs);
                queue += round.averageQueueMs;
                pushLead += round.averagePushLeadMs;
                finalWaitOverrun += round.averageFinalWaitOverrunMs;
                reschedules += round.averageReschedules;
                positiveRemaining += round.averagePositiveRemainingMs;
                dueToExecute += round.averageDueToExecuteMs;
            }
            int count = Math.max(1, rounds.size());
            return new ScenarioStudy(
                    scenario,
                    rounds,
                    average(roundSkews),
                    percentile(roundSkews, 95),
                    queue / count,
                    pushLead / count,
                    finalWaitOverrun / count,
                    reschedules / count,
                    positiveRemaining / count,
                    dueToExecute / count,
                    representativeSamples
            );
        }
    }

    private static final class StudyReport {
        private final List<ScenarioStudy> studies;

        private StudyReport(List<ScenarioStudy> studies) {
            this.studies = studies;
        }

        private String toCsv() {
            StringBuilder builder = new StringBuilder();
            builder.append("scenario,round,avg_abs_skew_ms,avg_queue_ms,avg_push_lead_ms,avg_dispatch_to_receive_ms,avg_receive_to_submit_ms,avg_final_wait_overrun_ms,avg_callback_to_due_ms,avg_due_to_execute_ms,avg_reschedules,avg_positive_remaining_ms,avg_receive_context_provider_skew_ms,avg_execute_context_provider_skew_ms\n");
            for (ScenarioStudy study : studies) {
                for (RoundStudy round : study.rounds) {
                    builder.append(study.scenario.label).append(',')
                            .append(round.round).append(',')
                            .append(format(round.averageAbsoluteSkewMs)).append(',')
                            .append(format(round.averageQueueMs)).append(',')
                            .append(format(round.averagePushLeadMs)).append(',')
                            .append(format(round.averageDispatchToReceiveMs)).append(',')
                            .append(format(round.averageReceiveToSubmitMs)).append(',')
                            .append(format(round.averageFinalWaitOverrunMs)).append(',')
                            .append(format(round.averageCallbackToDueMs)).append(',')
                            .append(format(round.averageDueToExecuteMs)).append(',')
                            .append(format(round.averageReschedules)).append(',')
                            .append(format(round.averagePositiveRemainingMs)).append(',')
                            .append(format(round.averageReceiveContextProviderSkewMs)).append(',')
                            .append(format(round.averageExecuteContextProviderSkewMs)).append('\n');
                }
            }
            return builder.toString();
        }

        private String toConsoleSummary() {
            StringBuilder builder = new StringBuilder();
            builder.append("=== Redis precision study (round avg abs skew / final wait overrun / reschedules) ===\n");
            for (ScenarioStudy study : studies) {
                builder.append(study.scenario.label).append(": ")
                        .append(format(study.averageRoundAbsSkewMs)).append(" / ")
                        .append(format(study.averageFinalWaitOverrunMs)).append(" / ")
                        .append(format(study.averageReschedules)).append('\n');
            }
            return builder.toString();
        }

        private String toTraceCsv() {
            StringBuilder builder = new StringBuilder();
            builder.append("scenario,round,trace_index,fire_time_ms,push_time_ms,receive_system_ms,receive_context_ms,receive_provider_ms,receive_offset_ms,due_context_ms,execute_system_ms,execute_context_ms,execute_provider_ms,execute_offset_ms,execute_system_skew_ms,execute_context_skew_ms,execute_provider_skew_ms,context_minus_provider_ms,provider_minus_system_ms,push_lead_ms,first_delay_ms,final_wait_overrun_ms,reschedules,queue_ms,receive_to_submit_ms,callback_to_due_ms,due_to_execute_ms\n");
            for (ScenarioStudy study : studies) {
                for (RoundStudy round : study.rounds) {
                    for (int i = 0; i < round.traces.size(); i++) {
                        Trace trace = round.traces.get(i);
                        ScheduleAttempt first = trace.firstAttempt();
                        ScheduleAttempt last = trace.lastAttempt();
                        builder.append(study.scenario.label).append(',')
                                .append(round.round).append(',')
                                .append(i + 1).append(',')
                                .append(trace.nextFireTime).append(',')
                                .append(trace.pushSystemMs).append(',')
                                .append(trace.receiveSystemMs).append(',')
                                .append(trace.receiveContextMs).append(',')
                                .append(trace.receiveProviderMs).append(',')
                                .append(trace.receiveOffsetMs).append(',')
                                .append(trace.dueContextMs).append(',')
                                .append(trace.executeSystemMs).append(',')
                                .append(trace.executeContextMs).append(',')
                                .append(trace.executeProviderMs).append(',')
                                .append(trace.executeOffsetMs).append(',')
                                .append(trace.executeSystemMs - trace.nextFireTime).append(',')
                                .append(trace.executeContextMs - trace.nextFireTime).append(',')
                                .append(trace.executeProviderMs - trace.nextFireTime).append(',')
                                .append(trace.executeContextMs - trace.executeProviderMs).append(',')
                                .append(trace.executeProviderMs - trace.executeSystemMs).append(',')
                                .append(trace.nextFireTime - trace.pushSystemMs).append(',')
                                .append(first.requestedDelayMs).append(',')
                                .append(format(nanosToMillis(last.callbackNanoTime - last.submitNanoTime) - last.requestedDelayMs)).append(',')
                                .append(trace.rescheduleCount).append(',')
                                .append(format(nanosToMillis(trace.receiveNanoTime - trace.pushNanoTime))).append(',')
                                .append(format(nanosToMillis(first.submitNanoTime - trace.receiveNanoTime))).append(',')
                                .append(format(nanosToMillis(trace.dueNanoTime - last.callbackNanoTime))).append(',')
                                .append(format(nanosToMillis(trace.executeNanoTime - trace.dueNanoTime))).append('\n');
                    }
                }
            }
            return builder.toString();
        }

        private String toMarkdown() {
            StringBuilder builder = new StringBuilder();
            builder.append("# Redis high-precision precision study\n\n");
            builder.append("## Method\n\n");
            builder.append("- Whole-second cron: `*/1 * * * * ?`\n");
            builder.append("- Each round keeps ").append(WARMUP_SAMPLES).append(" warmup samples and records ").append(MEASURED_SAMPLES).append(" measured samples\n");
            builder.append("- Redis scenarios run for 10 rounds; memory reference scenarios run for 4 rounds\n");
            builder.append("- Segments recorded: push -> dispatch -> worker receive -> schedule submit -> scheduler callback -> due check -> business execute\n");
            builder.append("- High-precision variants compare default 200us advance, larger advance, and a no-spin variant\n\n");

            builder.append("## Aggregate comparison\n\n");
            builder.append("| Scenario | rounds | avg round abs skew (ms) | p95 round abs skew | avg queue | avg push lead | avg final wait overrun | avg reschedules | avg positive remaining | avg due->execute |\n");
            builder.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
            for (ScenarioStudy study : studies) {
                builder.append("| ").append(study.scenario.label)
                        .append(" | ").append(study.rounds.size())
                        .append(" | ").append(format(study.averageRoundAbsSkewMs))
                        .append(" | ").append(format(study.p95RoundAbsSkewMs))
                        .append(" | ").append(format(study.averageQueueMs))
                        .append(" | ").append(format(study.averagePushLeadMs))
                        .append(" | ").append(format(study.averageFinalWaitOverrunMs))
                        .append(" | ").append(format(study.averageReschedules))
                        .append(" | ").append(format(study.averagePositiveRemainingMs))
                        .append(" | ").append(format(study.averageDueToExecuteMs))
                        .append(" |\n");
            }

            builder.append("\n### Avg round abs skew chart\n\n```text\n");
            appendBarChart(builder);
            builder.append("```\n");

            builder.append("\n## Round-by-round Redis comparison\n\n");
            builder.append("| Scenario | Round | avg abs skew | queue | push lead | receive->submit | final wait overrun | callback->due | due->execute | reschedules | positive remaining |\n");
            builder.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
            for (ScenarioStudy study : studies) {
                for (RoundStudy round : study.rounds) {
                    builder.append("| ").append(study.scenario.label)
                            .append(" | ").append(round.round)
                            .append(" | ").append(format(round.averageAbsoluteSkewMs))
                            .append(" | ").append(format(round.averageQueueMs))
                            .append(" | ").append(format(round.averagePushLeadMs))
                            .append(" | ").append(format(round.averageReceiveToSubmitMs))
                            .append(" | ").append(format(round.averageFinalWaitOverrunMs))
                            .append(" | ").append(format(round.averageCallbackToDueMs))
                            .append(" | ").append(format(round.averageDueToExecuteMs))
                            .append(" | ").append(format(round.averageReschedules))
                            .append(" | ").append(format(round.averagePositiveRemainingMs))
                            .append(" |\n");
                }
            }

            builder.append("\n## Representative samples\n\n");
            for (ScenarioStudy study : studies) {
                builder.append("### ").append(study.scenario.label).append('\n').append('\n');
                builder.append("| fire time | push time | execute provider time | abs skew | push lead | first delay | final overrun | reschedules |\n");
                builder.append("| --- | --- | --- | ---: | ---: | ---: | ---: | ---: |\n");
                int limit = Math.min(5, study.representativeSamples.size());
                for (int i = 0; i < limit; i++) {
                    Trace trace = study.representativeSamples.get(i);
                    ScheduleAttempt first = trace.firstAttempt();
                    ScheduleAttempt last = trace.lastAttempt();
                    builder.append("| ").append(formatEpoch(trace.nextFireTime))
                            .append(" | ").append(formatEpoch(trace.pushSystemMs))
                            .append(" | ").append(formatEpoch(trace.executeProviderMs))
                            .append(" | ").append(Math.abs(trace.executeProviderMs - trace.nextFireTime))
                            .append(" | ").append(trace.nextFireTime - trace.pushSystemMs)
                            .append(" | ").append(first.requestedDelayMs)
                            .append(" | ").append(format(nanosToMillis(last.callbackNanoTime - last.submitNanoTime) - last.requestedDelayMs))
                            .append(" | ").append(trace.rescheduleCount)
                            .append(" |\n");
                }
                builder.append('\n');
            }

            builder.append("## Notes\n\n");
            builder.append("- `avg push lead` 表示 Master 在 `fireTime` 之前多久把任务 push 出去；正值表示提前，负值表示已经晚点推送。\n");
            builder.append("- `avg final wait overrun` directly measures how much later the scheduler callback fired than the requested delay.\n");
            builder.append("- `avg positive remaining` shows how often the callback woke up while the Redis-synchronized clock still said \"not yet due\".\n");
            builder.append("- `avg due->execute` isolates the time from passing the final due check to entering the business callback.\n");
            return builder.toString();
        }

        private void appendBarChart(StringBuilder builder) {
            double max = 0.0D;
            for (ScenarioStudy study : studies) {
                max = Math.max(max, study.averageRoundAbsSkewMs);
            }
            if (max <= 0.0D) {
                max = 1.0D;
            }
            for (ScenarioStudy study : studies) {
                int width = Math.max(1, (int) Math.round((study.averageRoundAbsSkewMs / max) * 24.0D));
                builder.append(padRight(study.scenario.label, 32))
                        .append(" | ")
                        .append(repeat('█', width))
                        .append(' ')
                        .append(format(study.averageRoundAbsSkewMs))
                        .append(" ms\n");
            }
        }
    }

    private static final class TunableHighPrecisionWheelTaskScheduler extends HighPrecisionWheelTaskScheduler {
        private final boolean disableSpin;

        private TunableHighPrecisionWheelTaskScheduler(int poolSize, String threadNamePrefix, boolean disableSpin) {
            super(poolSize, threadNamePrefix);
            this.disableSpin = disableSpin;
        }

        @Override
        protected void onSpinWait(long deadlineNanos) {
            if (!disableSpin) {
                super.onSpinWait(deadlineNanos);
                return;
            }
            long remaining;
            while ((remaining = deadlineNanos - System.nanoTime()) > 0L) {
                LockSupport.parkNanos(Math.min(remaining, 100_000L));
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
            }
        }
    }

    private static double average(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0D;
        }
        double total = 0.0D;
        for (Double value : values) {
            total += value;
        }
        return total / values.size();
    }

    private static double percentile(List<Double> values, int percentile) {
        if (values.isEmpty()) {
            return 0.0D;
        }
        List<Double> sorted = new ArrayList<Double>(values);
        Collections.sort(sorted);
        int index = (int) Math.ceil((percentile / 100.0D) * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0D;
    }

    private static String format(double value) {
        return DECIMAL.format(value);
    }

    private static String formatEpoch(long epochMs) {
        return TIME_FORMATTER.format(Instant.ofEpochMilli(epochMs)) + " (" + epochMs + ")";
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

    private static void ensureRedisAvailable(JedisPool jedisPool) {
        try (Jedis jedis = jedisPool.getResource()) {
            if (!"PONG".equalsIgnoreCase(jedis.ping())) {
                throw new IllegalStateException("Redis 127.0.0.1:6379 is not available");
            }
        }
    }

    private static void cleanupRedisNamespace(JedisPool jedisPool, String namespacePrefix) {
        try (Jedis jedis = jedisPool.getResource()) {
            Set<String> keys = jedis.keys(namespacePrefix + "*");
            if (keys != null && !keys.isEmpty()) {
                jedis.del(keys.toArray(new String[0]));
            }
        }
    }

    private static void resetTockForTest() {
        try {
            java.lang.reflect.Method method = Tock.class.getDeclaredMethod("resetForTest");
            method.setAccessible(true);
            method.invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to reset Tock singleton", e);
        }
    }
}
