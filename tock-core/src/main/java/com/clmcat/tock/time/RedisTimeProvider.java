package com.clmcat.tock.time;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class RedisTimeProvider implements TimeProvider {

    private Supplier<Jedis> jedisSupplier;
    private Consumer<Jedis> closeSupplier;

    public RedisTimeProvider(Supplier<Jedis> jedisSupplier,  Consumer<Jedis> closeSupplier) {
        this.jedisSupplier = jedisSupplier;
        this.closeSupplier = closeSupplier;
    }

    public RedisTimeProvider(Supplier<Jedis> jedisSupplier) {
        this.jedisSupplier = jedisSupplier;
        this.closeSupplier = jedis -> jedis.close();
    }

    @Override
    public long currentTimeMillis() {
        Jedis jedis = null;
        try {
             jedis = jedisSupplier.get();
            List<String> time = jedis.time();
            long seconds = Long.parseLong(time.get(0));
            long microseconds = Long.parseLong(time.get(1));
            return seconds * 1000 + microseconds / 1000;
        } finally {
            if (jedis != null) {
                closeSupplier.accept(jedis);
            }
        }
    }
}
