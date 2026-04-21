package com.clmcat.tock.job;

/**
 * <p>用途: 注册中心，存储jobId → JobExecutor的映射。</p>
 *
 * <p>供Worker在执行任务时根据jobId找到对应的业务逻辑。</p>
 */
public interface JobRegistry {
    /**
     * 注册一个 Job 执行器
     * @param jobId 任务唯一标识
     * @param executor 执行逻辑
     * @throws IllegalArgumentException 如果 jobId 已存在（可选，也可以覆盖）
     */
    void register(String jobId, JobExecutor executor);

    /**
     * 获取 Job 执行器
     * @param jobId 任务ID
     * @return 执行器，如果不存在则返回 null
     */
    JobExecutor get(String jobId);

    /**
     * 检查是否已注册
     */
    boolean contains(String jobId);

    /**
     * 移除注册（可选，用于动态卸载）
     */
    void unregister(String jobId);
}


