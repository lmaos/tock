package com.clmcat.tock.worker.redis;

import com.clmcat.tock.TockContext;
import com.clmcat.tock.TockContextAware;
import com.clmcat.tock.redis.RedisSupport;
import com.clmcat.tock.store.JobExecution;
import com.clmcat.tock.worker.SubscribableWorkerQueue;
import com.clmcat.tock.worker.WorkerQueue;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import redis.clients.jedis.JedisPool;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Redis 列表版任务队列。
 * <p>
 * 推送端只做 RPUSH，消费端用少量分片线程做 BLPOP。
 * 每个分片负责一组 workerGroup，避免每个组单独占用一个线程。
 * </p>
 */
@Slf4j
public class RedisSubscribableWorkerQueue extends RedisSupport implements SubscribableWorkerQueue {

    private static final int DEFAULT_SHARD_COUNT = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
    private static final int BLPOP_TIMEOUT_SECONDS = 1;

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer<JobExecution>>> subscribers = new ConcurrentHashMap<>();
    private DispatcherShard[] shards;

    private final int shardCount;

    public RedisSubscribableWorkerQueue(String namespace, JedisPool jedisPool) {
        this(namespace, jedisPool, DEFAULT_SHARD_COUNT);
    }

    public RedisSubscribableWorkerQueue(String namespace, JedisPool jedisPool, int shardCount) {
        super(namespace, jedisPool);
        this.shardCount = shardCount;
    }

    public static @NonNull WorkerQueue create(String namespace, JedisPool jedisPool) {
        return new RedisSubscribableWorkerQueue(namespace, jedisPool);
    }

    @Override
    protected void onStart() {
        ensureStarted();
    }

    @Override
    protected void onStop() {
        if (shards != null) {
            for (DispatcherShard shard : shards) {
                shard.shutdown();
            }
            shards = null;
        }
    }


    @Override
    public void push(JobExecution execution, String workerGroup) {
        withJedis(jedis -> {
            jedis.rpush(raw(queueKey(workerGroup)), encode(execution));
            return null;
        });
    }

    @Override
    public void subscribe(String workerGroup, Consumer<JobExecution> consumer) {
        Objects.requireNonNull(consumer, "consumer is null");
        if (context == null || context.getConsumerExecutor() == null) {
            throw new IllegalStateException("TockContext with consumerExecutor must be set before subscribe");
        }
        subscribers.computeIfAbsent(workerGroup, k -> new CopyOnWriteArrayList<>()).add(consumer);
        ensureStarted();
        shardFor(workerGroup).subscribe(workerGroup);
    }

    @Override
    public void unsubscribe(String workerGroup) {
        subscribers.remove(workerGroup);
        if (shards == null) {
            return;
        }
        shardFor(workerGroup).unsubscribe(workerGroup);
    }

    private DispatcherShard shardFor(String workerGroup) {
        return shards[Math.floorMod(workerGroup.hashCode(), shards.length)];
    }

    private void ensureStarted() {
        if (this.shards != null) {
            return;
        }
        synchronized (this) {
            if (this.shards != null) {
                return;
            }
            int normalizedShardCount = Math.max(1, shardCount);
            this.shards = new DispatcherShard[normalizedShardCount];
            for (int i = 0; i < normalizedShardCount; i++) {
                this.shards[i] = new DispatcherShard(i);
            }
        }
    }

    private String queueKey(String workerGroup) {
        return key("queue:" + workerGroup + ":pending");
    }


    private final class DispatcherShard {
        private final int index;
        private final Set<String> groups = ConcurrentHashMap.newKeySet();
        private final Object signal = new Object();
        private volatile boolean started = false;
        private volatile boolean running = true;   // 新增：控制线程运行状态
        private Thread workerThread;               // 新增：保存工作线程引用

        private DispatcherShard(int index) {
            this.index = index;
        }

        private void subscribe(String workerGroup) {
            groups.add(workerGroup);
            ensureStarted();
            signal();
        }

        private void unsubscribe(String workerGroup) {
            groups.remove(workerGroup);
            signal();
        }

        private void ensureStarted() {
            if (started) {
                return;
            }
            synchronized (this) {
                if (started) {
                    return;
                }
                workerThread = new Thread(this::run, "tock-redis-queue-" + index);
                workerThread.setDaemon(true);
                workerThread.start();
                started = true;
            }
        }

        private void run() {
            while (running) {
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                Snapshot snapshot = snapshot();
                if (snapshot.keys.length == 0) {
                    waitForWork();
                    continue;
                }
                try {
                    List<byte[]> response = withJedis(jedis -> jedis.blpop(BLPOP_TIMEOUT_SECONDS, snapshot.keys));
                    if (response == null || response.size() < 2) {
                        continue;
                    }
                    String queueKey = text(response.get(0));
                    String workerGroup = snapshot.keyToGroup.get(queueKey);
                    if (workerGroup == null) {
                        continue;
                    }
                    JobExecution execution = decode(response.get(1), JobExecution.class);
                    if (!dispatch(workerGroup, execution)) {
                        requeue(workerGroup, execution);
                    }
                } catch (Exception e) {
                    if (jedisPool.isClosed()) {
                        return;
                    }
                    log.error("Redis queue shard {} failed", index, e);
                }
            }
        }

        private Snapshot snapshot() {
            List<String> activeGroups = new ArrayList<>();
            for (String group : groups) {
                if (subscribers.containsKey(group)) {
                    activeGroups.add(group);
                }
            }
            Collections.sort(activeGroups);
            byte[][] keys = new byte[activeGroups.size()][];
            Map<String, String> keyToGroup = new HashMap<>(activeGroups.size());
            for (int i = 0; i < activeGroups.size(); i++) {
                String group = activeGroups.get(i);
                String queueKey = queueKey(group);
                keys[i] = raw(queueKey);
                keyToGroup.put(queueKey, group);
            }
            return new Snapshot(keys, keyToGroup);
        }

        private boolean dispatch(String workerGroup, JobExecution execution) {
            List<Consumer<JobExecution>> consumers = subscribers.get(workerGroup);
            if (consumers == null || consumers.isEmpty()) {
                return false;
            }
            for (Consumer<JobExecution> consumer : consumers) {
                context.getConsumerExecutor().submit(() -> {
                    try {
                        consumer.accept(execution);
                    } catch (Exception e) {
                        log.error("Consumer error for group {}", workerGroup, e);
                    }
                });
            }
            return true;
        }

        private void waitForWork() {
            synchronized (signal) {
                try {
                    signal.wait(1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private void signal() {
            synchronized (signal) {
                signal.notifyAll();
            }
        }

        private void requeue(String workerGroup, JobExecution execution) {
            withJedis(jedis -> {
                jedis.lpush(raw(queueKey(workerGroup)), encode(execution));
                return null;
            });
        }

        // 新增：优雅关闭该分片
        void shutdown() {
            running = false;
            if (workerThread != null) {
                workerThread.interrupt();
            }
            signal(); // 唤醒可能在 wait 中的线程
            // 可选：等待线程终止一段时间，避免长时间阻塞
            if (workerThread != null) {
                try {
                    workerThread.join(2000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private static final class Snapshot {
        private final byte[][] keys;
        private final Map<String, String> keyToGroup;

        private Snapshot(byte[][] keys, Map<String, String> keyToGroup) {
            this.keys = keys;
            this.keyToGroup = keyToGroup;
        }
    }
}
