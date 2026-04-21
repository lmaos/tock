package com.clmcat.tock.cron;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CronCalculatorRegressionTest {

    private static final ZoneId ZONE_ID = ZoneId.of("UTC");
    private final CronCalculator calculator = new CronCalculator(ZONE_ID);

    @Test
    void shouldHandleListsRangesAndMultipleMonths() {
        // 回归场景：验证列表、范围、月份枚举同时生效。
        long base = timestamp(2026, 1, 10, 8, 0, 11);

        Optional<Long> next = calculator.getNextExecutionTime("10,20,30 0 8-10 * 1,6 ?", base);

        assertTrue(next.isPresent());
        assertEquals(timestamp(2026, 1, 10, 8, 0, 20), next.get().longValue());
    }

    @Test
    void shouldHandleLastWeekdayOfMonthByDayOfWeek() {
        // 回归场景：验证 nL 可以命中“某月最后一个周几”。
        long base = timestamp(2026, 4, 1, 0, 0, 0);

        Optional<Long> next = calculator.getNextExecutionTime("0 0 18 ? * 6L", base);

        assertTrue(next.isPresent());
        assertEquals(timestamp(2026, 4, 24, 18, 0, 0), next.get().longValue());
    }

    @Test
    void shouldHandleLastDayOfMonthWithDayOfWeekConstraint() {
        // 回归场景：验证 L 与周字段组合时，可命中“该月最后一个符合条件的周几”。
        long base = timestamp(2026, 4, 1, 0, 0, 0);

        Optional<Long> next = calculator.getNextExecutionTime("0 0 0 L * 3", base);

        assertTrue(next.isPresent());
        assertEquals(timestamp(2026, 4, 28, 0, 0, 0), next.get().longValue());
    }

    @Test
    void shouldFindLeapDayAcrossYears() {
        // 回归场景：验证跨年查找时不会漏掉闰日。
        long base = timestamp(2026, 4, 16, 0, 0, 0);

        Optional<Long> next = calculator.getNextExecutionTime("0 0 0 29 2 ?", base);

        assertTrue(next.isPresent());
        assertEquals(timestamp(2028, 2, 29, 0, 0, 0), next.get().longValue());
    }

    @Test
    void shouldHonorExplicitYearConstraint() {
        // 回归场景：验证 7 位表达式中的年份约束能够生效。
        long base = timestamp(2026, 12, 31, 23, 59, 58);

        Optional<Long> next = calculator.getNextExecutionTime("0 0 0 1 1 ? 2027", base);

        assertTrue(next.isPresent());
        assertEquals(timestamp(2027, 1, 1, 0, 0, 0), next.get().longValue());
    }

    @Test
    void shouldSkipMonthsWithoutRequestedNthWeekday() {
        // 回归场景：验证 # 在不存在第 5 个周日时会自动跳到下一个满足月份。
        long base = timestamp(2026, 4, 1, 0, 0, 0);

        Optional<Long> next = calculator.getNextExecutionTime("0 0 9 ? * 1#5", base);

        assertTrue(next.isPresent());
        assertEquals(timestamp(2026, 5, 31, 9, 0, 0), next.get().longValue());
    }

    @Test
    void shouldHandleNearestWeekdayAtMonthBoundaries() {
        // 回归场景：验证 W 在月初/月底的边界分支不会跨月错位。
        Optional<Long> monthStart = calculator.getNextExecutionTime("0 0 0 1W 5 ? 2021", timestamp(2021, 4, 30, 23, 59, 58));
        Optional<Long> monthEnd = calculator.getNextExecutionTime("0 0 0 31W 7 ? 2021", timestamp(2021, 7, 1, 0, 0, 0));

        assertTrue(monthStart.isPresent());
        assertTrue(monthEnd.isPresent());
        assertEquals(timestamp(2021, 5, 3, 0, 0, 0), monthStart.get().longValue());
        assertEquals(timestamp(2021, 7, 30, 0, 0, 0), monthEnd.get().longValue());
    }

    @Test
    void shouldHandleMixedStepAndListExpressions() {
        // 回归场景：验证同一字段内 step 与 list 组合时仍能正确解析。
        long base = timestamp(2026, 1, 10, 7, 59, 59);

        Optional<Long> next = calculator.getNextExecutionTime("5-15/5,20 0 8 * * ?", base);

        assertTrue(next.isPresent());
        assertEquals(timestamp(2026, 1, 10, 8, 0, 5), next.get().longValue());
    }

    @Test
    void shouldHandleNearestWeekdayOnLeapDay() {
        // 回归场景：验证闰年 2 月 29 日为周末时，W 会回落到同月内最近的工作日。
        long base = timestamp(2020, 2, 1, 0, 0, 0);

        Optional<Long> next = calculator.getNextExecutionTime("0 0 0 29W 2 ? 2020", base);

        assertTrue(next.isPresent());
        assertEquals(timestamp(2020, 2, 28, 0, 0, 0), next.get().longValue());
    }

    @Test
    void shouldHandleNearestWeekdayInShortMonthWhenLastDayIsSaturday() {
        // 回归场景：小月中 31W 应先截断到月末，再回退到最近工作日。
        long base = timestamp(2022, 4, 1, 0, 0, 0);

        Optional<Long> next = calculator.getNextExecutionTime("0 0 0 31W 4 ? 2022", base);

        assertTrue(next.isPresent());
        assertEquals(timestamp(2022, 4, 29, 0, 0, 0), next.get().longValue());
    }

    private static long timestamp(int year, int month, int day, int hour, int minute, int second) {
        return LocalDateTime.of(year, month, day, hour, minute, second)
                .atZone(ZONE_ID)
                .toInstant()
                .toEpochMilli();
    }
}
