package com.clmcat.tock.registry.redis;

import com.clmcat.tock.redis.RedisSupport;
import com.clmcat.tock.registry.TockNode;
import com.clmcat.tock.serialize.Serializer;
import lombok.extern.slf4j.Slf4j;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.*;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * Redis 版普通节点视图。
 * <p>
 * 只暴露 TockNode 能力；生命周期钩子由子类按需覆盖，默认是无操作。
 * 当前节点实现会继承这个类，再补上 start/stop/heartbeat 逻辑。
 * </p>
 */
@Slf4j
public class RedisTockNode extends RedisSupport implements TockNode {

    private static final String NODE_INDEX = "nodes:index";

    protected final String name;
    protected final String nodeId;
    protected final long leaseTimeoutMs;
    protected final long heartbeatIntervalMs;
    protected final LongSupplier timeSupplier;

    public RedisTockNode(String namespace, JedisPool jedisPool, String name) {
        this(namespace, jedisPool, null, name, UUID.randomUUID().toString(), 5000L, 1000L, System::currentTimeMillis);
    }

    public RedisTockNode(String namespace, JedisPool jedisPool, Serializer serializer, String name, String nodeId, long leaseTimeoutMs, long heartbeatIntervalMs, LongSupplier timeSupplier) {
        super(namespace, jedisPool, serializer);
        this.name = name;
        this.nodeId = nodeId;
        this.leaseTimeoutMs = leaseTimeoutMs <= 0 ? 5000L : leaseTimeoutMs;
        this.heartbeatIntervalMs = heartbeatIntervalMs <= 0 ? 1000L : heartbeatIntervalMs;
        this.timeSupplier = timeSupplier == null ? System::currentTimeMillis : timeSupplier;
    }

    @Override
    public String getId() {
        return nodeId;
    }

    @Override
    public NodeStatus getStatus() {
        return withJedis(this::readStatusFromRedis);
    }

    @Override
    public boolean setAttributeIfAbsent(String key, Object value) {
        return withJedis(jedis -> jedis.hsetnx(raw(attrsKey()), raw(key), encode(value)) == 1L);
    }

    @Override
    public void setAttributeIfAbsent(Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) return;
        withJedis(jedis -> {
            Map<byte[], byte[]> encoded = new HashMap<>();
            for (Map.Entry<String, Object> entry : attributes.entrySet()) {
                encoded.put(raw(entry.getKey()), encode(entry.getValue()));
            }
            jedis.hset(raw(attrsKey()), encoded);
            return null;
        });
    }

    @Override
    public boolean removeNodeAttributes(String name) {
        return withJedis(jedis -> jedis.hdel(raw(attrsKey()), raw(name)) > 0);
    }

    @Override
    public <T> T getAttribute(String key, Class<T> type) {
        return withJedis(jedis -> decode(jedis.hget(raw(attrsKey()), raw(key)), type));
    }

    @Override
    public <T> Map<String, T> getAttributes(Collection<String> keys, Class<T> type) {
        if (keys == null || keys.isEmpty()) return Collections.emptyMap();
        return withJedis(jedis -> {
            byte[][] fields = new byte[keys.size()][];
            int fieldIndex = 0;
            for (String key : keys) {
                fields[fieldIndex++] = raw(key);
            }
            List<byte[]> values = jedis.hmget(raw(attrsKey()), fields);
            Map<String, T> result = new LinkedHashMap<>();
            int i = 0;
            for (String key : keys) {
                byte[] value = values.get(i++);
                if (value != null) {
                    result.put(key, decode(value, type));
                }
            }
            return result;
        });
    }

    @Override
    public Set<String> getAttributeNamesAll() {
        return withJedis(jedis -> {
            Set<byte[]> keys = jedis.hkeys(raw(attrsKey()));
            Set<String> names = new LinkedHashSet<>(keys.size());
            for (byte[] key : keys) {
                names.add(text(key));
            }
            return names;
        });
    }

    @Override
    public void clearAttributes() {
        withJedis(jedis -> {
            jedis.del(attrsKey());
            return null;
        });
    }

    @Override
    public long getLeaseTime() {
        return withJedis(jedis -> {
            Double value = jedis.zscore(nodeIndexKey(), nodeId);
            return value == null ? -1 : value.longValue();
        });
    }

    protected void writeLease(long now) {
        withJedis(jedis -> {
            Map<String, String> meta = new HashMap<>();
            meta.put("name", name);
            meta.put("status", NodeStatus.ACTIVE.name());
            meta.put("leaseTime", String.valueOf(now));
            meta.put("leaseTimeoutMs", String.valueOf(leaseTimeoutMs));
            meta.put("heartbeatIntervalMs", String.valueOf(heartbeatIntervalMs));
            jedis.hset(metaKey(), meta);
            jedis.zadd(nodeIndexKey(), now, nodeId);
            return null;
        });
    }

    protected void markInactive() {
        withJedis(jedis -> {
            jedis.hset(metaKey(), "status", NodeStatus.INACTIVE.name());
            jedis.zrem(nodeIndexKey(), nodeId);
            jedis.del(metaKey(), attrsKey());
            return null;
        });
    }

    protected void removeFromRedis() {
        withJedis(jedis -> {
            jedis.zrem(nodeIndexKey(), nodeId);
            jedis.del(metaKey(), attrsKey());
            return null;
        });
    }

    protected NodeStatus readStatusFromRedis(Jedis jedis) {
        Map<String, String> meta = jedis.hgetAll(metaKey());
        if (meta.isEmpty()) {
            return NodeStatus.INACTIVE;
        }
        NodeStatus remoteStatus = parseStatus(meta.get("status"));
        long remoteLease = parseLong(meta.get("leaseTime"), 0L);
        long remoteTimeout = parseLong(meta.get("leaseTimeoutMs"), leaseTimeoutMs);
        if (remoteStatus == NodeStatus.ACTIVE && currentTimeMillis() - remoteLease > remoteTimeout) {
            return NodeStatus.UNKNOWN;
        }
        return remoteStatus;
    }

    protected long currentTimeMillis() {
        return timeSupplier.getAsLong();
    }

    protected String metaKey() {
        return key("node:" + nodeId + ":meta");
    }

    protected String attrsKey() {
        return key("node:" + nodeId + ":attrs");
    }

    protected String nodeIndexKey() {
        return key(NODE_INDEX);
    }

    protected String getNodeIdInternal() {
        return nodeId;
    }

    protected long getLeaseTimeoutMsInternal() {
        return leaseTimeoutMs;
    }

    protected long getHeartbeatIntervalMsInternal() {
        return heartbeatIntervalMs;
    }

    private NodeStatus parseStatus(String value) {
        if (value == null) return NodeStatus.INACTIVE;
        try {
            return NodeStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            return NodeStatus.UNKNOWN;
        }
    }

    private long parseLong(String value, long defaultValue) {
        if (value == null) return defaultValue;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
