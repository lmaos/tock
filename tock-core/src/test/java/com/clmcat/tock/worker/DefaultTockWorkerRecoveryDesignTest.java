package com.clmcat.tock.worker;

import com.clmcat.tock.Config;
import com.clmcat.tock.TockContext;
import com.clmcat.tock.job.DefaultJobRegistry;
import com.clmcat.tock.money.MemoryManager;
import com.clmcat.tock.registry.memory.MemoryTockRegister;
import com.clmcat.tock.schedule.ScheduleConfig;
import com.clmcat.tock.schedule.ScheduleExecutionGuard;
import com.clmcat.tock.schedule.memory.MemoryScheduleStore;
import com.clmcat.tock.store.JobExecution;
import com.clmcat.tock.store.MemoryJobStore;
import com.clmcat.tock.time.TimeSource;
import com.clmcat.tock.worker.memory.MemoryPullableWorkerQueue;
import com.clmcat.tock.worker.scheduler.ScheduledExecutorTaskScheduler;
import com.clmcat.tock.worker.scheduler.TaskScheduler;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class DefaultTockWorkerRecoveryDesignTest {

    @Test
    void shouldTrackPendingExecutionsWithoutTakingActiveLeaseEarly() {
        MemoryManager memoryManager = MemoryManager.create();
        MemoryTockRegister register = new MemoryTockRegister("worker-node", memoryManager);
        MemoryScheduleStore scheduleStore = MemoryScheduleStore.create();
        MemoryJobStore jobStore = MemoryJobStore.create();
        MemoryPullableWorkerQueue queue = MemoryPullableWorkerQueue.create();
        DefaultTockWorker worker = new DefaultTockWorker();
        ManualTimeSource timeSource = new ManualTimeSource(10_000L);
        TaskScheduler workerExecutor = new ScheduledExecutorTaskScheduler("test-worker");
        ScheduledExecutorService schedulerExecutor = Executors.newSingleThreadScheduledExecutor();
        ExecutorService consumerExecutor = Executors.newSingleThreadExecutor();

        ScheduleConfig scheduleConfig = ScheduleConfig.builder()
                .scheduleId("schedule-a")
                .jobId("job-a")
                .fixedDelayMs(200L)
                .workerGroup("default")
                .zoneId("UTC")
                .build();
        scheduleStore.save(scheduleConfig);

        Config config = Config.builder()
                .register(register)
                .scheduleStore(scheduleStore)
                .jobStore(jobStore)
                .workerQueue(queue)
                .pendingExecutionRecoveryEnabled(true)
                .build();

        TockContext context = TockContext.builder()
                .config(config)
                .register(register)
                .scheduleStore(scheduleStore)
                .jobStore(jobStore)
                .workerQueue(queue)
                .jobRegistry(new DefaultJobRegistry())
                .consumerExecutor(consumerExecutor)
                .workerExecutor(workerExecutor)
                .timeSource(timeSource)
                .build();

        try {
            worker.init(context);
            register.start(context);
            worker.start(context);

            JobExecution execution1 = execution("e-1", 15_000L, scheduleConfig);
            JobExecution execution2 = execution("e-2", 15_200L, scheduleConfig);

            worker.executeJob(execution1);
            worker.executeJob(execution2);

            Assertions.assertNull(register.getGroupAttribute(WorkerExecutionKeys.activeKey("default", "schedule-a"), Object.class));
            Assertions.assertNotNull(register.getNodeAttribute(WorkerExecutionKeys.pendingKey(execution1), JobExecution.class));
            Assertions.assertNotNull(register.getNodeAttribute(WorkerExecutionKeys.pendingKey(execution2), JobExecution.class));
        } finally {
            worker.stop();
            register.stop();
            consumerExecutor.shutdownNow();
            workerExecutor.stop();
            schedulerExecutor.shutdownNow();
        }
    }

    private JobExecution execution(String executionId, long nextFireTime, ScheduleConfig scheduleConfig) {
        return JobExecution.builder()
                .executionId(executionId)
                .scheduleId(scheduleConfig.getScheduleId())
                .jobId(scheduleConfig.getJobId())
                .nextFireTime(nextFireTime)
                .workerGroup(scheduleConfig.getWorkerGroup())
                .scheduleFingerprint(ScheduleExecutionGuard.fingerprint(scheduleConfig))
                .params(Collections.emptyMap())
                .build();
    }

    private static final class ManualTimeSource implements TimeSource {
        private final AtomicLong currentTime;

        private ManualTimeSource(long currentTime) {
            this.currentTime = new AtomicLong(currentTime);
        }

        @Override
        public long currentTimeMillis() {
            return currentTime.get();
        }
    }
}
