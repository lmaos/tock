package com.clmcat.tock.schedule.redis;

import com.clmcat.tock.redis.RedisSupport;
import com.clmcat.tock.schedule.ScheduleConfig;
import com.clmcat.tock.schedule.ScheduleStore;
import com.clmcat.tock.serialize.Serializer;
import lombok.extern.slf4j.Slf4j;
import redis.clients.jedis.JedisPool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Redis 版调度配置存储。
 * <p>
 * 使用一个 hash 存放所有 ScheduleConfig，再用一个全局版本号标识变更。
 * 变更只在内容真的变化时才递增，避免无意义的调度刷新。
 * </p>
 */
@Slf4j
public class RedisScheduleStore extends RedisSupport implements ScheduleStore {

    private static final String SCHEDULES = "schedules";
    private static final String VERSION = "schedules:version";

    public RedisScheduleStore(String namespace, JedisPool jedisPool) {
        this(namespace, jedisPool, null);
    }

    public RedisScheduleStore(String namespace, JedisPool jedisPool, Serializer serializer) {
        super(namespace, jedisPool, serializer);
    }

    public static RedisScheduleStore create(String namespace, JedisPool jedisPool) {
        return new RedisScheduleStore(namespace, jedisPool);
    }

    @Override
    public void save(ScheduleConfig config) {
        Objects.requireNonNull(config, "config is null");
        byte[] encoded = encode(config);
        withJedis(jedis -> {
            byte[] scheduleKey = rawKey(SCHEDULES);
            byte[] scheduleId = raw(config.getScheduleId());
            byte[] previous = jedis.hget(scheduleKey, scheduleId);
            if (!Arrays.equals(previous, encoded)) {
                jedis.hset(scheduleKey, scheduleId, encoded);
                jedis.incr(versionKey());
            }
            return null;
        });
    }

    @Override
    public void delete(String scheduleId) {
        withJedis(jedis -> {
            long removed = jedis.hdel(rawKey(SCHEDULES), raw(scheduleId));
            if (removed > 0) {
                jedis.incr(versionKey());
            }
            return null;
        });
    }

    @Override
    public ScheduleConfig get(String scheduleId) {
        return withJedis(jedis -> decode(jedis.hget(rawKey(SCHEDULES), raw(scheduleId)), ScheduleConfig.class));
    }

    @Override
    public List<ScheduleConfig> getAll() {
        return withJedis(jedis -> {
            List<byte[]> values = jedis.hvals(rawKey(SCHEDULES));
            List<ScheduleConfig> configs = new ArrayList<>(values.size());
            for (byte[] value : values) {
                ScheduleConfig config = decode(value, ScheduleConfig.class);
                if (config != null) {
                    configs.add(config);
                }
            }
            return configs;
        });
    }

    @Override
    public long getGlobalVersion() {
        return withJedis(jedis -> {
            String value = jedis.get(versionKey());
            if (value == null || value.trim().isEmpty()) return 0L;
            return Long.parseLong(value);
        });
    }
    private String versionKey() {
        return key(VERSION);
    }
}
