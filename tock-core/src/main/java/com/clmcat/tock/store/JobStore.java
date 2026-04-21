package com.clmcat.tock.store;

import java.util.List;

/**
 * 延时任务存储，按执行时间排序（类似延迟队列）。
 * 实现需要保证线程安全，支持原子性的 poll 操作。
 */
public interface JobStore {

    /**
     * 添加一个待执行任务。
     * @param execution 任务执行元数据（包含 scheduleId, jobId, nextFireTime, workerGroup, params 等）
     */
    void add(JobExecution execution);

    /**
     * 获取当前时间戳之前到期的任务，并原子地从存储中移除。
     * @param now 当前时间戳（毫秒）
     * @param limit 最多返回数量
     * @return 到期任务列表（已移除）
     */
    List<JobExecution> pollDueTasks(long now, int limit);

    /**
     * 根据任务ID删除任务（如取消调度）。
     * @param executionId 任务唯一ID
     * @return 是否删除成功
     */
    boolean remove(String executionId);

    /**
     * 查询某个 schedule 是否还有未执行的 future 任务（用于避免重复添加）。
     * @param scheduleId 调度配置ID
     * @param nextFireTime 下次执行时间（可选，用于精确匹配）
     * @return 是否存在
     */
    boolean hasPending(String scheduleId, long nextFireTime);

    /**
     * 获取当前存储中的任务总数（可选，用于监控）。
     */
    long size();
}