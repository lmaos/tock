package com.clmcat.tock.schedule.memory;

import com.clmcat.tock.schedule.ScheduleConfig;
import com.clmcat.tock.schedule.ScheduleStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Objects;

public class MemoryScheduleStore implements ScheduleStore {
    private final ConcurrentMap<String, ScheduleConfig> store = new ConcurrentHashMap<>();
    private final AtomicLong version = new AtomicLong(0);


    public static MemoryScheduleStore create() {
        return new MemoryScheduleStore();
    }

    @Override
    public void save(ScheduleConfig config) {
        ScheduleConfig previous = store.put(config.getScheduleId(), config);
        if (!sameConfig(previous, config)) {
            version.incrementAndGet();
        }
    }

    @Override
    public void delete(String scheduleId) {
        if (store.remove(scheduleId) != null) {
            version.incrementAndGet();
        }
    }

    @Override
    public ScheduleConfig get(String scheduleId) {
        return store.get(scheduleId);
    }

    @Override
    public List<ScheduleConfig> getAll() {
        return new ArrayList<>(store.values());
    }

    @Override
    public long getGlobalVersion() {
        return version.get();
    }

    private boolean sameConfig(ScheduleConfig a, ScheduleConfig b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return Objects.equals(a.getScheduleId(), b.getScheduleId())
                && Objects.equals(a.getJobId(), b.getJobId())
                && Objects.equals(a.getCron(), b.getCron())
                && Objects.equals(a.getFixedDelayMs(), b.getFixedDelayMs())
                && Objects.equals(a.getWorkerGroup(), b.getWorkerGroup())
                && a.isEnabled() == b.isEnabled()
                && Objects.equals(a.getParams(), b.getParams())
                && Objects.equals(a.getZoneId(), b.getZoneId());
    }
}
