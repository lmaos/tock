package com.clmcat.tock.registry.redis;

import com.clmcat.tock.TockContext;
import com.clmcat.tock.registry.TockMaster;
import com.clmcat.tock.registry.TockCurrentNode;
import com.clmcat.tock.registry.TockNode;
import com.clmcat.tock.registry.TockRegister;
import com.clmcat.tock.redis.RedisSupport;
import com.clmcat.tock.serialize.Serializer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Redis 版注册中心。
 * <p>
 * 这里把 master、node、runtime state 和 group state 都放在 Redis 中。
 * 当前节点和 master 使用心跳保持租约，时间源则直接取 Redis TIME，
 * 这样能保证整个集群读取到的是同一类时间基准。
 * </p>
 */
@Slf4j
public class RedisTockRegister extends RedisSupport implements TockRegister {

    private static final String RUNTIME_STATES = "runtime:states";
    private static final String GROUP_ATTRS = "group:attrs";
    private static final String NODE_INDEX = "nodes:index";
@Getter
    private final String namespace;
    private final boolean ownPool;
    private final long leaseTimeoutMs;
    private final long heartbeatIntervalMs;
    private final RedisTockMaster master;
    private final RedisTockCurrentNode currentNode;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object timeJedisMonitor = new Object();
    private volatile TockContext context;
    private volatile Jedis timeJedis;

    public RedisTockRegister(String namespace, JedisPool jedisPool) {
        this(namespace, jedisPool, null, 3000L, 1000L, false);
    }

    public RedisTockRegister(String namespace, String host, int port) {
        this(namespace, new JedisPool(host, port), null, 3000L, 1000L, true);
    }

    public RedisTockRegister(String namespace, JedisPool jedisPool, Serializer serializer, long leaseTimeoutMs, long heartbeatIntervalMs) {
        this(namespace, jedisPool, serializer, leaseTimeoutMs, heartbeatIntervalMs, false);
    }

    public RedisTockRegister(String namespace, JedisPool jedisPool, Serializer serializer, long leaseTimeoutMs, long heartbeatIntervalMs, boolean ownPool) {
        super(prefix(namespace), jedisPool, serializer);
        this.namespace = namespace;
        this.ownPool = ownPool;
        this.leaseTimeoutMs = leaseTimeoutMs <= 0 ? 3000L : leaseTimeoutMs;
        this.heartbeatIntervalMs = heartbeatIntervalMs <= 0 ? 1000L : heartbeatIntervalMs;
        this.currentNode = new RedisTockCurrentNode(this.namespace, jedisPool, serializer, namespace, UUID.randomUUID().toString(), this.leaseTimeoutMs, this.heartbeatIntervalMs);
        this.master = new RedisTockMaster(this.namespace, jedisPool, serializer, namespace, this.leaseTimeoutMs, this.heartbeatIntervalMs);
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
        if (currentNode.getId().equals(nodeId)) {
            return new RedisTockNode(namespace, jedisPool, serializer, namespace, nodeId, leaseTimeoutMs, heartbeatIntervalMs, context::currentTimeMillis);
        }
        return new RedisTockNode(namespace, jedisPool, serializer, namespace, nodeId, leaseTimeoutMs, heartbeatIntervalMs, context::currentTimeMillis);
    }

    @Override
    public List<TockNode> getNods() {
        return withJedis(jedis -> {
            List<String> nodeIds = new ArrayList<>(jedis.zrange(nodeIndexKey(), 0, -1));
            List<TockNode> nodes = new ArrayList<>(nodeIds.size());
            for (String nodeId : nodeIds) {
                nodes.add(getNode(nodeId));
            }
            return nodes;
        });
    }

    @Override
    public List<TockNode> getExpiredNodes() {
        long now = context.currentTimeMillis();
        long cutoff = now - leaseTimeoutMs;
        return withJedis(jedis -> {
            List<String> nodeIds = new ArrayList<>(jedis.zrangeByScore(nodeIndexKey(), 0, cutoff));
            if (nodeIds.isEmpty()) {
                return Collections.emptyList();
            }
            return nodeIds.stream().map(this::getNode).collect(Collectors.toList());
        });
    }

    @Override
    public void removeNode(String nodeId) {
        if (currentNode.getId().equals(nodeId) && currentNode.isRunning()) {
            currentNode.stop();
            return;
        }
        withJedis(jedis -> {
            jedis.zrem(nodeIndexKey(), nodeId);
            jedis.del(nodeMetaKey(nodeId));
            jedis.del(nodeAttrKey(nodeId));
            return null;
        });
    }

    @Override
    public boolean setNodeAttributeIfAbsent(String name, Object value) {
        return currentNode.setAttributeIfAbsent(name, value);
    }

    @Override
    public <T> T getNodeAttribute(String name, Class<T> type) {
        return currentNode.getAttribute(name, type);
    }

    @Override
    public boolean removeNodeAttribute(String name) {
        return currentNode.removeNodeAttributes(name);
    }

    @Override
    public boolean setGroupAttributeIfAbsent(String name, Object value) {
        return withJedis(jedis -> jedis.hsetnx(raw(groupAttrKey()), raw(name), encode(value)) == 1L);
    }

    @Override
    public <T> T getGroupAttribute(String name, Class<T> type) {
        return withJedis(jedis -> decode(jedis.hget(raw(groupAttrKey()), raw(name)), type));
    }

    @Override
    public boolean removeGroupAttribute(String name) {
        return withJedis(jedis -> jedis.hdel(raw(groupAttrKey()), raw(name)) > 0);
    }

    @Override
    public void removeGroupAttributes(Collection<String> names) {
        if (names == null || names.isEmpty()) return;
        withJedis(jedis -> {
            byte[][] fields = new byte[names.size()][];
            int i = 0;
            for (String name : names) {
                fields[i++] = raw(name);
            }
            jedis.hdel(raw(groupAttrKey()), fields);
            return null;
        });
    }

    @Override
    public void setRuntimeState(String key, String value) {
        withJedis(jedis -> {
            jedis.hset(runtimeKey(), key, value);
            return null;
        });
    }

    @Override
    public String getRuntimeState(String key) {
        return withJedis(jedis -> jedis.hget(runtimeKey(), key));
    }

    @Override
    public void start(TockContext context) {
        if (!running.compareAndSet(false, true)) return;
        this.context = context;
        // 先启动节点，再启动主机，这样主机选举能直接拿到当前节点 ID。
        currentNode.start(context);
        master.start(context);
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        master.stop();
        currentNode.stop();
        closeTimeJedis();
        if (ownPool) {
            jedisPool.close();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }


    private Jedis ensureTimeJedis() {
        if (timeJedis == null) {
            timeJedis = jedisPool.getResource();
        }
        return timeJedis;
    }

    private void closeTimeJedis() {
        Jedis jedis = timeJedis;
        timeJedis = null;
        if (jedis != null) {
            try {
                jedis.close();
            } catch (RuntimeException e) {
                log.warn("Closing Redis TIME connection failed", e);
            }
        }
    }

    private String runtimeKey() {
        return key(RUNTIME_STATES);
    }

    private String groupAttrKey() {
        return key(GROUP_ATTRS);
    }

    private String nodeIndexKey() {
        return key(NODE_INDEX);
    }

    private String nodeMetaKey(String nodeId) {
        return key("node:" + nodeId + ":meta");
    }

    private String nodeAttrKey(String nodeId) {
        return key("node:" + nodeId + ":attrs");
    }

    private static String prefix(String name) {
        return "tock:redis:" + name;
    }
}
