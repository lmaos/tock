package com.clmcat.tock.worker;

import com.clmcat.tock.Config;
import com.clmcat.tock.TockContext;
import com.clmcat.tock.job.DefaultJobRegistry;
import com.clmcat.tock.job.JobContext;
import com.clmcat.tock.job.JobExecutor;
import com.clmcat.tock.money.MemoryManager;
import com.clmcat.tock.registry.memory.MemoryTockRegister;
import com.clmcat.tock.schedule.memory.MemoryScheduleStore;
import com.clmcat.tock.store.JobExecution;
import com.clmcat.tock.time.DefaultTimeSynchronizer;
import com.clmcat.tock.time.TimeProvider;
import com.clmcat.tock.time.TimeSnapshot;
import com.clmcat.tock.time.TimeSynchronizer;
import com.clmcat.tock.worker.memory.MemorySubscribableWorkerQueue;
import com.clmcat.tock.worker.scheduler.TaskScheduler;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

class DefaultTockWorkerSnapshotPropagationTest {

    @Test
    void shouldPropagateSnapshotIntoBusinessThread() throws Exception {
        MutableOffsetTimeProvider timeProvider = new MutableOffsetTimeProvider(1_000L);
        TrackingTimeSynchronizer timeSynchronizer = new TrackingTimeSynchronizer(timeProvider);
        MemoryManager memoryManager = MemoryManager.create();
        MemoryTockRegister register = new MemoryTockRegister("worker-snapshot", memoryManager);
        MemoryScheduleStore scheduleStore = MemoryScheduleStore.create();
        MemorySubscribableWorkerQueue workerQueue = MemorySubscribableWorkerQueue.create();
        DefaultJobRegistry jobRegistry = new DefaultJobRegistry();
        RecordingTaskScheduler workerExecutor = new RecordingTaskScheduler();
        java.util.concurrent.ExecutorService consumerExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
        AtomicReference<TimeSnapshot> workerThreadSnapshotRef = new AtomicReference<>();
        AtomicLong workerThreadCurrentTime = new AtomicLong();
        DefaultTockWorker worker = new SnapshotAwareWorker(workerThreadSnapshotRef, workerThreadCurrentTime);
        CountDownLatch executionLatch = new CountDownLatch(1);
        AtomicReference<JobContext> jobContextRef = new AtomicReference<>();
        AtomicLong jobContextCurrentTime = new AtomicLong();
        AtomicLong jobTimeSourceCurrentTime = new AtomicLong();

        jobRegistry.register("job-snapshot", new JobExecutor() {
            @Override
            public void execute(JobContext context) {
                jobContextRef.set(context);
                jobTimeSourceCurrentTime.set(context.getTimeSource().currentTimeMillis());
                jobContextCurrentTime.set(context.currentTimeMillis());
                executionLatch.countDown();
            }
        });

        TockContext context = TockContext.builder()
                .config(Config.builder()
                        .register(register)
                        .scheduleStore(scheduleStore)
                        .workerQueue(workerQueue)
                        .build())
                .register(register)
                .scheduleStore(scheduleStore)
                .workerQueue(workerQueue)
                .jobRegistry(jobRegistry)
                .workerExecutor(workerExecutor)
                .consumerExecutor(consumerExecutor)
                .timeProvider(timeProvider)
                .timeSource(timeSynchronizer)
                .timeSynchronizer(timeSynchronizer)
                .build();

        try {
            worker.init(context);
            register.start(context);
            worker.start(context);

            long fireTime = context.currentTimeMillis() + 2_000L;
            JobExecution execution = JobExecution.builder()
                    .executionId("execution-snapshot")
                    .scheduleId("schedule-snapshot")
                    .jobId("job-snapshot")
                    .workerGroup("default")
                    .nextFireTime(fireTime)
                    .params(Collections.emptyMap())
                    .build();

            worker.executeJob(execution);

            TimeSnapshot expectedSnapshot = timeSynchronizer.lastBoundSnapshot();
            Assertions.assertNotNull(expectedSnapshot);
            Assertions.assertEquals(1, timeSynchronizer.bindCount());
            Assertions.assertEquals(1, timeSynchronizer.clearCount());
            Assertions.assertNull(timeSynchronizer.currentSnapshot());

            timeProvider.setOffsetMs(5_000L);
            timeSynchronizer.syncNow();

            Thread runner = new Thread(workerExecutor::runLastScheduledTask, "snapshot-runner");
            runner.start();
            runner.join(5_000L);

            Assertions.assertTrue(executionLatch.await(5, TimeUnit.SECONDS));
            Assertions.assertNotNull(jobContextRef.get());
            Assertions.assertSame(expectedSnapshot, workerThreadSnapshotRef.get());
            Assertions.assertTrue(workerThreadCurrentTime.get() > 0L);
            Assertions.assertSame(expectedSnapshot, jobContextRef.get().getTimeSource());
            Assertions.assertTrue(jobContextRef.get().getTimeSource() instanceof TimeSnapshot);
            Assertions.assertTrue(Math.abs(jobContextCurrentTime.get() - jobTimeSourceCurrentTime.get()) <= 1L);
            Assertions.assertEquals(2, timeSynchronizer.bindCount());
            Assertions.assertEquals(2, timeSynchronizer.clearCount());
            Assertions.assertNotSame(timeSynchronizer, jobContextRef.get().getTimeSource());
        } finally {
            worker.stop();
            register.stop();
            consumerExecutor.shutdownNow();
        }
    }

