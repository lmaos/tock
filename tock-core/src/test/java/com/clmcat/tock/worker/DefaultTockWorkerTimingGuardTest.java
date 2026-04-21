package com.clmcat.tock.worker;

import com.clmcat.tock.Config;
import com.clmcat.tock.TockContext;
import com.clmcat.tock.registry.TockCurrentNode;
import com.clmcat.tock.registry.TockMaster;
import com.clmcat.tock.registry.TockNode;
import com.clmcat.tock.registry.TockRegister;
import com.clmcat.tock.schedule.memory.MemoryScheduleStore;
import com.clmcat.tock.store.JobExecution;
import com.clmcat.tock.worker.memory.MemorySubscribableWorkerQueue;
import com.clmcat.tock.worker.scheduler.TaskScheduler;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

class DefaultTockWorkerTimingGuardTest {

    @Test
    void shouldRescheduleWhenSyncedClockMovesBackwardsBeforeExecution() {
        AdjustableTimeSource timeSource = new AdjustableTimeSource(1_000L);
        RecordingTaskScheduler workerExecutor = new RecordingTaskScheduler();
        CountingWorker worker = new CountingWorker();
        MemoryScheduleStore scheduleStore = MemoryScheduleStore.create();
        WorkerQueue workerQueue = MemorySubscribableWorkerQueue.create();
        TockRegister register = new StubRegister();
        TockContext context = TockContext.builder()
                .config(Config.builder().register(register).scheduleStore(scheduleStore).workerQueue(workerQueue).build())
                .register(register)
                .master(register.getMaster())
                .scheduleStore(scheduleStore)
                .workerQueue(workerQueue)
                .workerExecutor(workerExecutor)
                .consumerExecutor(java.util.concurrent.Executors.newSingleThreadExecutor())
                .schedulerExecutor(java.util.concurrent.Executors.newSingleThreadScheduledExecutor())
                .timeSource(timeSource)
                .build();

        worker.start(context);

        JobExecution execution = JobExecution.builder()
                .executionId("e1")
                .scheduleId("s1")
                .jobId("j1")
                .workerGroup("g1")
                .nextFireTime(1_500L)
                .build();

        worker.executeJob(execution);
        Assertions.assertEquals(500L, workerExecutor.lastScheduledDelayMs(), "initial delay should match fireTime - currentTime");

        timeSource.set(1_450L);
        workerExecutor.runLastScheduledTask();
        Assertions.assertEquals(50L, workerExecutor.lastScheduledDelayMs(), "worker should reschedule the remaining synchronized time");
        Assertions.assertEquals(0, worker.executedCount(), "job must not execute while synchronized clock is still early");

        timeSource.set(1_500L);
        workerExecutor.runLastScheduledTask();
        Assertions.assertEquals(1, worker.executedCount(), "job should execute exactly once after synchronized time catches up");
    }

    private static final class CountingWorker extends DefaultTockWorker {
        private final AtomicInteger executedCount = new AtomicInteger();

        @Override
        void doExecuteJob(JobExecution jobExecution) {
            executedCount.incrementAndGet();
        }

        private int executedCount() {
            return executedCount.get();
        }
    }

    private static final class AdjustableTimeSource implements com.clmcat.tock.time.TimeSource {
        private final AtomicLong current = new AtomicLong();

        private AdjustableTimeSource(long initial) {
            current.set(initial);
        }

        @Override
        public long currentTimeMillis() {
            return current.get();
        }

        private void set(long value) {
            current.set(value);
        }
    }

    private static final class RecordingTaskScheduler implements TaskScheduler {
        private long lastScheduledDelayMs = -1L;
        private Runnable lastScheduledTask;

        @Override
        public Future<?> schedule(Runnable task, long delay, TimeUnit unit) {
            lastScheduledDelayMs = TimeUnit.MILLISECONDS.convert(delay, unit);
            lastScheduledTask = task;
            return new CompletedFuture();
        }

        @Override
        public Future<?> submit(Runnable task) {
            lastScheduledTask = task;
            return new CompletedFuture();
        }

