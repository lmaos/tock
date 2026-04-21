package com.clmcat.tock.cron;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CronCalculatorBasicTest {

    private static final ZoneId ZONE_ID = ZoneId.of("UTC");
    private final CronCalculator calculator = new CronCalculator(ZONE_ID);

    @Test
    void shouldCalculateNextDailyFixedTime() {
        // 基础场景：验证固定时刻的日常任务能返回当天的下一次触发。
        long base = timestamp(2026, 4, 16, 14, 29, 58);

        Optional<Long> next = calculator.getNextExecutionTime("0 30 14 * * ?", base);

        assertTrue(next.isPresent());
        assertEquals(timestamp(2026, 4, 16, 14, 30, 0), next.get().longValue());
    }

    @Test
    void shouldCalculateNextStepBasedMinuteTrigger() {
        // 步长场景：验证 */15 这种常见写法可以正确推进到下一个分钟点。
        long base = timestamp(2026, 4, 16, 10, 7, 4);

        Optional<Long> next = calculator.getNextExecutionTime("0 */15 * * * ?", base);

        assertTrue(next.isPresent());
        assertEquals(timestamp(2026, 4, 16, 10, 15, 0), next.get().longValue());
    }

    @Test
    void shouldSupportLastDayOfMonth() {
        // 特殊语法：验证 L 能正确命中当月最后一天。
        long base = timestamp(2026, 4, 16, 8, 0, 0);

        Optional<Long> next = calculator.getNextExecutionTime("0 0 0 L * ?", base);

        assertTrue(next.isPresent());
        assertEquals(timestamp(2026, 4, 30, 0, 0, 0), next.get().longValue());
    }

    @Test
    void shouldSupportNearestWeekday() {
        // 特殊语法：验证 W 会落到指定日期附近最近的工作日。
        long base = timestamp(2026, 8, 1, 0, 0, 0);

        Optional<Long> next = calculator.getNextExecutionTime("0 0 9 15W * ?", base);

        assertTrue(next.isPresent());
        assertEquals(timestamp(2026, 8, 14, 9, 0, 0), next.get().longValue());
    }

    @Test
    void shouldSupportNthWeekdayInMonth() {
        // 特殊语法：验证 # 能正确命中“第几个周几”。
        long base = timestamp(2026, 4, 1, 0, 0, 0);

        Optional<Long> next = calculator.getNextExecutionTime("0 0 9 ? * 6#3", base);

        assertTrue(next.isPresent());
        assertEquals(timestamp(2026, 4, 17, 9, 0, 0), next.get().longValue());
    }

    private static long timestamp(int year, int month, int day, int hour, int minute, int second) {
        return LocalDateTime.of(year, month, day, hour, minute, second)
                .atZone(ZONE_ID)
                .toInstant()
                .toEpochMilli();
    }
}