    private static final class SnapshotAwareWorker extends DefaultTockWorker {
        private final AtomicReference<TimeSnapshot> workerThreadSnapshotRef;
        private final AtomicLong workerThreadCurrentTime;

        private SnapshotAwareWorker(AtomicReference<TimeSnapshot> workerThreadSnapshotRef, AtomicLong workerThreadCurrentTime) {
            this.workerThreadSnapshotRef = workerThreadSnapshotRef;
            this.workerThreadCurrentTime = workerThreadCurrentTime;
        }

        @Override
        protected void onExecutionDue(JobExecution jobExecution, long currentTimeMs) {
            workerThreadCurrentTime.set(currentTimeMs);
            workerThreadSnapshotRef.set(context.getTimeSynchronizer() == null ? null : context.getTimeSynchronizer().currentSnapshot());
        }
    }

    private static final class TrackingTimeSynchronizer extends DefaultTimeSynchronizer {
        private final AtomicReference<TimeSnapshot> lastBoundSnapshot = new AtomicReference<>();
        private final AtomicInteger bindCount = new AtomicInteger();
        private final AtomicInteger clearCount = new AtomicInteger();

        private TrackingTimeSynchronizer(TimeProvider timeProvider) {
            super(timeProvider, 100L, 1);
        }

        @Override
        public void bindSnapshot(TimeSnapshot snapshot) {
            if (snapshot != null) {
                lastBoundSnapshot.set(snapshot);
            }
            bindCount.incrementAndGet();
            super.bindSnapshot(snapshot);
        }

        @Override
        public void clearSnapshot() {
            clearCount.incrementAndGet();
            super.clearSnapshot();
        }

        private TimeSnapshot lastBoundSnapshot() {
            return lastBoundSnapshot.get();
        }

        private int bindCount() {
            return bindCount.get();
        }

        private int clearCount() {
            return clearCount.get();
        }
    }

    private static final class RecordingTaskScheduler implements TaskScheduler {
        private Runnable lastScheduledTask;

        @Override
        public Future<?> schedule(Runnable task, long delay, TimeUnit unit) {
            this.lastScheduledTask = task;
            return new CompletedFuture();
        }

        @Override
        public Future<?> submit(Runnable task) {
            this.lastScheduledTask = task;
            return new CompletedFuture();
        }

        @Override
        public void start(TockContext context) {
        }

        @Override
        public void stop() {
        }

        @Override
        public boolean isStarted() {
            return true;
        }

        private void runLastScheduledTask() {
            Assertions.assertNotNull(lastScheduledTask, "expected a scheduled task");
            lastScheduledTask.run();
        }
    }

    private static final class MutableOffsetTimeProvider implements TimeProvider {
        private final AtomicLong offsetMs = new AtomicLong();

        private MutableOffsetTimeProvider(long offsetMs) {
            this.offsetMs.set(offsetMs);
        }

        @Override
        public long currentTimeMillis() {
            return System.currentTimeMillis() + offsetMs.get();
        }

        private void setOffsetMs(long offsetMs) {
            this.offsetMs.set(offsetMs);
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
}