        @Override
        public long advanceNanos() {
            return 0L;
        }

        @Override
        public void start(TockContext context) {
        }

        @Override
        public void stop() {
        }

        @Override
        public boolean isRunning() {
            return true;
        }

        private long lastScheduledDelayMs() {
            return lastScheduledDelayMs;
        }

        private void runLastScheduledTask() {
            Runnable task = lastScheduledTask;
            Assertions.assertNotNull(task, "expected a scheduled task");
            lastScheduledTask = null;
            task.run();
        }
    }

    private static final class CompletedFuture implements Future<Object> {
        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public Object get() {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            return null;
        }
    }

    private static final class StubRegister implements TockRegister {
        private final TockMaster master = new StubMaster();
        private final TockCurrentNode currentNode = new StubCurrentNode();

        @Override
        public TockMaster getMaster() {
            return master;
        }

        @Override
        public TockCurrentNode getCurrentNode() {
            return currentNode;
        }

        @Override
        public TockNode getNode(String nodeId) {
            return currentNode;
        }

        @Override
        public java.util.List<TockNode> getNods() {
            return Collections.singletonList(currentNode);
        }

        @Override
        public java.util.List<TockNode> getExpiredNodes() {
            return Collections.emptyList();
        }

        @Override
        public void removeNode(String nodeId) {
        }

        @Override
        public boolean setNodeAttributeIfAbsent(String name, Object value) {
            return true;
        }

        @Override
        public <T> T getNodeAttribute(String name, Class<T> type) {
            return null;
        }

        @Override
        public boolean removeNodeAttribute(String name) {
            return true;
        }

        @Override
        public boolean setGroupAttributeIfAbsent(String name, Object value) {
            return true;
        }

        @Override
        public <T> T getGroupAttribute(String name, Class<T> type) {
            return null;
        }

        @Override
        public boolean removeGroupAttribute(String name) {
            return true;
        }

        @Override
        public void removeGroupAttributes(Collection<String> names) {
        }

        @Override
        public void setRuntimeState(String key, String value) {
        }

        @Override
        public String getRuntimeState(String key) {
            return null;
        }

        @Override
        public void start(TockContext context) {
        }

        @Override
        public void stop() {
        }

        @Override
        public boolean isRunning() {
            return true;
        }
    }

    private static final class StubMaster implements TockMaster {
        @Override
        public boolean isMaster() {
            return true;
        }

        @Override
        public void addListener(com.clmcat.tock.registry.MasterListener listener) {
        }

        @Override
        public void removeListener(com.clmcat.tock.registry.MasterListener listener) {
        }

        @Override
        public void start(TockContext context) {
        }

        @Override
        public void stop() {
        }

        @Override
        public boolean isRunning() {
            return true;
        }

        @Override
        public String getMasterName() {
            return "master";
        }
    }

    private static final class StubCurrentNode implements TockCurrentNode {
        @Override
        public void addNodeListener(com.clmcat.tock.registry.NodeListener listener) {
        }

        @Override
        public void removeNodeListener(com.clmcat.tock.registry.NodeListener listener) {
        }

        @Override
        public String getId() {
            return "node";
        }

        @Override
        public NodeStatus getStatus() {
            return NodeStatus.ACTIVE;
        }

        @Override
        public boolean setAttributeIfAbsent(String key, Object value) {
            return true;
        }

        @Override
        public void setAttributeIfAbsent(Map<String, Object> attributes) {
        }

        @Override
        public boolean removeNodeAttributes(String name) {
            return true;
        }

        @Override
        public <T> T getAttribute(String key, Class<T> type) {
            return null;
        }

        @Override
        public <T> Map<String, T> getAttributes(Collection<String> keys, Class<T> type) {
            return Collections.emptyMap();
        }

        @Override
        public java.util.Set<String> getAttributeNamesAll() {
            return Collections.emptySet();
        }

        @Override
        public void clearAttributes() {
        }

        @Override
        public void start(TockContext context) {
        }

        @Override
        public void stop() {
        }

        @Override
        public boolean isRunning() {
            return true;
        }
    }
}
