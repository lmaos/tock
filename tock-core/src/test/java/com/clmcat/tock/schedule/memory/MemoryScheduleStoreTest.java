package com.clmcat.tock.schedule.memory;

import com.clmcat.tock.schedule.ScheduleConfig;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

public class MemoryScheduleStoreTest {

    @Test
    void shouldIncrementVersionOnSaveAndOverwrite() {
        MemoryScheduleStore store = MemoryScheduleStore.create();
        ScheduleConfig config1 = schedule("s-1", "job-1");
        ScheduleConfig config2 = config1.toBuilder().enabled(false).build();
        ScheduleConfig config3 = config2.toBuilder().enabled(false).build();

        Assertions.assertEquals(0L, store.getGlobalVersion());
        store.save(config1);
        Assertions.assertEquals(1L, store.getGlobalVersion());
        store.save(config2);
        Assertions.assertEquals(2L, store.getGlobalVersion());
        Assertions.assertFalse(store.get("s-1").isEnabled());
        store.save(config3);
        Assertions.assertEquals(2L, store.getGlobalVersion());
    }

    @Test
    void shouldNotIncrementVersionWhenDeletingMissingSchedule() {
        MemoryScheduleStore store = MemoryScheduleStore.create();
        store.save(schedule("s-1", "job-1"));

        Assertions.assertEquals(1L, store.getGlobalVersion());
        store.delete("missing");
        Assertions.assertEquals(1L, store.getGlobalVersion());
    }

    @Test
    void shouldReturnCopyOfAllConfigs() {
        MemoryScheduleStore store = MemoryScheduleStore.create();
        store.save(schedule("s-1", "job-1"));
        store.save(schedule("s-2", "job-2"));

        List<ScheduleConfig> all = store.getAll();
        Assertions.assertEquals(2, all.size());
        all.clear();
        Assertions.assertEquals(2, store.getAll().size());
    }

    private static ScheduleConfig schedule(String scheduleId, String jobId) {
        return ScheduleConfig.builder()
                .scheduleId(scheduleId)
                .jobId(jobId)
                .cron("*/5 * * * * ?")
                .workerGroup("default")
                .zoneId("UTC")
                .build();
    }
}
