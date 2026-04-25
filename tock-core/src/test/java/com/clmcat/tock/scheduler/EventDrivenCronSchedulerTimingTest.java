package com.clmcat.tock.scheduler;

import com.clmcat.tock.Config;
import com.clmcat.tock.TockContext;
import com.clmcat.tock.registry.TockCurrentNode;
import com.clmcat.tock.registry.TockMaster;
import com.clmcat.tock.registry.TockNode;
import com.clmcat.tock.registry.TockRegister;
import com.clmcat.tock.schedule.ScheduleConfig;
import com.clmcat.tock.schedule.memory.MemoryScheduleStore;
import com.clmcat.tock.store.JobExecution;
import com.clmcat.tock.worker.WorkerQueue;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

class EventDrivenCronSchedulerTimingTest {

    @Test
    void shouldScheduleNextCronUsingCurrentFireTimeButCurrentDelay() throws Exception {
        EventDrivenCronScheduler scheduler = new EventDrivenCronScheduler();
        CapturingScheduledExecutorService schedulerExecutor = new CapturingScheduledExecutorService();
        MemoryScheduleStore scheduleStore = MemoryScheduleStore.create();
        WorkerQueue workerQueue = new NoOpWorkerQueue();
        TockRegister register = new StubRegister();
        FixedTimeSource timeSource = new FixedTimeSource(9_003L);
        TockContext context = TockContext.builder()
                .config(Config.builder().register(register).scheduleStore(scheduleStore).workerQueue(workerQueue).build())
                .register(register)
                .scheduleStore(scheduleStore)
                .workerQueue(workerQueue)
                .consumerExecutor(new NoOpExecutorService())
                .workerExecutor(new NoOpTaskScheduler())
                .timeSource(timeSource)
                .build();

        scheduler.init(context);
        setField(scheduler, "schedulerExecutor", schedulerExecutor);
        setStarted(scheduler, true);

        ScheduleConfig config = ScheduleConfig.builder()
                .scheduleId("cron-advance")
                .jobId("job")
                .workerGroup("group")
                .cron("*/1 * * * * ?")
                .zoneId("Asia/Shanghai")
                .build();

        try {
            scheduler.scheduleConfig(config, 9_999L, 9_003L);

            Assertions.assertEquals(997L, schedulerExecutor.lastDelayMs,
                    "current logic pulls cron fire times forward by 1s when the remaining delay is still large");
        } finally {
            scheduler.stop();
        }
    }

    private void setStarted(EventDrivenCronScheduler scheduler, boolean started) throws Exception {
        Field field = com.clmcat.tock.Lifecycle.AbstractNoImplLifecycle.class.getDeclaredField("started");
        field.setAccessible(true);
        AtomicBoolean state = (AtomicBoolean) field.get(scheduler);
        state.set(started);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class FixedTimeSource implements com.clmcat.tock.time.TimeSource {
        private final long currentTimeMillis;

        private FixedTimeSource(long currentTimeMillis) {
            this.currentTimeMillis = currentTimeMillis;
        }

        @Override
        public long currentTimeMillis() {
            return currentTimeMillis;
        }
    }

    private static final class NoOpWorkerQueue implements WorkerQueue {
        @Override
        public void push(JobExecution execution, String workerGroup) {
        }
    }

    private static final class StubRegister implements TockRegister {
        private final TockMaster master = new StubMaster();
        private final TockCurrentNode currentNode = new StubCurrentNode();

        @Override
        public String getNamespace() {
            return "";
        }

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
        public List<TockNode> getNods() {
            return Collections.singletonList(currentNode);
        }

        @Override
        public List<TockNode> getExpiredNodes() {
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
        public boolean isStarted() {
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
        public void setAttributeIfAbsent(java.util.Map<String, Object> attributes) {
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
        public <T> java.util.Map<String, T> getAttributes(Collection<String> keys, Class<T> type) {
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
        public long getLeaseTime() {
            return 0;
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

    private static final class CapturingScheduledExecutorService extends AbstractExecutorService implements ScheduledExecutorService {
        private long lastDelayMs = -1L;

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            lastDelayMs = TimeUnit.MILLISECONDS.convert(delay, unit);
            return new CompletedScheduledFuture();
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }

    private static final class CompletedScheduledFuture implements ScheduledFuture<Object> {
        @Override
        public long getDelay(TimeUnit unit) {
            return 0L;
        }

        @Override
        public int compareTo(Delayed o) {
            return 0;
        }

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

    private static final class NoOpExecutorService extends AbstractExecutorService {
        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }

    private static final class NoOpTaskScheduler implements com.clmcat.tock.worker.scheduler.TaskScheduler {
        @Override
        public Future<?> schedule(Runnable task, long delay, TimeUnit unit) {
            return null;
        }

        @Override
        public Future<?> submit(Runnable task) {
            return null;
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
    }
}
