package com.clmcat.tock;

import com.clmcat.tock.registry.TockRegister;
import com.clmcat.tock.schedule.ScheduleStore;
import com.clmcat.tock.store.JobStore;
import com.clmcat.tock.time.DefaultTimeSynchronizer;
import com.clmcat.tock.time.SystemTimeProvider;
import com.clmcat.tock.time.TimeProvider;
import com.clmcat.tock.time.TimeSynchronizer;
import com.clmcat.tock.worker.WorkerQueue;
import com.clmcat.tock.worker.scheduler.ScheduledExecutorTaskScheduler;
import com.clmcat.tock.worker.scheduler.TaskScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.function.BooleanSupplier;

/**
 * Redis 集成测试的公共支架。
 * <p>
 * 每个测试都会使用独立的 namespace，并在结束后清理自身生成的 key。
 * 这样可以在本机复用 Redis，且不会相互污染。
 * </p>
 */
public abstract class RedisTestSupport {

    protected JedisPool jedisPool;
    protected String namespace;
    protected ExecutorService consumerExecutor;
    protected TaskScheduler workerExecutor;
    protected ScheduledExecutorService schedulerExecutor;

    @BeforeEach
    void setUp(TestInfo testInfo) {
        this.jedisPool = new JedisPool("127.0.0.1", 6379);
        Assumptions.assumeTrue(isRedisAvailable(), "Redis 127.0.0.1:6379 is not available");
        this.namespace = "ut:" + getClass().getSimpleName() + ":" + sanitize(testInfo.getDisplayName()) + ":" + UUID.randomUUID().toString().replace("-", "");
        this.consumerExecutor = Executors.newCachedThreadPool(namedDaemonFactory("tock-test-consumer"));
        this.workerExecutor = new ScheduledExecutorTaskScheduler(1, "tock-test-worker");
        this.schedulerExecutor = Executors.newScheduledThreadPool(2, namedDaemonFactory("tock-test-scheduler"));
    }

    @AfterEach
    void tearDown() {
        if (jedisPool == null || namespace == null) return;
        try (Jedis jedis = jedisPool.getResource()) {
            Set<String> keys = jedis.keys(namespace + "*");
            if (keys != null && !keys.isEmpty()) {
                jedis.del(keys.toArray(new String[0]));
            }
        } finally {
            jedisPool.close();
        }
        if (consumerExecutor != null) consumerExecutor.shutdownNow();
        if (workerExecutor != null) workerExecutor.stop();
        if (schedulerExecutor != null) schedulerExecutor.shutdownNow();
        Tock.resetForTest();
    }

    protected TockContext buildContext(TockRegister register, WorkerQueue workerQueue, ScheduleStore scheduleStore, JobStore jobStore) {
        TimeSynchronizer timeSource;
        if (register instanceof TimeProvider) {
            timeSource = new DefaultTimeSynchronizer((TimeProvider) register, 100L, 3);
        } else {
            timeSource = new DefaultTimeSynchronizer(new SystemTimeProvider(), 100L, 3);
        }
        return TockContext.builder()
                .register(register)
                .scheduleStore(scheduleStore)
                .jobStore(jobStore)
                .workerQueue(workerQueue)
                .consumerExecutor(consumerExecutor)
                .workerExecutor(workerExecutor)
                .timeSource(timeSource)
                .build();
    }

    protected void await(BooleanSupplier condition, long timeoutMs, String message) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(20L);
        }
        throw new AssertionError(message);
    }

    protected void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting in Redis test", e);
        }
    }

    private boolean isRedisAvailable() {
        try (Jedis jedis = jedisPool.getResource()) {
            return "PONG".equalsIgnoreCase(jedis.ping());
        } catch (Exception e) {
            return false;
        }
    }

    private String sanitize(String value) {
        return value == null ? "test" : value.replaceAll("[^a-zA-Z0-9]+", "_");
    }

    private java.util.concurrent.ThreadFactory namedDaemonFactory(String prefix) {
        return r -> {
            Thread thread = new Thread(r, prefix + "-" + UUID.randomUUID().toString().substring(0, 8));
            thread.setDaemon(true);
            return thread;
        };
    }
}
