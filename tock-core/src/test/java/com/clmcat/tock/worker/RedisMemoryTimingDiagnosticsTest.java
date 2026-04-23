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
import com.clmcat.tock.worker.memory.MemorySubscribableWorkerQueue;
import com.clmcat.tock.worker.redis.RedisSubscribableWorkerQueue;
import com.clmcat.tock.worker.scheduler.ScheduledExecutorTaskScheduler;
import com.clmcat.tock.worker.scheduler.TaskScheduler;
import com.clmcat.tock.worker.scheduler.TaskSchedulers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

class RedisMemoryTimingDiagnosticsTest {

    private static final int EXECUTION_COUNT = 6;
    private static final long TIMEOUT_SECONDS = 15L;

    @Test
    void shouldKeepRedisHighPrecisionCloseToMemoryPrecision() {
        ScenarioSummary memory = runMemoryScenario("memory-high-precision", TaskSchedulers.highPrecision("diag-memory-worker"));

        try (JedisPool jedisPool = new JedisPool("127.0.0.1", 6379)) {
            assumeRedisAvailable(jedisPool);
            assumeRedisClockCloseToJvmClock(jedisPool);
            ScenarioSummary redis = runRedisScenario("redis-high-precision", jedisPool, TaskSchedulers.highPrecision("diag-redis-worker"));

            System.out.println(memory.summaryLine());
            System.out.println(redis.summaryLine());

            Assertions.assertTrue(memory.sampleCount() >= 4, "memory benchmark should collect enough stable samples");
            Assertions.assertTrue(redis.sampleCount() >= 4, "redis benchmark should collect enough stable samples");
            Assertions.assertTrue(Math.abs(memory.averageExecuteSkewMs()) <= 2.0D,
                    "memory high-precision worker should stay near the target fire time");
            Assertions.assertTrue(redis.averageQueueMs() <= 200.0D,
                    "Redis queue latency should stay bounded on the local machine");
            Assertions.assertTrue(Math.abs(redis.averageExecuteSkewMs()) <= 150.0D,
                    "Redis high-precision worker should stay within a bounded range of the target fire time");
            Assertions.assertTrue(Math.abs(redis.averageContextProviderSkewMs()) <= 50.0D,
                    "time synchronizer should stay close to Redis TIME during worker execution");
        }
    }

    @Test
    void shouldKeepRedisDefaultWorkerWithinTightBound() {
        try (JedisPool jedisPool = new JedisPool("127.0.0.1", 6379)) {
            assumeRedisAvailable(jedisPool);
            assumeRedisClockCloseToJvmClock(jedisPool);

            ScenarioSummary redisDefault = runRedisScenario("redis-default-worker", jedisPool, new ScheduledExecutorTaskScheduler("diag-redis-default"));
            ScenarioSummary redisHighPrecision = runRedisScenario("redis-high-worker", jedisPool, TaskSchedulers.highPrecision("diag-redis-high"));

            System.out.println(redisDefault.summaryLine());
            System.out.println(redisHighPrecision.summaryLine());

            Assertions.assertTrue(redisDefault.sampleCount() >= 4, "default worker benchmark should collect enough stable samples");
            Assertions.assertTrue(redisHighPrecision.sampleCount() >= 4, "high-precision benchmark should collect enough stable samples");
            Assertions.assertTrue(redisDefault.averageQueueMs() <= 200.0D,
                    "default Redis worker queue latency should stay bounded on the local machine");
            Assertions.assertTrue(redisHighPrecision.averageQueueMs() <= 200.0D,
                    "high-precision Redis worker queue latency should stay bounded on the local machine");
            Assertions.assertTrue(Math.abs(redisDefault.averageExecuteSkewMs()) <= 150.0D,
                    "default Redis worker should stay within a bounded range of the target fire time");
            Assertions.assertTrue(Math.abs(redisHighPrecision.averageExecuteSkewMs()) <= 150.0D,
                    "high-precision Redis worker should stay within a bounded range of the target fire time");
            Assertions.assertTrue(Math.abs(redisHighPrecision.averageContextProviderSkewMs()) <= 50.0D,
                    "high-precision worker should keep synchronized time close to Redis TIME");
        }
    }

