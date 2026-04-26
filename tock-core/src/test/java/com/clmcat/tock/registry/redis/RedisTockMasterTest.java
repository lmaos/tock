package com.clmcat.tock.registry.redis;

import com.clmcat.tock.RedisTestSupport;
import com.clmcat.tock.TockContext;
import com.clmcat.tock.registry.MasterListener;
import com.clmcat.tock.schedule.memory.MemoryScheduleStore;
import com.clmcat.tock.store.MemoryJobStore;
import com.clmcat.tock.worker.memory.MemoryPullableWorkerQueue;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class RedisTockMasterTest extends RedisTestSupport {

    @Test
    void shouldTransferMasterBetweenRegisters() throws Exception {
        RedisTockRegister register1 = new RedisTockRegister(namespace, jedisPool, null, 1000L, 100L);
        RedisTockRegister register2 = new RedisTockRegister(namespace, jedisPool, null, 1000L, 100L);
        TockContext context1 = buildContext(register1, MemoryPullableWorkerQueue.create(), MemoryScheduleStore.create(), MemoryJobStore.create());
        TockContext context2 = buildContext(register2, MemoryPullableWorkerQueue.create(), MemoryScheduleStore.create(), MemoryJobStore.create());

        CountDownLatch loseLatch = new CountDownLatch(1);
        register1.getMaster().addListener(new MasterListener() {
            @Override
            public void onBecomeMaster() {
            }

            @Override
            public void onLoseMaster() {
                loseLatch.countDown();
            }
        });
        register2.getMaster().addListener(new MasterListener() {
            @Override
            public void onBecomeMaster() {
            }

            @Override
            public void onLoseMaster() {
                loseLatch.countDown();
            }
        });

        register1.start(context1);
        register2.start(context2);
        try {
            await(() -> register1.getMaster().isMaster() ^ register2.getMaster().isMaster(), 1500L, "exactly one register should be master");
            RedisTockRegister masterRegister = register1.getMaster().isMaster() ? register1 : register2;
            RedisTockRegister standbyRegister = masterRegister == register1 ? register2 : register1;

            masterRegister.getMaster().stop();
            Assertions.assertTrue(loseLatch.await(1, TimeUnit.SECONDS));

            ((RedisTockMaster) standbyRegister.getMaster()).selectMaster();
            Assertions.assertTrue(standbyRegister.getMaster().isMaster());
            Assertions.assertFalse(masterRegister.getMaster().isMaster());
        } finally {
            register1.stop();
            register2.stop();
        }
    }

    @Test
    void shouldRetryMasterListenerFailuresWithoutNpe() throws Exception {
        RedisTockRegister register = new RedisTockRegister(namespace, jedisPool, null, 1000L, 100L);
        TockContext context = buildContext(register, MemoryPullableWorkerQueue.create(), MemoryScheduleStore.create(), MemoryJobStore.create());
        AtomicInteger becomeCalls = new AtomicInteger();
        CountDownLatch successLatch = new CountDownLatch(1);

        register.getMaster().addListener(new MasterListener() {
            @Override
            public void onBecomeMaster() {
                if (becomeCalls.getAndIncrement() == 0) {
                    throw new IllegalStateException("simulated master listener failure");
                }
                successLatch.countDown();
            }

            @Override
            public void onLoseMaster() {
            }
        });

        register.start(context);
        try {
            Assertions.assertTrue(successLatch.await(3, TimeUnit.SECONDS));
            Assertions.assertTrue(register.getMaster().isMaster());
            Assertions.assertTrue(becomeCalls.get() >= 2);
        } finally {
            register.stop();
        }
    }
}
