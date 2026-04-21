package com.clmcat.tock;

import com.clmcat.tock.job.JobContext;
import com.clmcat.tock.money.MemoryManager;
import com.clmcat.tock.registry.redis.RedisTockRegister;
import com.clmcat.tock.schedule.ScheduleConfig;
import com.clmcat.tock.schedule.redis.RedisScheduleStore;
import com.clmcat.tock.store.MemoryJobStore;
import com.clmcat.tock.worker.redis.RedisSubscribableWorkerQueue;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

public class RedisRedisIntegrationTest extends RedisTestSupport {

    @Test
    void shouldRunRedisBackedScheduleAndWorkerEndToEnd() {
        RedisTockRegister register = new RedisTockRegister(namespace, jedisPool, null, 3000L, 100L);
        RedisScheduleStore scheduleStore = new RedisScheduleStore(namespace, jedisPool);
        RedisSubscribableWorkerQueue workerQueue = new RedisSubscribableWorkerQueue(namespace, jedisPool);

        Config config = Config.builder()
                .register(register)
                .scheduleStore(scheduleStore)
                .jobStore(MemoryJobStore.create())
                .workerQueue(workerQueue)
                .build();

        AtomicInteger executions = new AtomicInteger();
        try {
            Tock.configure(config).start();
            Tock.get().registerJob("redis-job", jobContext -> executions.incrementAndGet());
            Tock.get().addSchedule(ScheduleConfig.builder()
                    .scheduleId("redis-schedule")
                    .jobId("redis-job")
                    .fixedDelayMs(100L)
                    .workerGroup("default")
                    .zoneId("UTC")
                    .build());
            Tock.get().refreshSchedules();
            Tock.get().joinGroup("default");

            await(() -> executions.get() > 0, 2000L, "redis-backed schedule should execute at least once");
            Assertions.assertTrue(executions.get() > 0);
        } finally {
            Tock.get().shutdown();
        }
    }
}
