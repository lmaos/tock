package com.clmcat.tock.worker;

import com.clmcat.tock.store.JobExecution;

/**
 * Worker 实时队列。
 * Master 将到期任务推入对应工作组队列，Worker 阻塞抢占。
 */
public interface WorkerQueue {

    /**
     * 将任务推送到指定工作组的队列。
     * @param execution 任务（通常是从 JobStore 中取出的到期 JobExecution）
     * @param workerGroup 工作组名称
     */
    void push(JobExecution execution, String workerGroup);


}