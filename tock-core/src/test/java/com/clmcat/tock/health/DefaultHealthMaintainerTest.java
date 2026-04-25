package com.clmcat.tock.health;

import com.clmcat.tock.Config;
import com.clmcat.tock.TockContext;
import com.clmcat.tock.money.MemoryManager;
import com.clmcat.tock.registry.memory.MemoryTockRegister;
import com.clmcat.tock.schedule.memory.MemoryScheduleStore;
import com.clmcat.tock.worker.WorkerQueue;
import com.clmcat.tock.worker.memory.MemorySubscribableWorkerQueue;
import com.clmcat.tock.worker.scheduler.ScheduledExecutorTaskScheduler;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

class DefaultHealthMaintainerTest {

    @Test
    void shouldPauseSafelyBeforeResume() {
        MemoryTockRegister register = new MemoryTockRegister("health-maintainer", MemoryManager.create());
        MemoryScheduleStore scheduleStore = MemoryScheduleStore.create();
        WorkerQueue workerQueue = MemorySubscribableWorkerQueue.create();
        ExecutorService consumerExecutor = Executors.newSingleThreadExecutor();
        ScheduledExecutorTaskScheduler workerExecutor = new ScheduledExecutorTaskScheduler("health-maintainer-worker");
        ExposedHealthMaintainer maintainer = new ExposedHealthMaintainer();
        TockContext context = TockContext.builder()
                .config(Config.builder()
                        .register(register)
                        .scheduleStore(scheduleStore)
                        .workerQueue(workerQueue)
                        .build())
                .register(register)
                .scheduleStore(scheduleStore)
                .workerQueue(workerQueue)
                .consumerExecutor(consumerExecutor)
                .workerExecutor(workerExecutor)
                .build();

        try {
            maintainer.init(context);
            maintainer.pauseNow(false);
            Assertions.assertNull(register.getGroupAttribute("health.host", Object.class));
        } finally {
            workerExecutor.stop();
            consumerExecutor.shutdownNow();
        }
    }

    private static final class ExposedHealthMaintainer extends DefaultHealthMaintainer {
        private void pauseNow(boolean force) {
            onPause(force);
        }
    }
}
