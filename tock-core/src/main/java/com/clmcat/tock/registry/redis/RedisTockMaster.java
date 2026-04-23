package com.clmcat.tock.registry.redis;

import com.clmcat.tock.TockContext;
import com.clmcat.tock.registry.MasterListener;
import com.clmcat.tock.registry.TockMaster;
import com.clmcat.tock.redis.RedisSupport;
import com.clmcat.tock.registry.listener.MasterListeners;
import com.clmcat.tock.serialize.Serializer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis 版主节点选举。
 * <p>
 * 选主只使用 Redis 原子锁和 lease 续租，不做忙轮询。
 * 通过 JVM 定时唤醒维持租约，失败后立即释放主身份。
 * </p>
 */
@Slf4j
public class RedisTockMaster extends RedisSupport implements TockMaster {

    private static final String MASTER_SUFFIX = "master";
    private static final String MASTER_KEY_PREFIX = "master:";

    private final String masterName;
    private final long leaseTimeoutMs;
    private final long heartbeatIntervalMs;
    private final AtomicBoolean master = new AtomicBoolean(false);
    private final MasterListeners listeners = new MasterListeners(this);
    private ScheduledExecutorService task;

    private volatile ScheduledFuture<?> startFuture;
    private volatile TockContext tockContext;

    public RedisTockMaster(String namespace, JedisPool jedisPool, String masterName, long leaseTimeoutMs, long heartbeatIntervalMs) {
        this(namespace, jedisPool, null, masterName, leaseTimeoutMs, heartbeatIntervalMs);
    }

    public RedisTockMaster(String namespace, JedisPool jedisPool, Serializer serializer, String masterName, long leaseTimeoutMs, long heartbeatIntervalMs) {
        super(namespace, jedisPool, serializer);
        this.masterName = masterName;
        this.leaseTimeoutMs = leaseTimeoutMs <= 0 ? 3000L : leaseTimeoutMs;
        this.heartbeatIntervalMs = heartbeatIntervalMs <= 0 ? 1000L : heartbeatIntervalMs;
    }

    @Override
    public boolean isMaster() {
        return master.get();
    }

    @Override
    public void addListener(MasterListener listener) {
        listeners.addListener(listener);
    }

    @Override
    public void removeListener(MasterListener listener) {
        listeners.removeListener(listener);
    }

    @Override
    public void start(TockContext context) {
        if (startFuture != null) return;
        this.tockContext = context;
        selectMaster();
        task = createScheduler();
        startFuture = task.scheduleWithFixedDelay(this::selectMaster, heartbeatIntervalMs, heartbeatIntervalMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        if (startFuture == null) return;
        startFuture.cancel(true);
        startFuture = null;
        if (task != null) {
            task.shutdownNow();
            task = null;
        }
        releaseMaster();
    }

    @Override
    public String getMasterName() {
        return masterName;
    }


    public boolean isRunning() {
        return startFuture != null && !startFuture.isCancelled();
    }

    public void selectMaster() {
        final String ownerId = currentNodeId();
        boolean acquiredOrRenewed = withJedis(jedis -> {
            String masterKey = masterKey();
            if (master.get()) {
                Object result = jedis.eval(
                        "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end",
                        java.util.Collections.singletonList(masterKey),
                        java.util.Arrays.asList(ownerId, String.valueOf(leaseTimeoutMs)));
                return toLong(result) > 0;
            }
            return "OK".equals(jedis.set(masterKey, ownerId, SetParams.setParams().nx().px(leaseTimeoutMs)));
        });

        if (acquiredOrRenewed) {
            if (master.compareAndSet(false, true)) {
                onBecomeMaster();
            }
            return;
        }

        if (master.compareAndSet(true, false)) {
            onLoseMaster();
        }
    }

    private void releaseMaster() {
        final String ownerId = currentNodeId();
        if (master.compareAndSet(true, false)) {
            withJedis(jedis -> {
                jedis.eval(
                        "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
                        java.util.Collections.singletonList(masterKey()),
                        java.util.Collections.singletonList(ownerId));
                return null;
            });
            onLoseMaster();
        }
    }

    private void onBecomeMaster() {
        LinkedList<MasterListener> activeListeners = new LinkedList<>(listeners.getListeners());
        onBecomeMaster(activeListeners, 0);
    }

    private void onLoseMaster() {
        LinkedList<MasterListener> activeListeners = new LinkedList<>(listeners.getListeners());
        onLoseMaster(activeListeners, 0);
    }

    private void onBecomeMaster(LinkedList<MasterListener> activeListeners, int retryCount) {
        if (activeListeners.isEmpty() || !isMaster()) return;
        Iterator<MasterListener> iterator = activeListeners.iterator();
        while (iterator.hasNext()) {
            MasterListener listener = iterator.next();
            try {
                if (isMaster()) {
                    listener.onBecomeMaster();
                }
                iterator.remove();
            } catch (Exception e) {
                log.error("onBecomeMaster error, count:{}", retryCount, e);
            }
        }
        if (!activeListeners.isEmpty()) {
            task.schedule(() -> onBecomeMaster(activeListeners, retryCount + 1), 1, TimeUnit.SECONDS);
        }
    }

    private void onLoseMaster(LinkedList<MasterListener> activeListeners, int retryCount) {
        if (activeListeners.isEmpty() || isMaster()) return;
        Iterator<MasterListener> iterator = activeListeners.iterator();
        while (iterator.hasNext()) {
            MasterListener listener = iterator.next();
            try {
                if (!isMaster()) {
                    listener.onLoseMaster();
                }
                iterator.remove();
            } catch (Exception e) {
                log.error("onLoseMaster error, count:{}", retryCount, e);
            }
        }
        if (!activeListeners.isEmpty()) {
            task.schedule(() -> onLoseMaster(activeListeners, retryCount + 1), 1, TimeUnit.SECONDS);
        }
    }

    private String currentNodeId() {
        if (tockContext == null || tockContext.getRegister() == null || tockContext.getRegister().getCurrentNode() == null) {
            throw new IllegalStateException("TockContext with current node is required before master election");
        }
        return tockContext.getRegister().getCurrentNode().getId();
    }

    private long toLong(Object value) {
        if (value instanceof Long) return (Long) value;
        if (value instanceof Integer) return ((Integer) value).longValue();
        if (value instanceof String) return Long.parseLong((String) value);
        return 0L;
    }

    private String masterKey() {
        return key(MASTER_KEY_PREFIX + masterName);
    }

    private ScheduledExecutorService createScheduler() {
        ThreadFactory factory = r -> {
            Thread thread = new Thread(r, "tock-redis-master");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadScheduledExecutor(factory);
    }
}
