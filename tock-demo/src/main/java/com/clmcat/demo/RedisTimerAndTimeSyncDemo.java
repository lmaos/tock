package com.clmcat.demo;

import com.clmcat.tock.Config;
import com.clmcat.tock.Tock;
import com.clmcat.tock.registry.redis.RedisTockRegister;
import com.clmcat.tock.schedule.ScheduleConfig;
import com.clmcat.tock.schedule.redis.RedisScheduleStore;
import com.clmcat.tock.time.HealthTimeProvider;
import com.clmcat.tock.time.RedisTimeProvider;
import com.clmcat.tock.worker.redis.RedisSubscribableWorkerQueue;
import com.clmcat.tock.worker.scheduler.TaskSchedulers;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

public class RedisTimerAndTimeSyncDemo {

    static void main() {

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

        String namespace = "namespace-3";

        RedisTockRegister register = new RedisTockRegister(namespace, jedisPool);

        Config config = Config.builder()
                .workerExecutor(TaskSchedulers.highPrecision(namespace + "-test-worker"))
                .register(register)
                .workerQueue(RedisSubscribableWorkerQueue.create(namespace, jedisPool))
                .scheduleStore(RedisScheduleStore.create(namespace, jedisPool))
                .timeProvider(new HealthTimeProvider())
                .build();


        Tock tock = Tock.configure(config);

        tock.start();
        tock.joinGroup("test");
        tock.addSchedule(ScheduleConfig
                .builder()
                .cron("*/1 * * * * ?")
                .jobId("job1")
                .workerGroup("test")
                .scheduleId("schedule1")
                .zoneId("Asia/Shanghai")
                .build());

        tock.registerJob("job1", (ctx)->{
            long l = ctx.currentTimeMillis();
            System.out.println("Job executed at: " + l);
        });
        tock.refreshSchedules();
        tock.sync();

    }
}
