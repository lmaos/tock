package com.clmcat.tock.health;

import com.clmcat.tock.Config;
import com.clmcat.tock.TockContext;
import com.clmcat.tock.health.HealthHost;
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

    @Test
    void shouldReplaceStaleHealthHostOnResume() {
        MemoryTockRegister register = new MemoryTockRegister("health-maintainer-replace", MemoryManager.create());
        MemoryScheduleStore scheduleStore = MemoryScheduleStore.create();
        WorkerQueue workerQueue = MemorySubscribableWorkerQueue.create();
        ExecutorService consumerExecutor = Executors.newSingleThreadExecutor();
        ScheduledExecutorTaskScheduler workerExecutor = new ScheduledExecutorTaskScheduler("health-maintainer-worker-replace");
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

        HealthHost stale = new HealthHost();
        stale.setKey("stale-key");
        stale.setPort(12345);

        try {
            maintainer.init(context);
            maintainer.start(context);
            register.setGroupAttributeIfAbsent("health.host", stale);

            maintainer.resumeNow();

            HealthHost current = register.getGroupAttribute("health.host", HealthHost.class);
            Assertions.assertNotNull(current);
            Assertions.assertNotEquals("stale-key", current.getKey());
            Assertions.assertNotEquals(12345, current.getPort());
        } finally {
            maintainer.stop();
            workerExecutor.stop();
            consumerExecutor.shutdownNow();
        }
    }

    private static final class ExposedHealthMaintainer extends DefaultHealthMaintainer {
        private void pauseNow(boolean force) {
            onPause(force);
        }

        private void resumeNow() {
            onResume();
        }
    }
}
