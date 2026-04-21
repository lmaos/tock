package com.clmcat.tock.cron;

import java.time.ZoneId;
import java.util.concurrent.ConcurrentHashMap;

public final class CronCalculators {

    private static final ConcurrentHashMap<ZoneId, CronCalculator> INSTANCES = new ConcurrentHashMap<>();

    public static CronCalculator get(ZoneId zoneId) {
        return INSTANCES.computeIfAbsent(zoneId, CronCalculator::new);
    }

    public static CronCalculator getUTC() {
        return get(ZoneId.of("UTC"));
    }

    public static CronCalculator getSystemDefault() {
        return get(ZoneId.systemDefault());
    }

    public static CronCalculator get(String zoneId) {

        if (zoneId == null) {
            return CronCalculators.getSystemDefault();
        }

        return INSTANCES.computeIfAbsent(ZoneId.of(zoneId), CronCalculator::new);
    }
}