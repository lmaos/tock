package com.clmcat.tock.registry.memory;

import com.clmcat.tock.TockContext;
import com.clmcat.tock.money.MemoryManager;
import com.clmcat.tock.registry.TockRegister;
import com.clmcat.tock.schedule.memory.MemoryScheduleStore;
import com.clmcat.tock.store.MemoryJobStore;
import com.clmcat.tock.time.TimeSource;
import com.clmcat.tock.worker.memory.MemoryPullableWorkerQueue;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicLong;

public class MemoryTockNodeTest {

    @Test
    void shouldReportUnknownWhenLeaseExpires() throws Exception {
        ManualTimeSource timeSource = new ManualTimeSource(1_000L);
        MemoryTockNode node = new MemoryTockNode("node-a", MemoryManager.create());
        node.start(context(timeSource));
        timeSource.set(1_000L + 10_001L);

        Assertions.assertEquals(MemoryTockNode.NodeStatus.UNKNOWN, node.getStatus());
        node.stop();
    }

    @Test
    void shouldBecomeInactiveAfterStop() {
        MemoryTockNode node = new MemoryTockNode("node-a", MemoryManager.create());

        node.start(null);
        Assertions.assertTrue(node.isRunning());
        node.stop();
        Assertions.assertFalse(node.isRunning());
        Assertions.assertEquals(MemoryTockNode.NodeStatus.INACTIVE, node.getStatus());
    }

    @Test
    void shouldUseSyncedClockForLeaseInitialization() throws Exception {
        ManualTimeSource timeSource = new ManualTimeSource(42_000L);
        MemoryTockNode node = new MemoryTockNode("node-a", MemoryManager.create());
        node.start(context(timeSource));

        Field field = MemoryTockNode.class.getDeclaredField("leaseTime");
        field.setAccessible(true);
        Assertions.assertEquals(42_000L, field.getLong(node));

        node.stop();
    }

    private static TockContext context(TimeSource timeSource) {
        MemoryTockRegister register = new MemoryTockRegister("node-a", MemoryManager.create());
        return TockContext.builder()
                .register(new TockRegister() {
                    @Override public com.clmcat.tock.registry.TockMaster getMaster() { return register.getMaster(); }
                    @Override public com.clmcat.tock.registry.TockCurrentNode getCurrentNode() { return register.getCurrentNode(); }
                    @Override public com.clmcat.tock.registry.TockNode getNode(String nodeId) { return null; }
                    @Override public java.util.List<com.clmcat.tock.registry.TockNode> getNods() { return java.util.Collections.emptyList(); }
                    @Override public java.util.List<com.clmcat.tock.registry.TockNode> getExpiredNodes() { return java.util.Collections.emptyList(); }
                    @Override public void removeNode(String nodeId) { }
                    @Override public boolean setNodeAttributeIfAbsent(String name, Object value) { return false; }
                    @Override public <T> T getNodeAttribute(String name, Class<T> type) { return null; }
                    @Override public boolean removeNodeAttribute(String name) { return false; }
                    @Override public boolean setGroupAttributeIfAbsent(String name, Object value) { return false; }
                    @Override public <T> T getGroupAttribute(String name, Class<T> type) { return null; }
                    @Override public boolean removeGroupAttribute(String name) { return false; }
                    @Override public void removeGroupAttributes(java.util.Collection<String> names) { }
                    @Override public void setRuntimeState(String key, String value) { }
                    @Override public String getRuntimeState(String key) { return null; }
                    @Override public void start(TockContext context) { }
                    @Override public void stop() { }
                    @Override public boolean isRunning() { return true; }
                })
                .master(register.getMaster())
                .scheduleStore(MemoryScheduleStore.create())
                .jobStore(MemoryJobStore.create())
                .workerQueue(MemoryPullableWorkerQueue.create())
                .timeSource(timeSource)
                .build();
    }

    private static final class ManualTimeSource implements TimeSource {
        private final AtomicLong current;

        private ManualTimeSource(long initial) {
            this.current = new AtomicLong(initial);
        }

        void set(long value) {
            current.set(value);
        }

        @Override
        public long currentTimeMillis() {
            return current.get();
        }
    }
}
