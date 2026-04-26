package com.clmcat.tock.job;

import com.clmcat.tock.time.TimeSource;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class JobContext {
    private final String scheduleId;
    private final String jobId;
    private final long scheduledTime;      // 计划执行时间戳(ms)
    private final long actualFireTime;     // 实际开始执行时间戳
    private final Map<String, Object> params;
    private final TimeSource timeSource;
    private final int retryCount;

    private JobContext(Builder builder) {
        this.scheduleId = builder.scheduleId;
        this.jobId = builder.jobId;
        this.scheduledTime = builder.scheduledTime;
        this.actualFireTime = builder.actualFireTime;
        this.params = Collections.unmodifiableMap(builder.params);
        this.timeSource = builder.timeSource;
        this.retryCount = builder.retryCount;
    }

    public String getScheduleId() {
        return scheduleId;
    }

    public String getJobId() {
        return jobId;
    }

    public long getScheduledTime() {
        return scheduledTime;
    }

    public long getActualFireTime() {
        return actualFireTime;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public TimeSource getTimeSource() {
        return timeSource;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public long currentTimeMillis() {
        return timeSource == null ? System.currentTimeMillis() : timeSource.currentTimeMillis();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String scheduleId;
        private String jobId;
        private long scheduledTime;
        private long actualFireTime;
        private Map<String, Object> params = new HashMap<>();
        private TimeSource timeSource;
        private int retryCount = 0;

        public Builder scheduleId(String scheduleId) { this.scheduleId = scheduleId; return this; }
        public Builder jobId(String jobId) { this.jobId = jobId; return this; }
        public Builder scheduledTime(long scheduledTime) { this.scheduledTime = scheduledTime; return this; }
        public Builder actualFireTime(long actualFireTime) { this.actualFireTime = actualFireTime; return this; }
        public Builder params(Map<String, Object> params) { this.params = params; return this; }
        public Builder timeSource(TimeSource timeSource) { this.timeSource = timeSource; return this; }
        public Builder retryCount(int retryCount) { this.retryCount = retryCount; return this; }
        public JobContext build() { return new JobContext(this); }
    }
}
