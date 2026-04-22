package com.clmcat.tock.worker;

import com.clmcat.tock.store.JobExecution;

public final class WorkerExecutionKeys {
    public static final String ACTIVE_PREFIX = "consumer.";
    public static final String PENDING_PREFIX = "pending.";

    private WorkerExecutionKeys() {
    }

    public static String activeKey(JobExecution execution) {
        return activeKey(execution.getWorkerGroup(), execution.getScheduleId());
    }

    public static String activeKey(String workerGroup, String scheduleId) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(ACTIVE_PREFIX);
        sb.append(workerGroup);
        sb.append(".");
        sb.append(scheduleId);

        return sb.toString();
    }

    public static String pendingKey(JobExecution execution) {
        return pendingKey(execution.getWorkerGroup(), execution.getScheduleId(), execution.getExecutionId());
    }

    public static String pendingKey(String workerGroup, String scheduleId, String executionId) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(PENDING_PREFIX);
        sb.append(workerGroup);
        sb.append(".");
        sb.append(scheduleId);
        sb.append(".");
        sb.append(executionId);
        return sb.toString();
    }

    public static boolean isActiveKey(String key) {
        return key != null && key.startsWith(ACTIVE_PREFIX);
    }

    public static boolean isPendingKey(String key) {
        return key != null && key.startsWith(PENDING_PREFIX);
    }
}
