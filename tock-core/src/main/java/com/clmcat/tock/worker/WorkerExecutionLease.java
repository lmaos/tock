package com.clmcat.tock.worker;

import java.io.Serializable;

/**
 * 执行期租约，只描述“当前是谁在执行哪个 executionId”。
 */
public final class WorkerExecutionLease implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String nodeId;
    private final String executionId;

    public WorkerExecutionLease(String nodeId, String executionId) {
        this.nodeId = nodeId;
        this.executionId = executionId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getExecutionId() {
        return executionId;
    }
}
