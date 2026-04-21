package com.clmcat.tock.worker.memory;

import com.clmcat.tock.store.JobExecution;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Collections;

public class MemoryPullableWorkerQueueTest {

    @Test
    void shouldPreserveFifoOrder() {
        MemoryPullableWorkerQueue queue = MemoryPullableWorkerQueue.create();
        JobExecution e1 = execution("e1");
        JobExecution e2 = execution("e2");

        queue.push(e1, "default");
        queue.push(e2, "default");

        Assertions.assertEquals(e1, queue.poll("default", 0));
        Assertions.assertEquals(e2, queue.poll("default", 0));
        Assertions.assertNull(queue.poll("default", 0));
    }

    @Test
    void shouldReturnNullForMissingGroup() {
        MemoryPullableWorkerQueue queue = MemoryPullableWorkerQueue.create();
        Assertions.assertNull(queue.poll("missing", 5));
    }

    private static JobExecution execution(String executionId) {
        return JobExecution.builder()
                .executionId(executionId)
                .scheduleId("schedule-a")
                .jobId("job-a")
                .nextFireTime(System.currentTimeMillis() + 1000L)
                .workerGroup("default")
                .params(Collections.emptyMap())
                .build();
    }
}
