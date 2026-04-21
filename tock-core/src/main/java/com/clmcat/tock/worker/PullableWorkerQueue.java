package com.clmcat.tock.worker;

import com.clmcat.tock.store.JobExecution;

public interface PullableWorkerQueue extends WorkerQueue {

    /**
     * 从指定工作组的队列中阻塞获取一个任务。
     * 若队列为空则阻塞，直到有任务可用。
     * @param workerGroup 工作组名称
     * @param timeoutMs 毫秒超时
     * @return 任务对象，超时则返回 null
     */
    JobExecution poll(String workerGroup, long timeoutMs) throws InterruptedException;
}