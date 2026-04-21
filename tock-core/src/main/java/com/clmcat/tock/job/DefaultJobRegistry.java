package com.clmcat.tock.job;

import java.util.concurrent.ConcurrentHashMap;

/**
 * <p>用途: 注册中心，存储jobId → JobExecutor的映射。</p>
 *
 * <p>供Worker在执行任务时根据jobId找到对应的业务逻辑。</p>
 */
public class DefaultJobRegistry implements JobRegistry {
    private final ConcurrentHashMap<String, JobExecutor> registry = new ConcurrentHashMap<>();

    @Override
    public void register(String jobId, JobExecutor executor) {
        if (jobId == null || executor == null) {
            throw new IllegalArgumentException("jobId and executor must not be null");
        }
        JobExecutor previous = registry.putIfAbsent(jobId, executor);
        if (previous != null) {
            // 策略：可以选择抛出异常或允许覆盖，这里选择抛出异常提示重复
            throw new IllegalStateException("Job already registered with id: " + jobId);
        }
    }

    @Override
    public JobExecutor get(String jobId) {
        return registry.get(jobId);
    }

    @Override
    public boolean contains(String jobId) {
        return registry.containsKey(jobId);
    }

    @Override
    public void unregister(String jobId) {
        registry.remove(jobId);
    }
}