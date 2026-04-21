package com.clmcat.tock.worker.memory;

import com.clmcat.tock.TockContext;
import com.clmcat.tock.TockContextAware;
import com.clmcat.tock.store.JobExecution;
import com.clmcat.tock.worker.SubscribableWorkerQueue;
import com.clmcat.tock.worker.WorkerQueue;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 内存版本的 SubscribableWorkerQueue，用于本地测试。
 * 支持订阅/取消订阅，当任务推送时，异步调用所有订阅者的 consumer。
 */
@Slf4j
public class MemorySubscribableWorkerQueue implements SubscribableWorkerQueue , TockContextAware {
    private final ConcurrentHashMap<String, List<Consumer<JobExecution>>> subscribers = new ConcurrentHashMap<>();

    private TockContext context;

    public static @NonNull WorkerQueue create() {
        return new MemorySubscribableWorkerQueue();
    }

    @Override
    public void push(JobExecution execution, String workerGroup) {
        List<Consumer<JobExecution>> consumers = subscribers.get(workerGroup);
        if (consumers == null || consumers.isEmpty()) {
            log.debug("No subscribers for group {}, task discarded", workerGroup);
            return;
        }
        if (context == null || context.getConsumerExecutor() == null) {
            throw new IllegalStateException("TockContext with consumerExecutor must be set before push");
        }
        // 异步通知所有订阅者，避免阻塞调用者（例如 Master 推送线程）
        for (Consumer<JobExecution> consumer : consumers) {
            context.getConsumerExecutor().submit(() -> {
                try {
                    consumer.accept(execution);
                } catch (Exception e) {
                    log.error("Consumer error for group {}", workerGroup, e);
                }
            });
        }
    }

    @Override
    public void subscribe(String workerGroup, Consumer<JobExecution> consumer) {
        subscribers.computeIfAbsent(workerGroup, k -> new CopyOnWriteArrayList<>()).add(consumer);
        log.debug("Subscribed to group {}", workerGroup);
    }

    @Override
    public void unsubscribe(String workerGroup) {
        subscribers.remove(workerGroup);
        log.debug("Unsubscribed from group {}", workerGroup);
    }

    @Override
    public void setTockContext(TockContext context) {
            this.context = Objects.requireNonNull(context, "context is null");
    }
}
