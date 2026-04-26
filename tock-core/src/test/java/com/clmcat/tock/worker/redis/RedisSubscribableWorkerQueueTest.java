package com.clmcat.tock.worker.redis;

import com.clmcat.tock.RedisTestSupport;
import com.clmcat.tock.TockContext;
import com.clmcat.tock.job.DefaultJobRegistry;
import com.clmcat.tock.money.MemoryManager;
import com.clmcat.tock.registry.memory.MemoryTockRegister;
import com.clmcat.tock.schedule.memory.MemoryScheduleStore;
import com.clmcat.tock.store.JobExecution;
import com.clmcat.tock.store.MemoryJobStore;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class RedisSubscribableWorkerQueueTest extends RedisTestSupport {

    @Test
    void shouldFollowLifecycleContract() {
        RedisSubscribableWorkerQueue queue = new RedisSubscribableWorkerQueue(namespace, jedisPool);
        MemoryTockRegister register = new MemoryTockRegister("queue-lifecycle-register", MemoryManager.create());
        TockContext context = TockContext.builder()
                .register(register)
                .scheduleStore(MemoryScheduleStore.create())
                .jobStore(MemoryJobStore.create())
                .workerQueue(queue)
                .jobRegistry(new DefaultJobRegistry())
                .consumerExecutor(consumerExecutor)
                .workerExecutor(workerExecutor)
                .timeSource(new com.clmcat.tock.time.DefaultTimeSynchronizer(new com.clmcat.tock.time.SystemTimeProvider(), 100L, 3))
                .build();
        queue.setTockContext(context);

        Assertions.assertFalse(queue.isStarted());
        queue.start(context);
        try {
            Assertions.assertTrue(queue.isStarted());
            queue.start(context);
            Assertions.assertTrue(queue.isStarted());
        } finally {
            queue.stop();
            Assertions.assertFalse(queue.isStarted());
            queue.stop();
            Assertions.assertFalse(queue.isStarted());
        }
    }

    @Test
    void shouldDeliverPublishedJobsToSubscribers() throws Exception {
        RedisSubscribableWorkerQueue queue = new RedisSubscribableWorkerQueue(namespace, jedisPool);
        MemoryTockRegister register = new MemoryTockRegister("queue-register", MemoryManager.create());
        TockContext context = TockContext.builder()
                .register(register)
                .scheduleStore(MemoryScheduleStore.create())
                .jobStore(MemoryJobStore.create())
                .workerQueue(queue)
                .jobRegistry(new DefaultJobRegistry())
                .consumerExecutor(consumerExecutor)
                .workerExecutor(workerExecutor)
                .timeSource(new com.clmcat.tock.time.DefaultTimeSynchronizer(new com.clmcat.tock.time.SystemTimeProvider(), 100L, 3))
                .build();
        queue.setTockContext(context);

        AtomicInteger counter = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(2);
        queue.subscribe("default", execution -> {
            counter.incrementAndGet();
            latch.countDown();
        });
        queue.subscribe("default", execution -> {
            counter.incrementAndGet();
            latch.countDown();
        });

        queue.push(execution("e-1"), "default");

        Assertions.assertTrue(latch.await(2, TimeUnit.SECONDS));
        Assertions.assertEquals(2, counter.get());

        queue.unsubscribe("default");
        queue.push(execution("e-2"), "default");
        sleep(100L);
        Assertions.assertEquals(2, counter.get());
    }

    @Test
    void shouldDrainBacklogWhenSubscriberJoinsLater() throws Exception {
        RedisSubscribableWorkerQueue queue = new RedisSubscribableWorkerQueue(namespace, jedisPool, 1);
        MemoryTockRegister register = new MemoryTockRegister("queue-register", MemoryManager.create());
        TockContext context = TockContext.builder()
                .register(register)
                .scheduleStore(MemoryScheduleStore.create())
                .jobStore(MemoryJobStore.create())
                .workerQueue(queue)
                .jobRegistry(new DefaultJobRegistry())
                .consumerExecutor(consumerExecutor)
                .workerExecutor(workerExecutor)
                .timeSource(new com.clmcat.tock.time.DefaultTimeSynchronizer(new com.clmcat.tock.time.SystemTimeProvider(), 100L, 3))
                .build();
        queue.setTockContext(context);

        queue.push(execution("e-backlog"), "default");

        CountDownLatch latch = new CountDownLatch(1);
        queue.subscribe("default", execution -> latch.countDown());

        Assertions.assertTrue(latch.await(2, TimeUnit.SECONDS));
    }

    private JobExecution execution(String executionId) {
        return JobExecution.builder()
                .executionId(executionId)
                .scheduleId("schedule-1")
                .jobId("job-1")
                .nextFireTime(System.currentTimeMillis() + 1000L)
                .workerGroup("default")
                .params(Collections.emptyMap())
                .build();
    }
}
