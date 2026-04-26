package com.clmcat.tock.worker;

import com.clmcat.tock.Config;
import com.clmcat.tock.TockContext;
import com.clmcat.tock.health.HeartbeatReportListener;
import com.clmcat.tock.health.HeartbeatReporter;
import com.clmcat.tock.job.DefaultJobRegistry;
import com.clmcat.tock.money.MemoryManager;
import com.clmcat.tock.registry.TockMaster;
import com.clmcat.tock.registry.TockNode;
import com.clmcat.tock.registry.TockRegister;
import com.clmcat.tock.registry.memory.MemoryTockRegister;
import com.clmcat.tock.schedule.memory.MemoryScheduleStore;
import com.clmcat.tock.time.TimeSource;
import com.clmcat.tock.worker.WorkerQueue;
import com.clmcat.tock.worker.memory.MemorySubscribableWorkerQueue;
import com.clmcat.tock.worker.scheduler.ScheduledExecutorTaskScheduler;
import com.clmcat.tock.worker.scheduler.TaskScheduler;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArraySet;

class DefaultTockWorkerHeartbeatCircuitBreakerTest {

    @Test
    void shouldPauseAndResumeWhenHeartbeatCircuitFlips() {
        MemoryManager memoryManager = MemoryManager.create();
        MemoryTockRegister register = new MemoryTockRegister("worker-heartbeat", memoryManager);
        MemoryScheduleStore scheduleStore = MemoryScheduleStore.create();
        WorkerQueue workerQueue = MemorySubscribableWorkerQueue.create();
        RecordingHeartbeatReporter heartbeatReporter = new RecordingHeartbeatReporter();
        TaskScheduler workerExecutor = new ScheduledExecutorTaskScheduler("worker-heartbeat");
        ExecutorService consumerExecutor = Executors.newSingleThreadExecutor();
        DefaultTockWorker worker = new DefaultTockWorker();
        TockContext context = TockContext.builder()
                .config(Config.builder()
                        .register(register)
                        .scheduleStore(scheduleStore)
                        .workerQueue(workerQueue)
                        .build())
                .register(register)
                .scheduleStore(scheduleStore)
                .workerQueue(workerQueue)
                .jobRegistry(new DefaultJobRegistry())
                .workerExecutor(workerExecutor)
                .consumerExecutor(consumerExecutor)
                .heartbeatReporter(heartbeatReporter)
                .timeSource(new TimeSource() {
                    @Override
                    public long currentTimeMillis() {
                        return System.currentTimeMillis();
                    }
                })
                .build();

        try {
            worker.init(context);
            worker.start(context);
            register.start(context);

            Assertions.assertFalse(worker.isRunning());

            heartbeatReporter.success();
            Assertions.assertTrue(worker.isRunning());

            heartbeatReporter.fail();
            Assertions.assertFalse(worker.isRunning());

            heartbeatReporter.success();
            Assertions.assertTrue(worker.isRunning());
        } finally {
            worker.stop();
            register.stop();
            consumerExecutor.shutdownNow();
            workerExecutor.stop();
        }
    }

    @Test
    void shouldStayPausedIfReporterIsAlreadyDegradedDuringStartup() {
        MemoryManager memoryManager = MemoryManager.create();
        MemoryTockRegister register = new MemoryTockRegister("worker-heartbeat-boundary", memoryManager);
        MemoryScheduleStore scheduleStore = MemoryScheduleStore.create();
        WorkerQueue workerQueue = MemorySubscribableWorkerQueue.create();
        RecordingHeartbeatReporter heartbeatReporter = new RecordingHeartbeatReporter();
        heartbeatReporter.setHealthy(false);
        TaskScheduler workerExecutor = new ScheduledExecutorTaskScheduler("worker-heartbeat-boundary");
        ExecutorService consumerExecutor = Executors.newSingleThreadExecutor();
        DefaultTockWorker worker = new DefaultTockWorker();
        TockContext context = TockContext.builder()
                .config(Config.builder()
                        .register(register)
                        .scheduleStore(scheduleStore)
                        .workerQueue(workerQueue)
                        .build())
                .register(register)
                .scheduleStore(scheduleStore)
                .workerQueue(workerQueue)
                .jobRegistry(new DefaultJobRegistry())
                .workerExecutor(workerExecutor)
                .consumerExecutor(consumerExecutor)
                .heartbeatReporter(heartbeatReporter)
                .timeSource(new TimeSource() {
                    @Override
                    public long currentTimeMillis() {
                        return System.currentTimeMillis();
                    }
                })
                .build();

        try {
            worker.init(context);
            worker.start(context);
            register.start(context);

            Assertions.assertFalse(worker.isRunning());

            heartbeatReporter.success();
            Assertions.assertTrue(worker.isRunning());
        } finally {
            worker.stop();
            register.stop();
            consumerExecutor.shutdownNow();
            workerExecutor.stop();
        }
    }

    private static final class RecordingHeartbeatReporter implements HeartbeatReporter {
        private final Set<HeartbeatReportListener> listeners = new CopyOnWriteArraySet<HeartbeatReportListener>();
        private volatile boolean healthy = true;
        private volatile boolean established = false;
        private volatile boolean started;

        @Override
        public void start(TockContext context) {
            started = true;
        }

        @Override
        public void stop() {
            started = false;
        }

        @Override
        public long serverTime() {
            return -1;
        }

        @Override
        public boolean isHeartbeatHealthy() {
            return healthy;
        }

        @Override
        public boolean isHeartbeatEstablished() {
            return established;
        }

        @Override
        public void addHeartbeatReportListener(HeartbeatReportListener listener) {
            listeners.add(listener);
        }

        @Override
        public void removeHeartbeatReportListener(HeartbeatReportListener listener) {
            listeners.remove(listener);
        }

        @Override
        public void addMasterChangeListener(com.clmcat.tock.health.client.MasterChangeListener listener) {
        }

        @Override
        public void removeMasterChangeListener(com.clmcat.tock.health.client.MasterChangeListener listener) {
        }

        @Override
        public boolean isStarted() {
            return started;
        }

        private void fail() {
            healthy = false;
            for (HeartbeatReportListener listener : listeners) {
                listener.onHeartbeatReportFailed(4);
            }
        }

        private void success() {
            boolean firstSuccess = !established;
            boolean wasHealthy = healthy;
            established = true;
            healthy = true;
            for (HeartbeatReportListener listener : listeners) {
                if (firstSuccess) {
                    listener.onHeartbeatReportFirstSuccess();
                } else if (!wasHealthy) {
                    listener.onHeartbeatReportRecovered();
                }
            }
        }

        private void setHealthy(boolean healthy) {
            this.healthy = healthy;
        }
    }
}
