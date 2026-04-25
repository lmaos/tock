package com.clmcat.tock.worker.redis;

import com.clmcat.tock.Config;
import com.clmcat.tock.RedisTestSupport;
import com.clmcat.tock.TockContext;
import com.clmcat.tock.job.DefaultJobRegistry;
import com.clmcat.tock.registry.redis.RedisTockRegister;
import com.clmcat.tock.schedule.ScheduleConfig;
import com.clmcat.tock.schedule.ScheduleExecutionGuard;
import com.clmcat.tock.schedule.memory.MemoryScheduleStore;
import com.clmcat.tock.store.JobExecution;
import com.clmcat.tock.store.MemoryJobStore;
import com.clmcat.tock.time.DefaultTimeSynchronizer;
import com.clmcat.tock.time.RedisTimeProvider;
import com.clmcat.tock.worker.DefaultTockWorker;
import com.clmcat.tock.worker.WorkerExecutionKeys;
import com.clmcat.tock.worker.scheduler.ScheduledExecutorTaskScheduler;
import com.clmcat.tock.worker.scheduler.TaskScheduler;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class RedisWorkerRecoveryTest extends RedisTestSupport {

    @Test
    void shouldRequeueDelayedJobAfterWorkerStopsAndLateSubscriberShouldDrainBacklog() throws Exception {
        RedisTockRegister register1 = new RedisTockRegister(namespace, jedisPool, null, 3000L, 500L);
        RedisTockRegister register2 = new RedisTockRegister(namespace, jedisPool, null, 3000L, 500L);
        MemoryScheduleStore scheduleStore = MemoryScheduleStore.create();
        ScheduleConfig scheduleConfig = ScheduleConfig.builder()
                .scheduleId("schedule-1")
                .jobId("job-1")
                .fixedDelayMs(5000L)
                .workerGroup("default")
                .zoneId("UTC")
                .build();
        scheduleStore.save(scheduleConfig);

        ExecutorService consumerExecutor1 = Executors.newCachedThreadPool(named("w1-consumer"));
        ExecutorService consumerExecutor2 = Executors.newCachedThreadPool(named("w2-consumer"));
        TaskScheduler workerExecutor1 = new ScheduledExecutorTaskScheduler(1, "w1-worker");
        TaskScheduler workerExecutor2 = new ScheduledExecutorTaskScheduler(1, "w2-worker");
        ScheduledExecutorService schedulerExecutor1 = Executors.newScheduledThreadPool(1, named("w1-scheduler"));
        ScheduledExecutorService schedulerExecutor2 = Executors.newScheduledThreadPool(1, named("w2-scheduler"));

        RedisSubscribableWorkerQueue queue1 = new RedisSubscribableWorkerQueue(namespace, jedisPool);
        RedisSubscribableWorkerQueue queue2 = new RedisSubscribableWorkerQueue(namespace, jedisPool);

        TockContext context1 = buildContext(register1, queue1, scheduleStore, MemoryJobStore.create(),
                consumerExecutor1, workerExecutor1, schedulerExecutor1);
        TockContext context2 = buildContext(register2, queue2, scheduleStore, MemoryJobStore.create(),
                consumerExecutor2, workerExecutor2, schedulerExecutor2);
        queue1.setTockContext(context1);
        queue2.setTockContext(context2);

        DefaultTockWorker worker1 = new DefaultTockWorker();
        DefaultTockWorker worker2 = new DefaultTockWorker();
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(1);

        try {
            register1.start(context1);
            worker1.start(context1);
            worker1.joinGroup("default");
            context1.getJobRegistry().register("job-1", jobContext -> {
                executions.incrementAndGet();
                latch.countDown();
            });
            context2.getJobRegistry().register("job-1", jobContext -> {
                executions.incrementAndGet();
                latch.countDown();
            });

            JobExecution execution = JobExecution.builder()
                    .executionId("exec-1")
                    .scheduleId("schedule-1")
                    .jobId("job-1")
                    .nextFireTime(context1.currentTimeMillis() + 2000L)
                    .workerGroup("default")
                    .scheduleFingerprint(ScheduleExecutionGuard.fingerprint(scheduleConfig))
                    .params(Collections.emptyMap())
                    .build();

            queue1.push(execution, "default");

            String pendingKey = WorkerExecutionKeys.pendingKey(execution);
            await(() -> register1.getNodeAttribute(pendingKey, JobExecution.class) != null, 1000L,
                    "worker1 should track pending execution before fire time");

            worker1.stop();

            register2.start(context2);
            worker2.start(context2);
            worker2.joinGroup("default");

            Assertions.assertTrue(latch.await(4, TimeUnit.SECONDS), "requeued execution should be handled by worker2");
            Assertions.assertEquals(1, executions.get());
            Assertions.assertNull(register1.getNodeAttribute(pendingKey, JobExecution.class));
        } finally {
            worker1.stop();
            worker2.stop();
            register1.stop();
            register2.stop();
            consumerExecutor1.shutdownNow();
            consumerExecutor2.shutdownNow();
            workerExecutor1.stop();
            workerExecutor2.stop();
            schedulerExecutor1.shutdownNow();
            schedulerExecutor2.shutdownNow();
        }
    }

    private TockContext buildContext(RedisTockRegister register, RedisSubscribableWorkerQueue queue,
                                     MemoryScheduleStore scheduleStore, MemoryJobStore jobStore,
                                     ExecutorService consumerExecutor, TaskScheduler workerExecutor,
                                     ScheduledExecutorService schedulerExecutor) {
        Config config = Config.builder()
                .register(register)
                .scheduleStore(scheduleStore)
                .jobStore(jobStore)
                .workerQueue(queue)
                .pendingExecutionRecoveryEnabled(true)
                .build();
        return TockContext.builder()
                .config(config)
                .register(register)
                .jobRegistry(new DefaultJobRegistry())
                .scheduleStore(scheduleStore)
                .jobStore(jobStore)
                .workerQueue(queue)
                .consumerExecutor(consumerExecutor)
                .workerExecutor(workerExecutor)
                .timeSource(new DefaultTimeSynchronizer(new RedisTimeProvider(jedisPool::getResource), 100L, 3))
                .build();
    }

    private java.util.concurrent.ThreadFactory named(String prefix) {
        return r -> {
            Thread thread = new Thread(r, prefix + "-" + java.util.UUID.randomUUID().toString().substring(0, 8));
            thread.setDaemon(true);
            return thread;
        };
    }
}
