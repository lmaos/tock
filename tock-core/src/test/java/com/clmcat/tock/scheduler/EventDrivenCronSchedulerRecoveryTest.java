package com.clmcat.tock.scheduler;

import com.clmcat.tock.Config;
import com.clmcat.tock.TockContext;
import com.clmcat.tock.money.MemoryManager;
import com.clmcat.tock.registry.TockNode;
import com.clmcat.tock.registry.memory.MemoryTockRegister;
import com.clmcat.tock.schedule.ScheduleConfig;
import com.clmcat.tock.schedule.ScheduleExecutionGuard;
import com.clmcat.tock.schedule.memory.MemoryScheduleStore;
import com.clmcat.tock.store.JobExecution;
import com.clmcat.tock.store.MemoryJobStore;
import com.clmcat.tock.time.TimeSource;
import com.clmcat.tock.worker.WorkerExecutionKeys;
import com.clmcat.tock.worker.WorkerExecutionLease;
import com.clmcat.tock.worker.WorkerQueue;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class EventDrivenCronSchedulerRecoveryTest {

    @Test
    void shouldRecoverPendingExecutionWhenStillWithinInterval() {
        Fixture fixture = new Fixture();
        ScheduleConfig config = fixture.fixedDelayConfig(5_000L);
        JobExecution execution = fixture.execution("e-1", 4_500L, config);

        fixture.scheduleStore.save(config);
        fixture.workerRegister.getCurrentNode().start(fixture.workerContext);
        fixture.workerRegister.getCurrentNode().setAttributeIfAbsent(WorkerExecutionKeys.pendingKey(execution), execution);
        fixture.timeSource.set(4_100L);

        TockNode expiredNode = fixture.masterRegister.getExpiredNodes().get(0);
        fixture.scheduler.recoverExpiredNode(expiredNode);

        Assertions.assertEquals(1, fixture.workerQueue.executions.size());
        Assertions.assertEquals(execution.getExecutionId(), fixture.workerQueue.executions.get(0).getExecutionId());
    }

    @Test
    void shouldDropPendingExecutionWhenScheduleChanged() {
        Fixture fixture = new Fixture();
        ScheduleConfig original = fixture.fixedDelayConfig(5_000L);
        JobExecution execution = fixture.execution("e-1", 4_500L, original);
        fixture.scheduleStore.save(original);
        fixture.scheduleStore.save(original.toBuilder().fixedDelayMs(8_000L).build());

        fixture.workerRegister.getCurrentNode().start(fixture.workerContext);
        fixture.workerRegister.getCurrentNode().setAttributeIfAbsent(WorkerExecutionKeys.pendingKey(execution), execution);
        fixture.timeSource.set(4_100L);

        TockNode expiredNode = fixture.masterRegister.getExpiredNodes().get(0);
        fixture.scheduler.recoverExpiredNode(expiredNode);

        Assertions.assertTrue(fixture.workerQueue.executions.isEmpty());
    }

    @Test
    void shouldRecoverActiveExecutionFromExpiredNode() {
        Fixture fixture = new Fixture();
        ScheduleConfig config = fixture.fixedDelayConfig(5_000L);
        JobExecution execution = fixture.execution("e-1", 4_500L, config);

        fixture.scheduleStore.save(config);
        fixture.workerRegister.getCurrentNode().start(fixture.workerContext);
        fixture.workerRegister.getCurrentNode().setAttributeIfAbsent(WorkerExecutionKeys.activeKey(execution), execution);
        fixture.masterRegister.setGroupAttributeIfAbsent(
                WorkerExecutionKeys.activeKey(execution),
                new WorkerExecutionLease(fixture.workerRegister.getCurrentNode().getId(), execution.getExecutionId())
        );
        fixture.timeSource.set(4_100L);

        TockNode expiredNode = fixture.masterRegister.getExpiredNodes().get(0);
        fixture.scheduler.recoverExpiredNode(expiredNode);

        Assertions.assertEquals(1, fixture.workerQueue.executions.size());
        Assertions.assertEquals(execution.getExecutionId(), fixture.workerQueue.executions.get(0).getExecutionId());
    }

    @Test
    void shouldDropPendingExecutionWhenItHasExceededFixedDelayWindow() {
        Fixture fixture = new Fixture();
        ScheduleConfig config = fixture.fixedDelayConfig(1_000L);
        JobExecution execution = fixture.execution("e-1", 1_500L, config);

        fixture.scheduleStore.save(config);
        fixture.workerRegister.getCurrentNode().start(fixture.workerContext);
        fixture.workerRegister.getCurrentNode().setAttributeIfAbsent(WorkerExecutionKeys.pendingKey(execution), execution);
        fixture.timeSource.set(4_100L);

        TockNode expiredNode = fixture.masterRegister.getExpiredNodes().get(0);
        fixture.scheduler.recoverExpiredNode(expiredNode);

        Assertions.assertTrue(fixture.workerQueue.executions.isEmpty());
    }

    private static final class Fixture {
        private final ManualTimeSource timeSource = new ManualTimeSource(1_000L);
        private final MemoryManager memoryManager = MemoryManager.create();
        private final MemoryScheduleStore scheduleStore = MemoryScheduleStore.create();
        private final RecordingWorkerQueue workerQueue = new RecordingWorkerQueue();
        private final MemoryTockRegister masterRegister = new MemoryTockRegister("cluster", memoryManager);
        private final MemoryTockRegister workerRegister = new MemoryTockRegister("cluster", memoryManager);
        private final EventDrivenCronScheduler scheduler = new EventDrivenCronScheduler();
        private final TockContext masterContext;
        private final TockContext workerContext;

        private Fixture() {
            masterContext = context(masterRegister);
            workerContext = context(workerRegister);
            scheduler.setTockContext(masterContext);
        }

        private TockContext context(MemoryTockRegister register) {
            Config config = Config.builder()
                    .register(register)
                    .scheduleStore(scheduleStore)
                    .jobStore(MemoryJobStore.create())
                    .workerQueue(workerQueue)
                    .build();
            return TockContext.builder()
                    .config(config)
                    .register(register)
                    .master(register.getMaster())
                    .scheduleStore(scheduleStore)
                    .jobStore(MemoryJobStore.create())
                    .workerQueue(workerQueue)
                    .timeSource(timeSource)
                    .build();
        }

        private ScheduleConfig fixedDelayConfig(long fixedDelayMs) {
            return ScheduleConfig.builder()
                    .scheduleId("schedule-a")
                    .jobId("job-a")
                    .fixedDelayMs(fixedDelayMs)
                    .workerGroup("default")
                    .zoneId("UTC")
                    .build();
        }

        private JobExecution execution(String executionId, long nextFireTime, ScheduleConfig config) {
            return JobExecution.builder()
                    .executionId(executionId)
                    .scheduleId(config.getScheduleId())
                    .jobId(config.getJobId())
                    .nextFireTime(nextFireTime)
                    .workerGroup(config.getWorkerGroup())
                    .scheduleFingerprint(ScheduleExecutionGuard.fingerprint(config))
                    .params(Collections.emptyMap())
                    .build();
        }
    }

    private static final class RecordingWorkerQueue implements WorkerQueue {
        private final List<JobExecution> executions = new ArrayList<>();

        @Override
        public void push(JobExecution execution, String workerGroup) {
            executions.add(execution);
        }
    }

    private static final class ManualTimeSource implements TimeSource {
        private final AtomicLong current = new AtomicLong();

        private ManualTimeSource(long initial) {
            current.set(initial);
        }

        private void set(long value) {
            current.set(value);
        }

        @Override
        public long currentTimeMillis() {
            return current.get();
        }
    }
}