    private ScenarioSummary runMemoryScenario(String label, TaskScheduler workerExecutor) {
        String scheduleId = label + "-" + UUID.randomUUID().toString().substring(0, 8);
        MemoryTockRegister register = new MemoryTockRegister(scheduleId, MemoryManager.create());
        RecordingSubscribableWorkerQueue queue = new RecordingSubscribableWorkerQueue(MemorySubscribableWorkerQueue.create(), label);
        ScheduleStore scheduleStore = MemoryScheduleStore.create();
        return runScenario(label, scheduleId, register, scheduleStore, queue, workerExecutor);
    }

    private ScenarioSummary runRedisScenario(String label, JedisPool jedisPool, TaskScheduler workerExecutor) {
        String namespace = "ut:" + label + ":" + UUID.randomUUID().toString().replace("-", "");
        RecordingSubscribableWorkerQueue queue = new RecordingSubscribableWorkerQueue(
                RedisSubscribableWorkerQueue.create(namespace, jedisPool),
                label
        );
        RedisTockRegister register = new RedisTockRegister(namespace, jedisPool);
        ScheduleStore scheduleStore = RedisScheduleStore.create(namespace, jedisPool);
        try {
            return runScenario(label, namespace, register, scheduleStore, queue, workerExecutor);
        } finally {
            cleanupRedisNamespace(jedisPool, "tock:" + namespace);
        }
    }

    private ScenarioSummary runScenario(String label, String scheduleId, TockRegister register, ScheduleStore scheduleStore,
                                        RecordingSubscribableWorkerQueue queue, TaskScheduler workerExecutor) {
        TraceRecorder recorder = new TraceRecorder(label, EXECUTION_COUNT);
        TracingWorker worker = new TracingWorker(recorder);
        queue.attachRecorder(recorder);

        Config config = Config.builder()
                .register(register)
                .scheduleStore(scheduleStore)
                .workerQueue(queue)
                .workerExecutor(workerExecutor)
                .worker(worker)
                .build();

        Tock tock = null;
        try {
            tock = Tock.configure(config);
            tock.registerJob("job-" + label, ctx -> {
                // execution timing is recorded at worker entry; keep the job body empty to minimize noise.
            });

            tock.start();
            tock.joinGroup("group-" + label);
            tock.addSchedule(ScheduleConfig.builder()
                    .scheduleId(scheduleId)
                    .jobId("job-" + label)
                    .workerGroup("group-" + label)
                    .cron("*/1 * * * * ?")
                    .zoneId("Asia/Shanghai")
                    .build());
            tock.refreshSchedules();

            Assertions.assertTrue(recorder.awaitExecutions(TIMEOUT_SECONDS, TimeUnit.SECONDS), label + " did not produce enough executions");
            return recorder.summarize();
        } finally {
            if (tock != null) {
                tock.shutdown();
            }
            resetTockForTest();
        }
    }

    private void assumeRedisAvailable(JedisPool jedisPool) {
        try (Jedis jedis = jedisPool.getResource()) {
            Assumptions.assumeTrue("PONG".equalsIgnoreCase(jedis.ping()), "Redis 127.0.0.1:6379 is not available");
        }
    }

