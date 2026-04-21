package com.clmcat.tock.schedule.redis;

import com.clmcat.tock.RedisTestSupport;
import com.clmcat.tock.schedule.ScheduleConfig;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Collections;

public class RedisScheduleStoreTest extends RedisTestSupport {

    @Test
    void shouldPersistAndVersionOnlyOnRealChanges() {
        RedisScheduleStore store = new RedisScheduleStore(namespace, jedisPool);
        ScheduleConfig config1 = schedule("schedule-1", "job-1");
        ScheduleConfig config2 = config1.toBuilder().enabled(false).build();
        ScheduleConfig config3 = config2.toBuilder().enabled(false).build();

        Assertions.assertEquals(0L, store.getGlobalVersion());
        store.save(config1);
        Assertions.assertEquals(1L, store.getGlobalVersion());
        store.save(config1);
        Assertions.assertEquals(1L, store.getGlobalVersion());
        store.save(config2);
        Assertions.assertEquals(2L, store.getGlobalVersion());
        store.save(config3);
        Assertions.assertEquals(2L, store.getGlobalVersion());
        Assertions.assertEquals(config2.isEnabled(), store.get("schedule-1").isEnabled());
        Assertions.assertEquals(1, store.getAll().size());

        store.delete("schedule-1");
        Assertions.assertEquals(3L, store.getGlobalVersion());
        Assertions.assertNull(store.get("schedule-1"));
    }

    private ScheduleConfig schedule(String scheduleId, String jobId) {
        return ScheduleConfig.builder()
                .scheduleId(scheduleId)
                .jobId(jobId)
                .cron("*/5 * * * * ?")
                .workerGroup("default")
                .params(Collections.singletonMap("mode", "redis"))
                .zoneId("UTC")
                .build();
    }
}
