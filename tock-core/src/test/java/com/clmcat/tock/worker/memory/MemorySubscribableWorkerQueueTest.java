package com.clmcat.tock.worker.memory;

import com.clmcat.tock.TockContext;
import com.clmcat.tock.job.DefaultJobRegistry;
import com.clmcat.tock.money.MemoryManager;
import com.clmcat.tock.registry.memory.MemoryTockRegister;
import com.clmcat.tock.schedule.memory.MemoryScheduleStore;
import com.clmcat.tock.store.JobExecution;
import com.clmcat.tock.store.MemoryJobStore;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class MemorySubscribableWorkerQueueTest {

    @Test
    void shouldDeliverToAllSubscribersAndSurviveExceptions() throws Exception {
        ExecutorService consumerExecutor = Executors.newSingleThreadExecutor();
        try {
            MemoryManager memoryManager = MemoryManager.create();
            MemoryTockRegister register = new MemoryTockRegister("queue-node", memoryManager);
            MemorySubscribableWorkerQueue queue = new MemorySubscribableWorkerQueue();
            TockContext context = TockContext.builder()
                    .register(register)
                    .master(register.getMaster())
                    .scheduleStore(MemoryScheduleStore.create())
                    .jobStore(MemoryJobStore.create())
                    .workerQueue(queue)
                    .jobRegistry(new DefaultJobRegistry())
                    .consumerExecutor(consumerExecutor)
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
                throw new IllegalStateException("boom");
            });

            queue.push(execution("e1"), "default");

            Assertions.assertTrue(latch.await(2, TimeUnit.SECONDS));
            Assertions.assertEquals(2, counter.get());

            queue.unsubscribe("default");
            queue.push(execution("e2"), "default");
            Thread.sleep(100L);
            Assertions.assertEquals(2, counter.get());
        } finally {
            consumerExecutor.shutdownNow();
        }
    }

    @Test
    void shouldFailFastWhenContextMissing() {
        MemorySubscribableWorkerQueue queue = new MemorySubscribableWorkerQueue();
        queue.subscribe("default", execution -> { });

        Assertions.assertThrows(IllegalStateException.class,
                () -> queue.push(execution("e1"), "default"));
    }

    private static JobExecution execution(String executionId) {
        return JobExecution.builder()
                .executionId(executionId)
                .scheduleId("schedule-a")
                .jobId("job-a")
                .nextFireTime(System.currentTimeMillis() + 1000L)
                .workerGroup("default")
                .params(java.util.Collections.emptyMap())
                .build();
    }
}
