package com.clmcat.tock.registry.redis;

import com.clmcat.tock.TockContext;
import com.clmcat.tock.registry.NodeListener;
import com.clmcat.tock.registry.TockCurrentNode;
import com.clmcat.tock.registry.listener.NodeListeners;
import com.clmcat.tock.serialize.Serializer;
import lombok.extern.slf4j.Slf4j;
import redis.clients.jedis.JedisPool;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis 版当前节点实现。
 * <p>
 * 这个类负责生命周期、心跳和节点监听器；普通节点查询只应暴露 {@link RedisTockNode}。
 * </p>
 */
@Slf4j
public class RedisTockCurrentNode extends RedisTockNode implements TockCurrentNode {

    private final NodeListeners nodeListeners = new NodeListeners(this);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService heartbeatExecutor ;

    private volatile ScheduledFuture<?> heartbeatFuture;
    private volatile TockContext tockContext;

    public RedisTockCurrentNode(String namespace, JedisPool jedisPool, String name) {
        this(namespace, jedisPool, null, name, UUID.randomUUID().toString(), 5000, 1000L);
    }

    public RedisTockCurrentNode(String namespace, JedisPool jedisPool, Serializer serializer, String name, String nodeId, long leaseTimeoutMs, long heartbeatIntervalMs) {
        super(namespace, jedisPool, serializer, name, nodeId, leaseTimeoutMs, heartbeatIntervalMs, System::currentTimeMillis);
    }

    @Override
    public void addNodeListener(NodeListener listener) {
        nodeListeners.addNodeListener(listener);
    }

    @Override
    public void removeNodeListener(NodeListener listener) {
        nodeListeners.removeNodeListener(listener);
    }


    @Override
    protected void onStart() {
        if (!running.compareAndSet(false, true)) return;
        this.tockContext = context;
        try {
            writeLease(currentTimeMillis());
            heartbeatExecutor = createScheduler();
            heartbeatFuture = heartbeatExecutor.scheduleWithFixedDelay(this::refreshLease, getHeartbeatIntervalMsInternal(), getHeartbeatIntervalMsInternal(), TimeUnit.MILLISECONDS);
            onRunning();
        } catch (RuntimeException e) {
            running.set(false);
            if (heartbeatFuture != null) {
                heartbeatFuture.cancel(true);
                heartbeatFuture = null;
            }
            if (heartbeatExecutor != null) {
                heartbeatExecutor.shutdownNow();
                heartbeatExecutor = null;
            }
            markInactive();
            clearAttributes();
            this.tockContext = null;
            throw e;
        }
    }


    @Override
    protected void onStop() {
        if (!running.compareAndSet(true, false)) return;
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(true);
            heartbeatFuture = null;
        }
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
            heartbeatExecutor = null;
        }

        markInactive();
        clearAttributes();
        onStopped();
        this.tockContext = null;
    }


    public boolean isRunning() {
        return running.get();
    }

    private void refreshLease() {
        try {
            writeLease(currentTimeMillis());
        } catch (Exception e) {
            log.error("Redis node heartbeat failed for {}", getId(), e);
        }
    }

    private void onRunning() {
        nodeListeners.onRunning();

    }

    private void onStopped() {
        nodeListeners.onStopped();
    }

    @Override
    protected long currentTimeMillis() {
        TockContext localContext = tockContext;
        return localContext == null ? super.currentTimeMillis() : localContext.currentTimeMillis();
    }

    private ScheduledExecutorService createScheduler() {
        ThreadFactory factory = r -> {
            Thread thread = new Thread(r, "tock-redis-node-heartbeat");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadScheduledExecutor(factory);
    }
}
