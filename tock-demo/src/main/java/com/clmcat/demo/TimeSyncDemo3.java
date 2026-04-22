package com.clmcat.demo;

import com.clmcat.tock.time.DefaultTimeSynchronizer;
import com.clmcat.tock.time.RedisTimeProvider;
import com.clmcat.tock.time.TimeProvider;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TimeSyncDemo3 {

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

        DefaultTimeSynchronizer synchronizer1 = new DefaultTimeSynchronizer(new RedisTimeProvider(jedisPool::getResource), 1000, 5);
        DefaultTimeSynchronizer synchronizer2 = new DefaultTimeSynchronizer(new RedisTimeProvider(jedisPool::getResource), 1000, 5);
        synchronizer1.start(null);
        Thread.sleep(1000);
        synchronizer2.start(null);
        while (true) {
            long l = synchronizer1.currentTimeMillis();
            long l1 = synchronizer2.currentTimeMillis();
            System.out.println(l - l1 + ", t1=" + l + ", t2=" + l1);
            Thread.sleep(1000);
        }

    }
}
