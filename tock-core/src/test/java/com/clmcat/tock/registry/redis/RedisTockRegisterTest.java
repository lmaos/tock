package com.clmcat.tock.registry.redis;

import com.clmcat.tock.RedisTestSupport;
import com.clmcat.tock.TockContext;
import com.clmcat.tock.Lifecycle;
import com.clmcat.tock.registry.TockCurrentNode;
import com.clmcat.tock.registry.TockNode;
import com.clmcat.tock.time.DefaultTimeSynchronizer;
import com.clmcat.tock.time.RedisTimeProvider;
import com.clmcat.tock.schedule.memory.MemoryScheduleStore;
import com.clmcat.tock.store.MemoryJobStore;
import com.clmcat.tock.worker.memory.MemoryPullableWorkerQueue;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

public class RedisTockRegisterTest extends RedisTestSupport {

    @Test
    void shouldExposeRedisTimeAndNodeState() {
        RedisTockRegister register = new RedisTockRegister(namespace, jedisPool, null, 250L, 1000L);
        MemoryPullableWorkerQueue workerQueue = MemoryPullableWorkerQueue.create();
        MemoryScheduleStore scheduleStore = MemoryScheduleStore.create();
        MemoryJobStore jobStore = MemoryJobStore.create();
        TockContext context = TockContext.builder()
                .config(com.clmcat.tock.Config.builder()
                        .register(register)
                        .scheduleStore(scheduleStore)
                        .workerQueue(workerQueue)
                        .build())
                .register(register)
                .scheduleStore(scheduleStore)
                .jobStore(jobStore)
                .workerQueue(workerQueue)
                .consumerExecutor(consumerExecutor)
                .workerExecutor(workerExecutor)
                .timeSource(new DefaultTimeSynchronizer(new RedisTimeProvider(jedisPool::getResource), 250L, 3))
                .build();

        Assertions.assertTrue(register instanceof Lifecycle);
        Assertions.assertTrue(register.getMaster() instanceof Lifecycle);
        Assertions.assertTrue(register.getCurrentNode() instanceof Lifecycle);
        Assertions.assertDoesNotThrow(() -> register.getNode("node-before-start"));
        Assertions.assertTrue(register.getExpiredNodes().isEmpty());
        ((Lifecycle) context.getTimeSource()).start(context);
        register.start(context);
        try {
            Assertions.assertTrue(register.isStarted());
            Assertions.assertTrue(((Lifecycle) register.getMaster()).isStarted());
            Assertions.assertTrue(((Lifecycle) register.getCurrentNode()).isStarted());

            long redisTime = redisTimeMillis();
            long syncedTime = context.currentTimeMillis();
            Assertions.assertTrue(Math.abs(redisTime - syncedTime) < 200L, "context clock should stay close to Redis time");

            register.getCurrentNode().setAttributeIfAbsent("role", "worker");
            TockNode lookedUpNode = register.getNode(register.getCurrentNode().getId());
            Assertions.assertFalse(lookedUpNode instanceof TockCurrentNode);
            Assertions.assertEquals("worker", lookedUpNode.getAttribute("role", String.class));

            register.setGroupAttributeIfAbsent("owner", register.getCurrentNode().getId());
            Assertions.assertEquals(register.getCurrentNode().getId(), register.getGroupAttribute("owner", String.class));

            register.setRuntimeState("phase", "running");
            Assertions.assertEquals("running", register.getRuntimeState("phase"));

            await(() -> register.getCurrentNode().getStatus() == TockNode.NodeStatus.ACTIVE, 1000L, "node should be active after start");
            sleep(350L);
            Assertions.assertEquals(TockNode.NodeStatus.UNKNOWN, register.getCurrentNode().getStatus());
        } finally {
            register.stop();
            ((Lifecycle) context.getTimeSource()).stop();
            Assertions.assertFalse(register.isStarted());
            Assertions.assertFalse(((Lifecycle) register.getMaster()).isStarted());
            Assertions.assertFalse(((Lifecycle) register.getCurrentNode()).isStarted());
        }
    }

    private long redisTimeMillis() {
        try (redis.clients.jedis.Jedis jedis = jedisPool.getResource()) {
            List<String> time = jedis.time();
            long seconds = Long.parseLong(time.get(0));
            long micros = Long.parseLong(time.get(1));
            return seconds * 1000L + micros / 1000L;
        }
    }
}
