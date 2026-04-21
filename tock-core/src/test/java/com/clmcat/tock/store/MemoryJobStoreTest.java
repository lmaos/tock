package com.clmcat.tock.store;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

public class MemoryJobStoreTest {

    @Test
    void shouldPollDueTasksRespectLimit() {
        MemoryJobStore store = MemoryJobStore.create();
        long now = System.currentTimeMillis();
        JobExecution e1 = execution("e1", now - 1000L);
        JobExecution e2 = execution("e2", now - 900L);
        JobExecution e3 = execution("e3", now - 800L);

        store.add(e1);
        store.add(e2);
        store.add(e3);

        List<JobExecution> due = store.pollDueTasks(now, 2);
        Assertions.assertEquals(2, due.size());
        Assertions.assertEquals(1L, store.size());
        Assertions.assertTrue(store.hasPending("schedule-a", e3.getNextFireTime()));
        Assertions.assertFalse(store.hasPending("schedule-a", e1.getNextFireTime()));
    }

    @Test
    void shouldRemoveExecutionAndCleanupEmptySchedule() {
        MemoryJobStore store = MemoryJobStore.create();
        JobExecution execution = execution("e1", System.currentTimeMillis() + 1000L);
        store.add(execution);

        Assertions.assertTrue(store.remove("e1"));
        Assertions.assertFalse(store.remove("e1"));
        Assertions.assertEquals(0L, store.size());
        Assertions.assertFalse(store.hasPending("schedule-a", execution.getNextFireTime()));
    }

    @Test
    void shouldIgnoreFutureTasksWhenPollingDue() {
        MemoryJobStore store = MemoryJobStore.create();
        JobExecution execution = execution("e1", System.currentTimeMillis() + 5000L);
        store.add(execution);

        Assertions.assertTrue(store.pollDueTasks(System.currentTimeMillis(), 10).isEmpty());
        Assertions.assertEquals(1L, store.size());
    }

    private static JobExecution execution(String executionId, long fireTime) {
        return JobExecution.builder()
                .executionId(executionId)
                .scheduleId("schedule-a")
                .jobId("job-a")
                .nextFireTime(fireTime)
                .workerGroup("default")
                .params(Collections.singletonMap("k", "v"))
                .build();
    }
}
