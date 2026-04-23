package com.clmcat.tock.registry.memory;

import com.clmcat.tock.TockContext;
import com.clmcat.tock.registry.MasterListener;
import com.clmcat.tock.money.MemoryManager;
import com.clmcat.tock.registry.TockRegister;
import com.clmcat.tock.schedule.memory.MemoryScheduleStore;
import com.clmcat.tock.store.MemoryJobStore;
import com.clmcat.tock.time.TimeSource;
import com.clmcat.tock.worker.memory.MemoryPullableWorkerQueue;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class MemoryTockMasterTest {

    MemoryTockMaster tockMaster1;
    MemoryTockMaster tockMaster2;
    MemoryManager memoryManager;

    @BeforeEach
    void init() {
        memoryManager = MemoryManager.create();
        tockMaster1 = new MemoryTockMaster("abc", memoryManager);
        tockMaster2 = new MemoryTockMaster("abc", memoryManager);
    }

    @Test
    void shouldStartSchedulerMaster() {
        tockMaster1.addListener(new MasterListener() {
            @Override
            public void onBecomeMaster() {
                // 观察触发了 tockMaster1
                System.out.println("shouldStartSchedulerWhenBecomeMaster: tockMaster1 成为主机");
            }

            @Override
            public void onLoseMaster() {
                // 观察触发了 tockMaster1
                System.out.println("shouldStartSchedulerWhenBecomeMaster: tockMaster1 失去主机");
            }
        });

        tockMaster2.addListener(new MasterListener() {
            @Override
            public void onBecomeMaster() {
                // 观察触发了 tockMaster2
                System.out.println("shouldStartSchedulerWhenBecomeMaster: tockMaster2 成为主机");
            }

            @Override
            public void onLoseMaster() {
                // 观察触发了 tockMaster2
                System.out.println("shouldStartSchedulerWhenBecomeMaster: tockMaster2 失去主机");
            }
        });

        // 启动两个服务
        tockMaster1.start(null);
        tockMaster2.start(null);
        // 这里可以添加断言，验证 tockMaster1 是主机，tockMaster2 不是主机
        Assertions.assertTrue(tockMaster1.isMaster());
        Assertions.assertFalse(tockMaster2.isMaster());
        // 停止1， 使 2选主
        tockMaster1.stop();
        tockMaster2.selectMaster();

        Assertions.assertFalse(tockMaster1.isMaster());
        Assertions.assertTrue(tockMaster2.isMaster());
        // 停止2
        tockMaster2.stop();
    }

    @Test
    void shouldTakeOverWhenLeaseExpired() throws Exception {
        ManualTimeSource timeSource = new ManualTimeSource(1_000L);
        TockContext context = context(timeSource);

        CountDownLatch loseLatch = new CountDownLatch(1);
        tockMaster1.addListener(new MasterListener() {
            @Override
            public void onBecomeMaster() { }

            @Override
            public void onLoseMaster() {
                loseLatch.countDown();
            }
        });

        setField(tockMaster1, "tockContext", context);
        setField(tockMaster2, "tockContext", context);
        tockMaster1.selectMaster();
        tockMaster2.selectMaster();
        Assertions.assertTrue(tockMaster1.isMaster());
        Assertions.assertFalse(tockMaster2.isMaster());

        timeSource.advance(10_000L);

        tockMaster2.selectMaster();

        Assertions.assertTrue(loseLatch.await(1, TimeUnit.SECONDS));
        Assertions.assertFalse(tockMaster1.isMaster());
        Assertions.assertTrue(tockMaster2.isMaster());
        Assertions.assertSame(tockMaster2, memoryManager.getMasterMap().get("abc"));
    }

    private static TockContext context(TimeSource timeSource) {
        MemoryTockRegister register = new MemoryTockRegister("abc", MemoryManager.create());
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
                    @Override public <T> T getGroupAttribute(String name, Class<T> type) { return null; }
                    @Override public boolean removeGroupAttribute(String name) { return false; }
                    @Override public void removeGroupAttributes(java.util.Collection<String> names) { }
                    @Override public void setRuntimeState(String key, String value) { }
                    @Override public String getRuntimeState(String key) { return null; }
                    @Override public void start(TockContext context) { }
                    @Override public void stop() { }
                    @Override public boolean isStarted() { return true; }
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

        void advance(long delta) {
            current.addAndGet(delta);
        }

        @Override
        public long currentTimeMillis() {
            return current.get();
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
