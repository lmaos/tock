package com.clmcat.tock.health;

import com.clmcat.tock.Config;
import com.clmcat.tock.TockContext;
import com.clmcat.tock.health.client.HealthClientManager;
import com.clmcat.tock.health.client.HealthResponse;
import com.clmcat.tock.money.MemoryManager;
import com.clmcat.tock.registry.memory.MemoryTockRegister;
import com.clmcat.tock.schedule.memory.MemoryScheduleStore;
import com.clmcat.tock.worker.WorkerQueue;
import com.clmcat.tock.worker.memory.MemorySubscribableWorkerQueue;
import com.clmcat.tock.worker.scheduler.ScheduledExecutorTaskScheduler;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

class DefaultHeartbeatReporterTest {

    @Test
    void shouldTripOnceAfterFourFailuresAndRecoverOnceOnSuccess() {
        MemoryTockRegister register = new MemoryTockRegister("heartbeat-test", MemoryManager.create());
        MemoryScheduleStore scheduleStore = MemoryScheduleStore.create();
        WorkerQueue workerQueue = MemorySubscribableWorkerQueue.create();
        ExecutorService consumerExecutor = Executors.newSingleThreadExecutor();
        ScheduledExecutorTaskScheduler workerExecutor = new ScheduledExecutorTaskScheduler("heartbeat-test-worker");
        ScriptedHeartbeatReporter reporter = new ScriptedHeartbeatReporter(register);
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
                .heartbeatReporter(reporter)
                .build();

        AtomicInteger failureEvents = new AtomicInteger();
        AtomicInteger recoveryEvents = new AtomicInteger();
        reporter.addHeartbeatReportListener(new HeartbeatReportListener() {
            @Override
            public void onHeartbeatReportFailed(int consecutiveFailures) {
                failureEvents.incrementAndGet();
                Assertions.assertEquals(4, consecutiveFailures);
            }

            @Override
            public void onHeartbeatReportRecovered() {
                recoveryEvents.incrementAndGet();
            }
        });

        try {
            reporter.init(context);
            reporter.enqueue(HealthResponse.TIMEOUT);
            reporter.enqueue(HealthResponse.TIMEOUT);
            reporter.enqueue(HealthResponse.TIMEOUT);
            reporter.enqueue(HealthResponse.TIMEOUT);
            reporter.enqueue(HealthResponse.TIMEOUT);
            reporter.enqueue(success());

            Assertions.assertTrue(reporter.isHeartbeatHealthy());
            reporter.reportActive();
            reporter.reportActive();
            reporter.reportActive();
            Assertions.assertTrue(reporter.isHeartbeatHealthy());
            Assertions.assertEquals(0, failureEvents.get());
            Assertions.assertEquals(0, recoveryEvents.get());

            reporter.reportActive();
            Assertions.assertFalse(reporter.isHeartbeatHealthy());
            Assertions.assertEquals(1, failureEvents.get());
            Assertions.assertEquals(0, recoveryEvents.get());

            reporter.reportActive();
            Assertions.assertFalse(reporter.isHeartbeatHealthy());
            Assertions.assertEquals(1, failureEvents.get());

            reporter.reportActive();
            Assertions.assertTrue(reporter.isHeartbeatHealthy());
            Assertions.assertEquals(1, failureEvents.get());
            Assertions.assertEquals(1, recoveryEvents.get());

            reporter.reportActive();
            Assertions.assertTrue(reporter.isHeartbeatHealthy());
            Assertions.assertEquals(1, failureEvents.get());
            Assertions.assertEquals(1, recoveryEvents.get());

            reporter.reportActive();
            Assertions.assertTrue(reporter.isHeartbeatHealthy());
            Assertions.assertEquals(1, recoveryEvents.get());
        } finally {
            workerExecutor.stop();
            consumerExecutor.shutdownNow();
        }
    }

    private HealthResponse success() {
        return HealthResponse.builder()
                .reqId(1)
                .code((byte) 0)
                .type((byte) 0)
                .time(System.currentTimeMillis())
                .build();
    }

    private static final class ScriptedHeartbeatReporter extends DefaultHeartbeatReporter {
        private final ScriptedHealthClientManager healthClientManager;

        private ScriptedHeartbeatReporter(MemoryTockRegister register) {
            this.healthClientManager = new ScriptedHealthClientManager(register);
        }

        @Override
        protected HealthClientManager createHealthClientManager(com.clmcat.tock.registry.TockRegister register) {
            return healthClientManager;
        }

        private void enqueue(HealthResponse response) {
            healthClientManager.enqueue(response);
        }
    }

    private static final class ScriptedHealthClientManager extends HealthClientManager {
        private final Queue<HealthResponse> responses = new ArrayDeque<>();

        private ScriptedHealthClientManager(MemoryTockRegister register) {
            super(register);
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public HealthResponse reportActive(String nodeId) {
            HealthResponse response = responses.poll();
            return response == null ? success() : response;
        }

        @Override
        public long serverTime() {
            return -1;
        }

        private void enqueue(HealthResponse response) {
            responses.add(response);
        }

        private HealthResponse success() {
            return HealthResponse.builder()
                    .reqId(1)
                    .code((byte) 0)
                    .type((byte) 0)
                    .time(System.currentTimeMillis())
                    .build();
        }
    }
}
