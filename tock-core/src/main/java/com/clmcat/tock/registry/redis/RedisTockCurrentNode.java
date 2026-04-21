package com.clmcat.tock.registry.redis;

import com.clmcat.tock.TockContext;
import com.clmcat.tock.registry.NodeListener;
import com.clmcat.tock.registry.TockCurrentNode;
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

    private final Set<NodeListener> nodeListeners = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService heartbeatExecutor ;

    private volatile ScheduledFuture<?> heartbeatFuture;
    private volatile TockContext tockContext;

    public RedisTockCurrentNode(String namespace, JedisPool jedisPool, String name) {
        this(namespace, jedisPool, null, name, UUID.randomUUID().toString(), 3000L, 1000L);
    }

    public RedisTockCurrentNode(String namespace, JedisPool jedisPool, Serializer serializer, String name, String nodeId, long leaseTimeoutMs, long heartbeatIntervalMs) {
        super(namespace, jedisPool, serializer, name, nodeId, leaseTimeoutMs, heartbeatIntervalMs, System::currentTimeMillis);
    }

    @Override
    public void addNodeListener(NodeListener listener) {
        nodeListeners.add(listener);
    }

    @Override
    public void removeNodeListener(NodeListener listener) {
        nodeListeners.remove(listener);
    }

    @Override
    public void start(TockContext context) {
        if (!running.compareAndSet(false, true)) return;
        this.tockContext = context;
        writeLease(currentTimeMillis());
        heartbeatExecutor = createScheduler();
        heartbeatFuture = heartbeatExecutor.scheduleWithFixedDelay(this::refreshLease, getHeartbeatIntervalMsInternal(), getHeartbeatIntervalMsInternal(), TimeUnit.MILLISECONDS);
        onRunning();
    }

    @Override
    public void stop() {
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
    }

    @Override
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
        for (NodeListener listener : nodeListeners) {
            try {
                listener.onRunning();
            } catch (Exception e) {
                log.error("NodeListener onRunning error", e);
                throw new RuntimeException(e);
            }
        }
    }

    private void onStopped() {
        for (NodeListener listener : nodeListeners) {
            try {
                listener.onStopped();
            } catch (Exception e) {
                log.error("NodeListener onStopped error", e);
            }
        }
    }

    @Override
    protected long currentTimeMillis() {
        return tockContext == null ? super.currentTimeMillis() : tockContext.currentTimeMillis();
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
