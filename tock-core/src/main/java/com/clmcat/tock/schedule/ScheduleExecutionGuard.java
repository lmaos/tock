package com.clmcat.tock.schedule;

import com.clmcat.tock.cron.CronCalculator;
import com.clmcat.tock.cron.CronCalculators;
import com.clmcat.tock.store.JobExecution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 调度配置与执行计划的一致性判断。
 */
public final class ScheduleExecutionGuard {
    private ScheduleExecutionGuard() {
    }

    public static String fingerprint(ScheduleConfig config) {
        if (config == null) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        builder.append(nullSafe(config.getScheduleId())).append('|')
                .append(nullSafe(config.getJobId())).append('|')
                .append(nullSafe(config.getCron())).append('|')
                .append(config.getFixedDelayMs()).append('|')
                .append(nullSafe(config.getWorkerGroup())).append('|')
                .append(config.isEnabled()).append('|')
                .append(nullSafe(config.getZoneId()));
        if (config.getParams() != null && !config.getParams().isEmpty()) {
            for (Map.Entry<String, Object> entry : new TreeMap<>(config.getParams()).entrySet()) {
                builder.append('|').append(entry.getKey()).append('=').append(String.valueOf(entry.getValue()));
            }
        }
        return sha256(builder.toString());
    }

    public static boolean isExecutionStillValid(ScheduleConfig config, JobExecution execution, long now) {
        if (config == null || execution == null || !config.isEnabled()) {
            return false;
        }
        if (!Objects.equals(config.getJobId(), execution.getJobId())
                || !Objects.equals(config.getWorkerGroup(), execution.getWorkerGroup())) {
            return false;
        }
        String executionFingerprint = execution.getScheduleFingerprint();
        if (executionFingerprint != null && !executionFingerprint.equals(fingerprint(config))) {
            return false;
        }
        if (now <= execution.getNextFireTime()) {
            return true;
        }
        if (config.getFixedDelayMs() != null && config.getFixedDelayMs() > 0 && config.getCron() == null) {
            return now - execution.getNextFireTime() < config.getFixedDelayMs();
        }
        if (config.getCron() != null && !config.getCron().isEmpty()) {
            CronCalculator calculator = CronCalculators.get(config.getZoneId());
            Optional<Long> next = calculator.getNextExecutionTime(config.getCron(), execution.getNextFireTime());
            return next.isPresent() && now < next.get();
        }
        return false;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
