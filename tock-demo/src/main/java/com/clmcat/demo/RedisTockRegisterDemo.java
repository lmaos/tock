package com.clmcat.demo;

import com.clmcat.tock.registry.redis.RedisTockRegister;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

public class RedisTockRegisterDemo {
    static void main() throws InterruptedException {
        JedisPoolConfig jedisPoolConfig = new JedisPoolConfig();
        jedisPoolConfig.setMaxIdle(10);
        jedisPoolConfig.setMaxTotal(10);
        jedisPoolConfig.setMinIdle(10);
        jedisPoolConfig.setTestOnBorrow(true);
        jedisPoolConfig.setTestOnReturn(true);
        jedisPoolConfig.setTestWhileIdle(true);
        jedisPoolConfig.setBlockWhenExhausted(true);
        jedisPoolConfig.setNumTestsPerEvictionRun(10);

        JedisPool jedisPool = new JedisPool(jedisPoolConfig, "127.0.0.1", 6379);

        String namespace = "namespace";

        RedisTockRegister register = new RedisTockRegister(namespace, jedisPool);

        while (true) {

            System.out.println(register.currentTimeMillis());
            Thread.sleep(1000);
        }


    }
}
