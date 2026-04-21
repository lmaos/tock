package com.clmcat.tock.registry.redis;

import com.clmcat.tock.RedisTestSupport;
import com.clmcat.tock.TockContext;
import com.clmcat.tock.registry.TockCurrentNode;
import com.clmcat.tock.registry.TockNode;
import com.clmcat.tock.schedule.memory.MemoryScheduleStore;
import com.clmcat.tock.store.MemoryJobStore;
import com.clmcat.tock.worker.memory.MemoryPullableWorkerQueue;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class RedisTockRegisterTest extends RedisTestSupport {

    @Test
    void shouldExposeRedisTimeAndNodeState() {
        RedisTockRegister register = new RedisTockRegister(namespace, jedisPool, null, 250L, 1000L);
        TockContext context = buildContext(register, MemoryPullableWorkerQueue.create(), MemoryScheduleStore.create(), MemoryJobStore.create());

        register.start(context);
        try {
            long redisTime = register.currentTimeMillis();
            long syncedTime = context.currentTimeMillis();
            Assertions.assertTrue(Math.abs(redisTime - syncedTime) < 200L, "context clock should stay close to Redis time");

            register.getCurrentNode().setAttributeIfAbsent("role", "worker");
            TockNode lookedUpNode = register.getNode(register.getCurrentNode().getId());
            Assertions.assertFalse(lookedUpNode instanceof TockCurrentNode);
            Assertions.assertEquals("worker", lookedUpNode.getAttribute("role", String.class));
            Assertions.assertTrue(register.getNods().stream().anyMatch(n -> n.getId().equals(register.getCurrentNode().getId())));

            register.setGroupAttributeIfAbsent("owner", register.getCurrentNode().getId());
            Assertions.assertEquals(register.getCurrentNode().getId(), register.getGroupAttribute("owner", String.class));

            register.setRuntimeState("phase", "running");
            Assertions.assertEquals("running", register.getRuntimeState("phase"));

            await(() -> register.getCurrentNode().getStatus() == TockNode.NodeStatus.ACTIVE, 1000L, "node should be active after start");
            sleep(350L);
            Assertions.assertEquals(TockNode.NodeStatus.UNKNOWN, register.getCurrentNode().getStatus());
        } finally {
            register.stop();
        }
    }
}
