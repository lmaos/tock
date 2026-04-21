package com.clmcat.tock.worker;

import com.clmcat.tock.store.JobExecution;

import java.util.function.Consumer;

public interface SubscribableWorkerQueue extends WorkerQueue {
    /**
     * 订阅指定工作组，当有新任务时回调 Consumer。
     * @param workerGroup 组名
     * @param consumer 任务消费者（通常由 Worker 提供）
     */
    void subscribe(String workerGroup, Consumer<JobExecution> consumer);

    /**
     * 取消订阅
     */
    void unsubscribe(String workerGroup);
}