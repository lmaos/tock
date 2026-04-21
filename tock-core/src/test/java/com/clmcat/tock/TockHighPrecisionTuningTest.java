package com.clmcat.tock;

import com.clmcat.tock.money.MemoryManager;
import com.clmcat.tock.registry.memory.MemoryTockRegister;
import com.clmcat.tock.schedule.memory.MemoryScheduleStore;
import com.clmcat.tock.time.TimeProvider;
import com.clmcat.tock.worker.memory.MemoryPullableWorkerQueue;
import com.clmcat.tock.worker.scheduler.HighPrecisionWheelTaskScheduler;
import com.clmcat.tock.worker.scheduler.TaskSchedulers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TockHighPrecisionTuningTest {

    @AfterEach
    void resetTock() {
        Tock.resetForTest();
    }

    @Test
    void shouldApplyDistributedAdvanceToDefaultHighPrecisionScheduler() {
        HighPrecisionWheelTaskScheduler workerExecutor = TaskSchedulers.highPrecision("test-worker");
        Config config = Config.builder()
                .register(new MemoryTockRegister("tuning-default", MemoryManager.create()))
                .scheduleStore(MemoryScheduleStore.create())
                .workerQueue(MemoryPullableWorkerQueue.create())
                .workerExecutor(workerExecutor)
                .timeProvider(new RemoteLikeTimeProvider())
                .build();

        Tock.configure(config);

        Assertions.assertEquals(HighPrecisionWheelTaskScheduler.DISTRIBUTED_DEFAULT_ADVANCE_NANOS, workerExecutor.advanceNanos());
    }

    @Test
    void shouldKeepExplicitHighPrecisionAdvanceUntouched() {
        HighPrecisionWheelTaskScheduler workerExecutor = TaskSchedulers.highPrecision("test-worker");
        workerExecutor.setAdvanceNanos(2_000_000L);
        Config config = Config.builder()
                .register(new MemoryTockRegister("tuning-custom", MemoryManager.create()))
                .scheduleStore(MemoryScheduleStore.create())
                .workerQueue(MemoryPullableWorkerQueue.create())
                .workerExecutor(workerExecutor)
                .timeProvider(new RemoteLikeTimeProvider())
                .build();

        Tock.configure(config);

        Assertions.assertEquals(2_000_000L, workerExecutor.advanceNanos());
    }

    @Test
    void shouldKeepLocalDefaultAdvanceForSystemTime() {
        HighPrecisionWheelTaskScheduler workerExecutor = TaskSchedulers.highPrecision("test-worker");
        Config config = Config.builder()
                .register(new MemoryTockRegister("tuning-system", MemoryManager.create()))
                .scheduleStore(MemoryScheduleStore.create())
                .workerQueue(MemoryPullableWorkerQueue.create())
                .workerExecutor(workerExecutor)
                .build();

        Tock.configure(config);

        Assertions.assertEquals(HighPrecisionWheelTaskScheduler.DEFAULT_ADVANCE_NANOS, workerExecutor.advanceNanos());
    }

    private static final class RemoteLikeTimeProvider implements TimeProvider {
        @Override
        public long currentTimeMillis() {
            return System.currentTimeMillis();
        }
    }
}
