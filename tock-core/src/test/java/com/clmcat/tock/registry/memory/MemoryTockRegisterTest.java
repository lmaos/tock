package com.clmcat.tock.registry.memory;

import com.clmcat.tock.TockContext;
import com.clmcat.tock.money.MemoryManager;
import com.clmcat.tock.registry.TockCurrentNode;
import com.clmcat.tock.registry.TockRegister;
import com.clmcat.tock.schedule.memory.MemoryScheduleStore;
import com.clmcat.tock.store.MemoryJobStore;
import com.clmcat.tock.time.TimeSource;
import com.clmcat.tock.worker.memory.MemoryPullableWorkerQueue;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

public class MemoryTockRegisterTest {

    @Test
    void shouldDelegateCurrentTimeToContextTimeSource() {
        ManualTimeSource timeSource = new ManualTimeSource(12_345L);
        MemoryTockRegister register = new MemoryTockRegister("reg-a", MemoryManager.create());
        TockContext context = context(timeSource, register);
        register.start(context);

        Assertions.assertEquals(12_345L, context.currentTimeMillis());
        timeSource.set(67_890L);
        Assertions.assertEquals(67_890L, context.currentTimeMillis());
        Assertions.assertFalse(register.getNode(register.getCurrentNode().getId()) instanceof TockCurrentNode);

        register.stop();
    }

    private static TockContext context(TimeSource timeSource, MemoryTockRegister register) {
        return TockContext.builder()
                .register(new TockRegister() {
                    @Override
                    public String getNamespace() {
                        return "";
                    }

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
                    @Override public void setGroupAttribute(String name, Object value) { }
                    @Override public <T> T getGroupAttribute(String name, Class<T> type) { return null; }
                    @Override public boolean removeGroupAttribute(String name) { return false; }
                    @Override public void removeGroupAttributes(java.util.Collection<String> names) { }
                    @Override public void setRuntimeState(String key, String value) { }
                    @Override public String getRuntimeState(String key) { return null; }
                    @Override public void start(TockContext context) { }
                    @Override public void stop() { }
                    @Override public boolean isStarted() { return true; }
                })

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
