package com.clmcat.tock.store;

import java.io.Serializable;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * 存储在 JobStore 中的延时任务元数据。
 * 不可变，使用 Builder 创建。
 */
public final class JobExecution implements Serializable {
    private static final long serialVersionUID = 1L;
    /**
     * 唯一ID，通常由 Master 生成（例如 UUID），用于 JobStore 中的唯一标识和删除。
     *
     * 每一次具体的执行计划（即每次计算出的未来触发时间点）的唯一标识。通常由 Master 在将任务写入 JobStore 时生成
     */
    private final String executionId;
    /**
     * 调度配置的唯一标识。由用户在创建 ScheduleConfig 时指定（例如 "clean-temp-schedule"）。
     * 一个 scheduleId 对应一个完整的调度规则（cron/固定延迟、workerGroup、开关等），可以产生无数个 executionId。
     */
    private final String scheduleId;
    /**
     * 业务执行逻辑的唯一标识。由用户在注册 JobExecutor 时指定（例如 "clean-temp-job"）。
     * Worker 执行时通过 jobId 从 JobRegistry 中找到对应的 JobExecutor 并调用。
     */
    private final String jobId;
    /**
     * 计划执行的时间戳（毫秒）。
     * Master 在调度时根据 cron 或 fixedDelay 计算出下一次应该执行的时间，存入 JobStore 作为排序依据。
     * Worker 执行时，可以将这个时间与 actualFireTime（实际执行时间）对比，监控延迟。
     */
    private final long nextFireTime;
    /**
     * 指定该任务应该由哪个工作组（Worker Group）来执行。
     * Master 从 ScheduleConfig 中获取该值，并确保任务推送到对应组的队列（如 tock:queue:{workerGroup}）。
     * Worker 节点启动时会声明自己属于哪个组，只消费自己组的任务，实现执行隔离。
     */
    private final String workerGroup;
    /**
     * 生成该执行计划时所对应的调度配置指纹。
     * 用于在恢复极端场景下判断“旧计划”是否仍然和当前配置一致。
     */
    private final String scheduleFingerprint;
    /**
     * 用户自定义的参数 Map。在创建 ScheduleConfig 时可以通过 Builder 设置（例如 params(Map.of("path", "/tmp", "timeout", 30))）。
     * Worker 执行时，这些参数会传递给 JobContext，业务逻辑可以根据参数动态调整行为，无需修改代码。
     */
    private final Map<String, Object> params;

    private JobExecution(Builder builder) {
        this.executionId = builder.executionId;
        this.scheduleId = builder.scheduleId;
        this.jobId = builder.jobId;
        this.nextFireTime = builder.nextFireTime;
        this.workerGroup = builder.workerGroup;
        this.scheduleFingerprint = builder.scheduleFingerprint;
        this.params = builder.params == null ? Collections.emptyMap() : Collections.unmodifiableMap(builder.params);
    }

    // Getters
    public String getExecutionId() { return executionId; }
    public String getScheduleId() { return scheduleId; }
    public String getJobId() { return jobId; }
    public long getNextFireTime() { return nextFireTime; }
    public String getWorkerGroup() { return workerGroup; }
    public String getScheduleFingerprint() { return scheduleFingerprint; }
    public Map<String, Object> getParams() { return params; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        JobExecution that = (JobExecution) o;
        return nextFireTime == that.nextFireTime &&
               Objects.equals(executionId, that.executionId) &&
               Objects.equals(scheduleId, that.scheduleId) &&
               Objects.equals(jobId, that.jobId) &&
               Objects.equals(workerGroup, that.workerGroup) &&
               Objects.equals(scheduleFingerprint, that.scheduleFingerprint) &&
               Objects.equals(params, that.params);
    }

    @Override
    public int hashCode() {
        return Objects.hash(executionId, scheduleId, jobId, nextFireTime, workerGroup, scheduleFingerprint, params);
    }

    @Override
    public String toString() {
        return "JobExecution{" +
               "executionId='" + executionId + '\'' +
               ", scheduleId='" + scheduleId + '\'' +
               ", jobId='" + jobId + '\'' +
               ", nextFireTime=" + nextFireTime +
               ", workerGroup='" + workerGroup + '\'' +
               ", scheduleFingerprint='" + scheduleFingerprint + '\'' +
               ", params=" + params +
               '}';
    }

    // 生成一个基于当前对象的新 Builder（用于修改字段，例如重新调度）
    public Builder toBuilder() {
        return new Builder()
            .executionId(this.executionId)
            .scheduleId(this.scheduleId)
            .jobId(this.jobId)
            .nextFireTime(this.nextFireTime)
            .workerGroup(this.workerGroup)
            .scheduleFingerprint(this.scheduleFingerprint)
            .params(this.params);
    }

    // 静态工厂方法
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String executionId;
        private String scheduleId;
        private String jobId;
        private long nextFireTime;
        private String workerGroup;
        private String scheduleFingerprint;
        private Map<String, Object> params;

        public Builder executionId(String executionId) { this.executionId = executionId; return this; }
        public Builder scheduleId(String scheduleId) { this.scheduleId = scheduleId; return this; }
        public Builder jobId(String jobId) { this.jobId = jobId; return this; }
        public Builder nextFireTime(long nextFireTime) { this.nextFireTime = nextFireTime; return this; }
        public Builder workerGroup(String workerGroup) { this.workerGroup = workerGroup; return this; }
        public Builder scheduleFingerprint(String scheduleFingerprint) { this.scheduleFingerprint = scheduleFingerprint; return this; }
        public Builder params(Map<String, Object> params) { this.params = params; return this; }

        public JobExecution build() {
            Objects.requireNonNull(executionId, "executionId must not be null");
            Objects.requireNonNull(scheduleId, "scheduleId must not be null");
            Objects.requireNonNull(jobId, "jobId must not be null");
            if (nextFireTime <= 0) throw new IllegalArgumentException("nextFireTime must be positive");
            Objects.requireNonNull(workerGroup, "workerGroup must not be null");
            return new JobExecution(this);
        }
    }
}
