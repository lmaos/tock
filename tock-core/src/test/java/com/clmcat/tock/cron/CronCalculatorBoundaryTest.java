package com.clmcat.tock.cron;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CronCalculatorBoundaryTest {

    private static final ZoneId ZONE_ID = ZoneId.of("UTC");
    private final CronCalculator calculator = new CronCalculator(ZONE_ID);

    @Test
    void shouldRejectInvalidFieldCount() {
        // 边界场景：字段数量不对时，必须明确失败。
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> calculator.getNextExecutionTime("* * * * *", 0L)
        );

        assertTrue(ex.getMessage().contains("6 or 7 fields"));
    }

    @Test
    void shouldRejectOutOfRangeSecond() {
        // 边界场景：秒字段越界时应抛出校验异常，而不是悄悄修正。
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> calculator.getNextExecutionTime("60 * * * * ?", 0L)
        );

        assertTrue(ex.getMessage().contains("out of range"));
    }

    @Test
    void shouldRejectConflictingDayFields() {
        // 规则场景：日和周同时约束时，必须使用 ? 明确消歧。
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> calculator.getNextExecutionTime("0 0 12 15 * 2", 0L)
        );

        assertTrue(ex.getMessage().contains("must be '?'"));
    }

    @Test
    void shouldRejectZeroStep() {
        // 异常场景：步长不能为 0。
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> calculator.getNextExecutionTime("0 */0 * * * ?", 0L)
        );

        assertTrue(ex.getMessage().contains("step must be greater than 0"));
    }

    @Test
    void shouldReturnEmptyWhenNoFutureExecutionExists() {
        // 终态场景：如果表达式已经没有未来可执行点，应返回 empty。
        long base = timestamp(2026, 4, 16, 0, 0, 0);

        Optional<Long> next = calculator.getNextExecutionTime("0 0 0 1 1 ? 2025", base);

        assertFalse(next.isPresent());
    }

    @Test
    void shouldReportValidationStateAccurately() {
        // 校验场景：合法表达式返回 true，非法表达式返回 false。
        assertTrue(calculator.isValidCron("0 0 12 * * ?"));
        assertFalse(calculator.isValidCron("0 0 12 15 * 2"));
    }

    @Test
    void shouldRejectBothDayOfMonthAndDayOfWeekAsQuestionMark() {
        // 规则场景：日和周字段不能同时是 ?。
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> calculator.getNextExecutionTime("0 0 0 ? * ?", 0L)
        );

        assertTrue(ex.getMessage().contains("cannot both be '?'"));
        assertFalse(calculator.isValidCron("0 0 0 ? * ?"));
    }

    @Test
    void shouldReturnEmptyForInvalidLeapDayWithExplicitNonLeapYear() {
        // 边界场景：非闰年显式指定 2 月 29 日时，应返回 empty 而不是抛异常。
        long base = timestamp(2027, 1, 1, 0, 0, 0);

        Optional<Long> next = calculator.getNextExecutionTime("0 0 0 29 2 ? 2027", base);

        assertFalse(next.isPresent());
    }

    private static long timestamp(int year, int month, int day, int hour, int minute, int second) {
        return LocalDateTime.of(year, month, day, hour, minute, second)
                .atZone(ZONE_ID)
                .toInstant()
                .toEpochMilli();
    }
}