    private void assumeRedisClockCloseToJvmClock(JedisPool jedisPool) {
        List<Long> diffs = new ArrayList<Long>();
        try (Jedis jedis = jedisPool.getResource()) {
            for (int i = 0; i < 5; i++) {
                long before = System.currentTimeMillis();
                List<String> time = jedis.time();
                long after = System.currentTimeMillis();
                long redisMs = Long.parseLong(time.get(0)) * 1000L + Long.parseLong(time.get(1)) / 1000L;
                long midpoint = before + ((after - before) / 2L);
                diffs.add(Math.abs(redisMs - midpoint));
                try {
                    Thread.sleep(20L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while sampling Redis clock", e);
                }
            }
        }
        Collections.sort(diffs);
        long medianDiff = diffs.get(diffs.size() / 2);
        Assumptions.assumeTrue(medianDiff <= 100L,
                "Redis clock is not close to JVM system clock in this environment (median diff=" + medianDiff + "ms)");
    }

    private void cleanupRedisNamespace(JedisPool jedisPool, String namespacePrefix) {
        try (Jedis jedis = jedisPool.getResource()) {
            java.util.Set<String> keys = jedis.keys(namespacePrefix + "*");
            if (keys != null && !keys.isEmpty()) {
                jedis.del(keys.toArray(new String[0]));
            }
        }
    }

    private void resetTockForTest() {
        try {
            java.lang.reflect.Method method = Tock.class.getDeclaredMethod("resetForTest");
            method.setAccessible(true);
            method.invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to reset Tock test singleton", e);
        }
    }

    private static final class RecordingSubscribableWorkerQueue implements SubscribableWorkerQueue, TockContextAware, com.clmcat.tock.Lifecycle {
        private final WorkerQueue delegate;
        private final String label;
        private TraceRecorder recorder;

        private RecordingSubscribableWorkerQueue(WorkerQueue delegate, String label) {
            this.delegate = delegate;
            this.label = label;
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
                recorder.onDispatch(execution, System.nanoTime(), System.currentTimeMillis(), label);
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

    private static final class TracingWorker extends DefaultTockWorker {
        private final TraceRecorder recorder;
        private TockContext context;

        private TracingWorker(TraceRecorder recorder) {
            this.recorder = recorder;
        }

        @Override
        public synchronized void start(TockContext context) {
            this.context = context;
            super.start(context);
        }

        @Override
        void executeJob(JobExecution jobExecution) {
            long receiveContextMs = context.currentTimeMillis();
            long receiveProviderMs = context.getTimeProvider().currentTimeMillis();
            recorder.onWorkerReceive(jobExecution, System.nanoTime(), System.currentTimeMillis(), receiveContextMs, receiveProviderMs);
            super.executeJob(jobExecution);
        }

        @Override
        void doExecuteJob(JobExecution jobExecution) {
            long executeContextMs = context.currentTimeMillis();
            long executeProviderMs = context.getTimeProvider().currentTimeMillis();
            recorder.onWorkerExecute(jobExecution, System.nanoTime(), System.currentTimeMillis(), executeContextMs, executeProviderMs);
            super.doExecuteJob(jobExecution);
        }
    }

    private static final class TraceRecorder {
        private final String label;
        private final CountDownLatch latch;
        private final Map<String, Trace> traces = new ConcurrentHashMap<>();

        private TraceRecorder(String label, int expectedExecutions) {
            this.label = label;
            this.latch = new CountDownLatch(expectedExecutions);
        }

        private void onPush(JobExecution execution, long pushNanoTime, long pushSystemMs) {
            trace(execution).recordPush(pushNanoTime, pushSystemMs);
        }

        private void onDispatch(JobExecution execution, long dispatchNanoTime, long dispatchSystemMs, String source) {
            trace(execution).recordDispatch(dispatchNanoTime, dispatchSystemMs, source);
        }

        private void onWorkerReceive(JobExecution execution, long receiveNanoTime, long receiveSystemMs,
                                     long receiveContextMs, long receiveProviderMs) {
            trace(execution).recordReceive(receiveNanoTime, receiveSystemMs, receiveContextMs, receiveProviderMs);
        }

        private void onWorkerExecute(JobExecution execution, long executeNanoTime, long executeSystemMs,
                                     long executeContextMs, long executeProviderMs) {
            if (trace(execution).recordExecute(executeNanoTime, executeSystemMs, executeContextMs, executeProviderMs)) {
                latch.countDown();
            }
        }

        private boolean awaitExecutions(long timeout, TimeUnit unit) {
            try {
                return latch.await(timeout, unit);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private ScenarioSummary summarize() {
            List<Trace> executed = new ArrayList<>();
            for (Trace trace : traces.values()) {
                if (trace.executeProviderMs > 0L && trace.receiveNanoTime > 0L && trace.pushNanoTime > 0L) {
                    executed.add(trace);
                }
            }
            executed.sort(Comparator.comparingLong(t -> t.nextFireTime));
            if (executed.size() > 1) {
                executed = new ArrayList<>(executed.subList(1, executed.size()));
            }
            return ScenarioSummary.from(label, executed);
        }

        private Trace trace(JobExecution execution) {
            return traces.computeIfAbsent(execution.getExecutionId(),
                    key -> new Trace(execution.getExecutionId(), execution.getNextFireTime(), execution.getScheduleId(), execution.getWorkerGroup()));
        }
    }

    private static final class Trace {
        private final String executionId;
        private final long nextFireTime;
        private final String scheduleId;
        private final String workerGroup;

        private volatile long pushNanoTime;
        private volatile long pushSystemMs;
        private volatile long dispatchNanoTime;
        private volatile long dispatchSystemMs;
        private volatile long receiveNanoTime;
        private volatile long receiveSystemMs;
        private volatile long receiveContextMs;
        private volatile long receiveProviderMs;
        private volatile long executeNanoTime;
        private volatile long executeSystemMs;
        private volatile long executeContextMs;
        private volatile long executeProviderMs;
        private volatile String dispatchSource;

        private Trace(String executionId, long nextFireTime, String scheduleId, String workerGroup) {
            this.executionId = executionId;
            this.nextFireTime = nextFireTime;
            this.scheduleId = scheduleId;
            this.workerGroup = workerGroup;
        }

        private void recordPush(long pushNanoTime, long pushSystemMs) {
            this.pushNanoTime = pushNanoTime;
            this.pushSystemMs = pushSystemMs;
        }

        private void recordDispatch(long dispatchNanoTime, long dispatchSystemMs, String dispatchSource) {
            this.dispatchNanoTime = dispatchNanoTime;
            this.dispatchSystemMs = dispatchSystemMs;
            this.dispatchSource = dispatchSource;
        }

        private void recordReceive(long receiveNanoTime, long receiveSystemMs, long receiveContextMs, long receiveProviderMs) {
            this.receiveNanoTime = receiveNanoTime;
            this.receiveSystemMs = receiveSystemMs;
            this.receiveContextMs = receiveContextMs;
            this.receiveProviderMs = receiveProviderMs;
        }

        private synchronized boolean recordExecute(long executeNanoTime, long executeSystemMs, long executeContextMs, long executeProviderMs) {
            if (this.executeProviderMs > 0L) {
                return false;
            }
            this.executeNanoTime = executeNanoTime;
            this.executeSystemMs = executeSystemMs;
            this.executeContextMs = executeContextMs;
            this.executeProviderMs = executeProviderMs;
            return true;
        }
    }

    private static final class ScenarioSummary {
        private final String label;
        private final int sampleCount;
        private final double averageQueueMs;
        private final double averageReceiveToExecuteMs;
        private final double averageExecuteSkewMs;
        private final double averageContextProviderSkewMs;

        private ScenarioSummary(String label, int sampleCount, double averageQueueMs, double averageReceiveToExecuteMs,
                                double averageExecuteSkewMs, double averageContextProviderSkewMs) {
            this.label = label;
            this.sampleCount = sampleCount;
            this.averageQueueMs = averageQueueMs;
            this.averageReceiveToExecuteMs = averageReceiveToExecuteMs;
            this.averageExecuteSkewMs = averageExecuteSkewMs;
            this.averageContextProviderSkewMs = averageContextProviderSkewMs;
        }

        private static ScenarioSummary from(String label, List<Trace> traces) {
            double queueMs = 0.0D;
            double receiveToExecuteMs = 0.0D;
            double executeSkewMs = 0.0D;
            double contextProviderSkewMs = 0.0D;

            for (Trace trace : traces) {
                queueMs += nanosToMillis(trace.receiveNanoTime - trace.pushNanoTime);
                receiveToExecuteMs += nanosToMillis(trace.executeNanoTime - trace.receiveNanoTime);
                executeSkewMs += trace.executeProviderMs - trace.nextFireTime;
                contextProviderSkewMs += trace.executeContextMs - trace.executeProviderMs;
            }

            int sampleCount = traces.size();
            if (sampleCount == 0) {
                return new ScenarioSummary(label, 0, 0.0D, 0.0D, 0.0D, 0.0D);
            }
            return new ScenarioSummary(
                    label,
                    sampleCount,
                    queueMs / sampleCount,
                    receiveToExecuteMs / sampleCount,
                    executeSkewMs / sampleCount,
                    contextProviderSkewMs / sampleCount
            );
        }

        private static double nanosToMillis(long nanos) {
            return nanos / 1_000_000.0D;
        }

        private int sampleCount() {
            return sampleCount;
        }

        private double averageExecuteSkewMs() {
            return averageExecuteSkewMs;
        }

        private double averageContextProviderSkewMs() {
            return averageContextProviderSkewMs;
        }

        private double averageQueueMs() {
            return averageQueueMs;
        }

        private String summaryLine() {
            return String.format(
                    "%s samples=%d avgQueue=%.2fms avgReceiveToExecute=%.2fms avgExecuteSkew=%.2fms avgContextProviderSkew=%.2fms",
                    label, sampleCount, averageQueueMs, averageReceiveToExecuteMs, averageExecuteSkewMs, averageContextProviderSkewMs
            );
        }
    }
}
