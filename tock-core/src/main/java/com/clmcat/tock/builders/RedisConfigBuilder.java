package com.clmcat.tock.builders;

import com.clmcat.tock.Config;
import com.clmcat.tock.registry.redis.RedisTockRegister;
import com.clmcat.tock.schedule.redis.RedisScheduleStore;
import com.clmcat.tock.scheduler.EventDrivenCronScheduler;
import com.clmcat.tock.time.SystemTimeProvider;
import com.clmcat.tock.time.TimeProvider;
import com.clmcat.tock.worker.DefaultTockWorker;
import com.clmcat.tock.worker.memory.MemorySubscribableWorkerQueue;
import com.clmcat.tock.worker.redis.RedisSubscribableWorkerQueue;
import com.clmcat.tock.worker.scheduler.TaskSchedulers;
import redis.clients.jedis.JedisPool;

import java.util.Objects;

/**
 * 本类提供简单构造一个 Config
 */
public class RedisConfigBuilder {

    boolean highPrecisionWorker = false;
    private String namespace;
    private JedisPool jedisPool;
    /**
     * 默认：SystemTimeProvider 直接返回当前系统时间， 可以重新实现  TimeProvider 或 继承 SystemTimeProvider 进行实现。
     *
     * <p>实现  TimeProvider 或 继承 SystemTimeProvider 区别： </p>
     * <p>实现  TimeProvider 默认会进入 DefaultTimeSynchronizer 时间同步策略。 </p>
     * <p>继承 SystemTimeProvider ， 直接由 返回的时间作为当前时间。 </p>
     */
    private TimeProvider timeProvider = new SystemTimeProvider();

    public RedisConfigBuilder(String namespace) {
        this.namespace = namespace;
        Objects.requireNonNull(namespace, "namespace is null");
    }

    public RedisConfigBuilder withJedisPool(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
        return this;
    }

    public RedisConfigBuilder withHighPrecisionWorker(boolean highPrecisionWorker) {
        this.highPrecisionWorker = highPrecisionWorker;
        return this;
    }
    /**
     * 默认：SystemTimeProvider 直接返回当前系统时间， 可以重新实现  TimeProvider 或 继承 SystemTimeProvider 进行实现。
     *
     * <p>实现  TimeProvider 或 继承 SystemTimeProvider 区别： </p>
     * <p>实现  TimeProvider 默认会进入 DefaultTimeSynchronizer 时间同步策略。 </p>
     * <p>继承 SystemTimeProvider ， 直接由 返回的时间作为当前时间。 </p>
     */

    public RedisConfigBuilder withTimeProvider(TimeProvider timeProvider) {
        this.timeProvider = timeProvider;
        return this;
    }

    public Config.ConfigBuilder toConfigBuilder() {

        Objects.requireNonNull(jedisPool, "jedisPool is null (withJedisPool(jedisPool))");
        Objects.requireNonNull(namespace, "namespace is null");

        return Config.builder()
                .scheduleStore(RedisScheduleStore.create(namespace, jedisPool))
                .workerQueue(RedisSubscribableWorkerQueue.create(namespace, jedisPool))
                .register(RedisTockRegister.create(namespace, jedisPool))
                .worker(DefaultTockWorker.create())
                .scheduler(EventDrivenCronScheduler.create())
                .timeProvider(timeProvider)
                .workerExecutor(highPrecisionWorker ? TaskSchedulers.highPrecision(namespace + "-worker")
                        : TaskSchedulers.schedulerExecutor(namespace + "-worker"))
                ;

    }


    public Config build() {
        return toConfigBuilder().build();
    }


    public static RedisConfigBuilder builder(String namespace) {
        return new RedisConfigBuilder(namespace);
    }

}
