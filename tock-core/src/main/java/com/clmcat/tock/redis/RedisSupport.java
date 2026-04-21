package com.clmcat.tock.redis;

import com.clmcat.tock.serialize.Serializer;
import com.clmcat.tock.serialize.JavaSerializer;
import com.clmcat.tock.serialize.VersionedSerializer;
import lombok.extern.slf4j.Slf4j;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Function;

/**
 * Redis 组件的共享支撑类。
 * <p>
 * 只负责三件事：连接、命名空间和对象编解码。
 * 这样主机、节点、队列和调度存储可以保持各自职责单一。
 * </p>
 */
@Slf4j
public abstract class RedisSupport {

    protected final JedisPool jedisPool;
    protected final String namespace;
    protected final Serializer serializer;

    protected RedisSupport(String namespace, JedisPool jedisPool) {
        this(namespace, jedisPool, null);
    }

    protected RedisSupport(String namespace, JedisPool jedisPool, Serializer serializer) {
        this.namespace = normalizeNamespace(namespace);
        this.jedisPool = Objects.requireNonNull(jedisPool, "jedisPool is null");
        this.serializer = serializer == null ? new VersionedSerializer(new JavaSerializer()) : serializer;
    }

    protected String key(String suffix) {
        return namespace + ":" + suffix;
    }

    protected byte[] rawKey(String suffix) {
        return raw(key(suffix));
    }

    protected byte[] raw(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    protected String text(byte[] value) {
        return value == null ? null : new String(value, StandardCharsets.UTF_8);
    }

    protected <T> T withJedis(Function<Jedis, T> action) {
        try (Jedis jedis = jedisPool.getResource()) {
            return action.apply(jedis);
        }
    }

    protected byte[] encode(Object value) {
        if (value == null) return null;
        try {
            return serializer.serialize(value);
        } catch (Exception e) {
            throw new IllegalStateException("Redis encode failed for " + value.getClass().getName(), e);
        }
    }

    protected <T> T decode(byte[] encoded, Class<T> type) {
        if (encoded == null) return null;
        try {
            return serializer.deserialize(encoded, type);
        } catch (Exception e) {
            throw new IllegalStateException("Redis decode failed for " + type.getName(), e);
        }
    }

    private String normalizeNamespace(String namespace) {
        if (namespace == null || namespace.trim().isEmpty()) {
            throw new IllegalArgumentException("namespace must not be blank");
        }
        return namespace.startsWith("tock:") ? namespace : "tock:" + namespace;
    }
}
