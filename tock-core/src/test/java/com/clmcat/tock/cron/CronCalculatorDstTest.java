package com.clmcat.tock.cron;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CronCalculatorDstTest {

    private static final ZoneId ZONE_ID = ZoneId.of("America/New_York");
    private final CronCalculator calculator = new CronCalculator(ZONE_ID);

    @Test
    void shouldResolveDstSpringForwardGapToFirstValidLocalTime() {
        // 夏令时开始：2026-03-08 02:00 到 02:59 的本地时间不存在。
        // 这里用 02:30 验证调度器会落到当天第一个真实可执行时刻 03:30，并切换到 EDT(-04:00)。
        long springForwardBase = timestamp(2026, 3, 8, 1, 59, 58);
        Optional<Long> springForward = calculator.getNextExecutionTime("0 30 2 8 3 ? 2026", springForwardBase);

        assertPresentAt(springForward, 2026, 3, 8, 3, 30, 0, "-04:00");
    }

    @Test
    void shouldResolveDstFallBackOverlapToFirstOccurrence() {
        // 夏令时结束：2026-11-01 01:30 会出现两次。
        // 基准时间在回拨前，先命中第一次出现的 01:30，仍然使用 EDT(-04:00)。
        long fallBackBase = timestamp(2026, 11, 1, 0, 59, 58);
        Optional<Long> fallBack = calculator.getNextExecutionTime("0 30 1 1 11 ? 2026", fallBackBase);

        assertPresentAt(fallBack, 2026, 11, 1, 1, 30, 0, "-04:00");
    }

    @Test
    void shouldResolveDstFallBackOverlapToSecondOccurrence() {
        // 夏令时结束后，同一个 local time 会以新的偏移量再出现一次。
        // 这里把基准时间放在第一次 01:30 之后，验证第二次 01:30(-05:00) 不会被漏掉。
        long fallBackBase = timestampWithOffset(2026, 11, 1, 1, 31, 0, ZoneOffset.ofHours(-4));
        Optional<Long> fallBack = calculator.getNextExecutionTime("0 30 1 1 11 ? 2026", fallBackBase);

        assertPresentAt(fallBack, 2026, 11, 1, 1, 30, 0, "-05:00");
    }

    @Test
    void shouldSkipAdjustedHourIfNotAllowedAfterSpringForward() {
        // 夏令时开始后的顺延只针对当前缺失的 local time 生效，不应错误命中未声明的小时字段。
        long base = timestamp(2026, 3, 8, 1, 59, 58);

        Optional<Long> next = calculator.getNextExecutionTime("0 0 4 8 3 ? 2026", base);

        assertPresentAt(next, 2026, 3, 8, 4, 0, 0, "-04:00");
    }

    private static void assertPresentAt(Optional<Long> actual,
                                        int year,
                                        int month,
                                        int day,
                                        int hour,
                                        int minute,
                                        int second,
                                        String offsetId) {
        assertTrue(actual.isPresent());
        ZonedDateTime zonedDateTime = Instant.ofEpochMilli(actual.get()).atZone(ZONE_ID);
        assertAll(
                () -> assertEquals(LocalDateTime.of(year, month, day, hour, minute, second), zonedDateTime.toLocalDateTime()),
                () -> assertEquals(offsetId, zonedDateTime.getOffset().getId())
        );
    }

    private static long timestamp(int year, int month, int day, int hour, int minute, int second) {
        return LocalDateTime.of(year, month, day, hour, minute, second)
                .atZone(ZONE_ID)
                .toInstant()
                .toEpochMilli();
    }

    private static long timestampWithOffset(int year, int month, int day, int hour, int minute, int second, ZoneOffset offset) {
        return ZonedDateTime.ofLocal(LocalDateTime.of(year, month, day, hour, minute, second), ZONE_ID, offset)
                .toInstant()
                .toEpochMilli();
    }
}
