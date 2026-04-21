package com.clmcat.tock.worker.memory;

import com.clmcat.tock.store.JobExecution;
import com.clmcat.tock.worker.PullableWorkerQueue;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.*;
import java.util.function.Consumer;

@Slf4j
public class MemoryPullableWorkerQueue implements PullableWorkerQueue {
    private final ConcurrentHashMap<String, BlockingQueue<JobExecution>> queues = new ConcurrentHashMap<>();

    @Override
    public void push(JobExecution execution, String workerGroup) {
        BlockingQueue<JobExecution> queue = queues.computeIfAbsent(workerGroup, k -> new LinkedBlockingQueue<>());
        queue.offer(execution);
    }

    @Override
    public JobExecution poll(String workerGroup, long timeoutMs) {
        BlockingQueue<JobExecution> queue = queues.get(workerGroup);
        if (queue == null) return null;
        try {
            return queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    public static MemoryPullableWorkerQueue create() {
        return new MemoryPullableWorkerQueue();
    }
}