package com.clmcat.tock;

import com.clmcat.tock.money.MemoryManager;
import com.clmcat.tock.registry.memory.MemoryTockRegister;
import com.clmcat.tock.schedule.memory.MemoryScheduleStore;
import com.clmcat.tock.worker.memory.MemoryPullableWorkerQueue;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ConfigTest {

    @Test
    void shouldBuildMinimalConfigWithOptionalDependenciesMissing() {
        MemoryTockRegister register = new MemoryTockRegister("test-timer", MemoryManager.create());
        MemoryScheduleStore scheduleStore = MemoryScheduleStore.create();
        MemoryPullableWorkerQueue workerQueue = MemoryPullableWorkerQueue.create();

        Config config = Config.builder()
                .register(register)
                .scheduleStore(scheduleStore)
                .workerQueue(workerQueue)
                .build();

        Assertions.assertSame(register, config.getRegister());
        Assertions.assertSame(scheduleStore, config.getScheduleStore());
        Assertions.assertSame(workerQueue, config.getWorkerQueue());
        Assertions.assertNull(config.getSerializer());
        Assertions.assertNull(config.getJobStore());
        Assertions.assertNull(config.getTimeProvider());
        Assertions.assertNull(config.getTimeSynchronizer());
        Assertions.assertTrue(config.isManageThreadPools());
        Assertions.assertFalse(config.isPendingExecutionRecoveryEnabled());
    }

    @Test
    void shouldRejectMissingCoreComponents() {
        MemoryTockRegister register = new MemoryTockRegister("test-timer", MemoryManager.create());
        MemoryScheduleStore scheduleStore = MemoryScheduleStore.create();
        MemoryPullableWorkerQueue workerQueue = MemoryPullableWorkerQueue.create();

        Assertions.assertThrows(NullPointerException.class, () -> Config.builder()
                .scheduleStore(scheduleStore)
                .workerQueue(workerQueue)
                .build());
        Assertions.assertThrows(NullPointerException.class, () -> Config.builder()
                .register(register)
                .workerQueue(workerQueue)
                .build());
        Assertions.assertThrows(NullPointerException.class, () -> Config.builder()
                .register(register)
                .scheduleStore(scheduleStore)
                .build());
    }
}
