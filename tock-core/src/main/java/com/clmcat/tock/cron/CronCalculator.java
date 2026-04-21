package com.clmcat.tock.cron;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneOffsetTransition;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Cron 表达式工具服务。
 * 功能：根据基准时间，计算下一次触发执行的时间。
 */
public final class CronCalculator {

    private static final int SEARCH_YEARS = 400;

    private final ZoneId zoneId;

    public CronCalculator() {
        this(ZoneId.systemDefault());
    }

    public CronCalculator(ZoneId zoneId) {
        this.zoneId = Objects.requireNonNull(zoneId, "zoneId");
    }

    /**
     * 根据 cron 表达式 和 基准时间戳，计算【下次执行时间戳】。
     *
     * @param cronExpression cron 表达式（支持 6位/7位）
     * @param baseTimestamp  基准时间戳（毫秒），从这个时间往后找下一次
     * @return 下次执行时间戳（毫秒），无下次则返回 empty
     */
    public Optional<Long> getNextExecutionTime(String cronExpression, long baseTimestamp) {
        CronExpression expression = CronExpression.parse(cronExpression);
        // 先把基准时间推进 1 秒，避免把“当前时刻”本身当成下一次触发。
        ZonedDateTime start = Instant.ofEpochMilli(baseTimestamp)
                .atZone(zoneId)
                .truncatedTo(ChronoUnit.SECONDS)
                .plusSeconds(1);
        return expression.nextExecutionAfter(start, zoneId).map(value -> value.toInstant().toEpochMilli());
    }

    /**
     * 验证 cron 表达式是否合法。
     */
    public boolean isValidCron(String cronExpression) {
        try {
            CronExpression.parse(cronExpression);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static final class CronExpression {
        private final NumericField seconds;
        private final NumericField minutes;
        private final NumericField hours;
        private final DayOfMonthField dayOfMonth;
        private final NumericField months;
        private final DayOfWeekField dayOfWeek;
        private final NumericField years;

        private CronExpression(NumericField seconds,
                               NumericField minutes,
                               NumericField hours,
                               DayOfMonthField dayOfMonth,
                               NumericField months,
                               DayOfWeekField dayOfWeek,
                               NumericField years) {
            this.seconds = seconds;
            this.minutes = minutes;
            this.hours = hours;
            this.dayOfMonth = dayOfMonth;
            this.months = months;
            this.dayOfWeek = dayOfWeek;
            this.years = years;
        }

        /**
         * 解析 6 位或 7 位 Cron 表达式。
         *
         * <pre>
         * 秒 分 时 日 月 周 [年]
         * 0-59 0-59 0-23 1-31 1-12 1-7 1970-9999
         * </pre>
         *
         * 支持的特殊字符：
         * {@code *}、{@code /}、{@code ,}、{@code -}、{@code ?}、{@code L}、{@code W}、{@code #}。
         * 其中 {@code ?} 仅用于日/周字段，{@code W} 仅用于日字段，{@code #} 仅用于周字段。
         */
        private static CronExpression parse(String expression) {
            if (expression == null || expression.trim().isEmpty()) {
                throw invalid(expression, "expression must not be blank");
            }

            String[] fields = expression.trim().split("\\s+");
            if (fields.length != 6 && fields.length != 7) {
                throw invalid(expression, "cron must contain 6 or 7 fields");
            }

            NumericField seconds = NumericField.parse(fields[0], "second", 0, 59);
            NumericField minutes = NumericField.parse(fields[1], "minute", 0, 59);
            NumericField hours = NumericField.parse(fields[2], "hour", 0, 23);
            String dayOfMonthToken = fields[3];
            DayOfMonthField dayOfMonth = DayOfMonthField.parse(dayOfMonthToken, expression);
            NumericField months = NumericField.parse(fields[4], "month", 1, 12);
            String dayOfWeekToken = fields[5];
            DayOfWeekField dayOfWeek = DayOfWeekField.parse(dayOfWeekToken, expression);
            NumericField years = fields.length == 7
                    ? NumericField.parse(fields[6], "year", 1970, 9999)
                    : NumericField.any("year", 1970, 9999);

            boolean dayOfMonthQuestion = "?".equals(dayOfMonthToken);
            boolean dayOfWeekQuestion = "?".equals(dayOfWeekToken);
            if (dayOfMonthQuestion && dayOfWeekQuestion) {
                throw invalid(expression, "day-of-month and day-of-week cannot both be '?'");
            }
            if (!dayOfMonthQuestion && !dayOfWeekQuestion && !dayOfMonth.supportsDayOfWeekCombination()) {
                throw invalid(expression, "one of day-of-month and day-of-week must be '?'");
            }

            return new CronExpression(seconds, minutes, hours, dayOfMonth, months, dayOfWeek, years);
        }

        private Optional<ZonedDateTime> nextExecutionAfter(ZonedDateTime start, ZoneId zoneId) {
            ZonedDateTime cursor = start.withNano(0);
            Instant startInstant = start.toInstant();
            int searchLimitYear = start.getYear() + SEARCH_YEARS;

            // 采用“年 -> 月 -> 日 -> 时 -> 分 -> 秒”的逐层收敛方式查找下一次触发时间。
            while (true) {
                if (cursor.getYear() > searchLimitYear) {
                    return Optional.empty();
                }

                Integer year = years.nextOrSame(cursor.getYear());
                if (year == null || year > searchLimitYear) {
                    return Optional.empty();
                }
                if (year != cursor.getYear()) {
                    cursor = cursor.withYear(year)
                            .withMonth(1)
                            .withDayOfMonth(1)
                            .withHour(0)
                            .withMinute(0)
                            .withSecond(0);
                }

                Integer month = months.nextOrSame(cursor.getMonthValue());
                if (month == null) {
                    cursor = cursor.plusYears(1)
                            .withMonth(1)
                            .withDayOfMonth(1)
                            .withHour(0)
                            .withMinute(0)
                            .withSecond(0);
                    continue;
                }
                if (month != cursor.getMonthValue()) {
                    cursor = cursor.withMonth(month)
                            .withDayOfMonth(1)
                            .withHour(0)
                            .withMinute(0)
                            .withSecond(0);
                }

                LocalDate nextDate = nextMatchingDateInMonth(cursor.toLocalDate());
                if (nextDate == null) {
                    cursor = cursor.plusMonths(1)
                            .withDayOfMonth(1)
                            .withHour(0)
                            .withMinute(0)
                            .withSecond(0);
                    continue;
                }

                ZonedDateTime candidate = nextExecutionOnDate(nextDate, startInstant, zoneId);
                if (candidate == null) {
                    cursor = ZonedDateTime.of(nextDate.plusDays(1), LocalTime.MIDNIGHT, zoneId);
                    continue;
                }

                return Optional.of(candidate);
            }
        }

        private LocalDate nextMatchingDateInMonth(LocalDate startDate) {
            int lastDay = startDate.lengthOfMonth();
            for (int day = startDate.getDayOfMonth(); day <= lastDay; day++) {
                LocalDate candidate = startDate.withDayOfMonth(day);
                if (matchesDate(candidate)) {
                    return candidate;
                }
            }
            return null;
        }

        private boolean matchesDate(LocalDate date) {
            if (dayOfMonth.supportsDayOfWeekCombination() && !dayOfWeek.isUnconstrained()) {
                return dayOfWeek.matches(date) && date.plusWeeks(1).getMonth() != date.getMonth();
            }
            boolean dom = dayOfMonth.isUnconstrained() || dayOfMonth.matches(date);
            boolean dow = dayOfWeek.isUnconstrained() || dayOfWeek.matches(date);
            return dom && dow;
        }

        private ZonedDateTime nextExecutionOnDate(LocalDate date, Instant startInstant, ZoneId zoneId) {
            ZonedDateTime best = null;
            int[] allowedHours = hours.values();
            int[] allowedMinutes = minutes.values();
            int[] allowedSeconds = seconds.values();

            // 直接枚举当天所有允许的时分秒组合：
            // 1) gap 由 ZonedDateTime 解析为下一个有效本地时间；
            // 2) overlap 通过额外的有效偏移补齐第二次出现的同一 local time。
            for (int hour : allowedHours) {
                for (int minute : allowedMinutes) {
                    for (int second : allowedSeconds) {
                        LocalDateTime localDateTime = LocalDateTime.of(date, LocalTime.of(hour, minute, second));
                        for (ZonedDateTime occurrence : resolveOccurrences(localDateTime, zoneId)) {
                            best = chooseBetter(best, occurrence, startInstant);
                        }
                    }
                }
            }

            return best;
        }

        private ZonedDateTime chooseBetter(ZonedDateTime currentBest, ZonedDateTime candidate, Instant startInstant) {
            if (!candidate.toInstant().isAfter(startInstant)) {
                return currentBest;
            }
            if (currentBest == null || candidate.toInstant().isBefore(currentBest.toInstant())) {
                return candidate;
            }
            return currentBest;
        }

        private List<ZonedDateTime> resolveOccurrences(LocalDateTime localDateTime, ZoneId zoneId) {
            List<ZoneOffset> offsets = zoneId.getRules().getValidOffsets(localDateTime);
            if (offsets.size() == 1) {
                return Collections.singletonList(ZonedDateTime.ofLocal(localDateTime, zoneId, offsets.get(0)));
            }
            if (offsets.size() > 1) {
                return Arrays.asList(
                        ZonedDateTime.ofLocal(localDateTime, zoneId, offsets.get(0)),
                        ZonedDateTime.ofLocal(localDateTime, zoneId, offsets.get(1))
                );
            }

            ZoneOffsetTransition transition = zoneId.getRules().getTransition(localDateTime);
            if (transition == null) {
                return Collections.emptyList();
            }

            // gap：把本地时间顺延到跳变后的同一时刻，例如 02:30 -> 03:30。
            // 这里直接补上 transition 的时长，得到跳变后第一个真实存在的本地时间。
            LocalDateTime adjusted = localDateTime.plusSeconds(transition.getDuration().getSeconds());
            return Collections.singletonList(ZonedDateTime.ofLocal(adjusted, zoneId, transition.getOffsetAfter()));
        }
    }

    private static final class NumericField {
        private final String name;
        private final int min;
        private final int max;
        private final boolean[] allowed;
        private final boolean any;
        private final int[] cachedValues;

        private NumericField(String name, int min, int max, boolean[] allowed, boolean any) {
            this.name = name;
            this.min = min;
            this.max = max;
            this.allowed = allowed;
            this.any = any;
            this.cachedValues = buildValues(min, max, allowed);
        }

        private static NumericField any(String name, int min, int max) {
            boolean[] values = new boolean[max + 1];
            Arrays.fill(values, min, max + 1, true);
            return new NumericField(name, min, max, values, true);
        }

        private static NumericField parse(String field, String name, int min, int max) {
            if ("*".equals(field)) {
                return any(name, min, max);
            }

            boolean[] values = new boolean[max + 1];
            String[] segments = field.split(",");
            for (String segment : segments) {
                fillSegment(values, segment.trim(), name, min, max);
            }

            if (!containsAny(values, min, max)) {
                throw new IllegalArgumentException(name + " field does not contain any valid value");
            }

            return new NumericField(name, min, max, values, false);
        }

        private static void fillSegment(boolean[] values, String segment, String name, int min, int max) {
            if (segment.isEmpty() || "?".equals(segment) || "L".equals(segment) || segment.endsWith("W") || segment.contains("#")) {
                throw new IllegalArgumentException("unsupported token '" + segment + "' in " + name + " field");
            }

            String[] stepParts = segment.split("/", -1);
            if (stepParts.length > 2) {
                throw new IllegalArgumentException("invalid step syntax '" + segment + "' in " + name + " field");
            }

            int step = 1;
            if (stepParts.length == 2) {
                step = parseNumber(stepParts[1], name, min, max);
                if (step <= 0) {
                    throw new IllegalArgumentException(name + " step must be greater than 0");
                }
            }

            String rangePart = stepParts[0];
            int start;
            int end;
            if ("*".equals(rangePart)) {
                start = min;
                end = max;
            } else if (rangePart.contains("-")) {
                String[] range = rangePart.split("-", -1);
                if (range.length != 2) {
                    throw new IllegalArgumentException("invalid range syntax '" + segment + "' in " + name + " field");
                }
                start = parseNumber(range[0], name, min, max);
                end = parseNumber(range[1], name, min, max);
                if (start > end) {
                    throw new IllegalArgumentException(name + " range start must not be greater than end");
                }
            } else {
                start = parseNumber(rangePart, name, min, max);
                end = stepParts.length == 2 ? max : start;
            }

            for (int value = start; value <= end; value += step) {
                values[value] = true;
            }
        }

        private static int parseNumber(String value, String name, int min, int max) {
            try {
                int parsed = Integer.parseInt(value);
                if (parsed < min || parsed > max) {
                    throw new IllegalArgumentException(name + " value " + parsed + " is out of range [" + min + ", " + max + "]");
                }
                return parsed;
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("invalid number '" + value + "' in " + name + " field", ex);
            }
        }

        private static boolean containsAny(boolean[] values, int min, int max) {
            for (int i = min; i <= max; i++) {
                if (values[i]) {
                    return true;
                }
            }
            return false;
        }

        private Integer nextOrSame(int value) {
            if (value < min) {
                return first();
            }
            if (value > max) {
                return null;
            }
            for (int current = value; current <= max; current++) {
                if (allowed[current]) {
                    return current;
                }
            }
            return null;
        }

        private boolean contains(int value) {
            return value >= min && value <= max && allowed[value];
        }

        private int first() {
            for (int current = min; current <= max; current++) {
                if (allowed[current]) {
                    return current;
                }
            }
            throw new IllegalStateException("no values in " + name + " field");
        }

        private int maxAllowed() {
            for (int current = max; current >= min; current--) {
                if (allowed[current]) {
                    return current;
                }
            }
            throw new IllegalStateException("no values in " + name + " field");
        }

        private boolean isAny() {
            return any;
        }

        private int[] values() {
            return cachedValues;
        }

        private static int[] buildValues(int min, int max, boolean[] allowed) {
            int size = 0;
            for (int current = min; current <= max; current++) {
                if (allowed[current]) {
                    size++;
                }
            }
            int[] values = new int[size];
            int index = 0;
            for (int current = min; current <= max; current++) {
                if (allowed[current]) {
                    values[index++] = current;
                }
            }
            return values;
        }
    }

    private static final class DayOfMonthField {
        private enum Kind {
            ANY,
            NO_SPEC,
            NUMERIC,
            LAST_DAY,
            NEAREST_WEEKDAY
        }

        private final Kind kind;
        private final NumericField numericField;
        private final Integer weekdayTarget;

        private DayOfMonthField(Kind kind, NumericField numericField, Integer weekdayTarget) {
            this.kind = kind;
            this.numericField = numericField;
            this.weekdayTarget = weekdayTarget;
        }

        private static DayOfMonthField parse(String field, String expression) {
            if ("*".equals(field)) {
                return new DayOfMonthField(Kind.ANY, null, null);
            }
            if ("?".equals(field)) {
                return new DayOfMonthField(Kind.NO_SPEC, null, null);
            }
            if ("L".equals(field)) {
                return new DayOfMonthField(Kind.LAST_DAY, null, null);
            }
            if (field.endsWith("W")) {
                String day = field.substring(0, field.length() - 1);
                int value = NumericField.parseNumber(day, "day-of-month", 1, 31);
                return new DayOfMonthField(Kind.NEAREST_WEEKDAY, null, value);
            }
            if (field.contains("#")) {
                throw invalid(expression, "'#' is not supported in day-of-month field");
            }
            if (field.contains("L")) {
                throw invalid(expression, "invalid 'L' usage in day-of-month field");
            }
            return new DayOfMonthField(
                    Kind.NUMERIC,
                    NumericField.parse(field, "day-of-month", 1, 31),
                    null
            );
        }

        private boolean isUnconstrained() {
            return kind == Kind.ANY || kind == Kind.NO_SPEC;
        }

        private boolean supportsDayOfWeekCombination() {
            return kind == Kind.LAST_DAY;
        }

        private boolean matches(LocalDate date) {
            switch (kind) {
                case ANY:
                case NO_SPEC:
                    return true;
                case NUMERIC:
                    return numericField.contains(date.getDayOfMonth());
                case LAST_DAY:
                    return date.getDayOfMonth() == date.lengthOfMonth();
                case NEAREST_WEEKDAY:
                    return date.getDayOfMonth() == nearestWeekday(date.withDayOfMonth(1), weekdayTarget);
                default:
                    throw new IllegalStateException("unsupported day-of-month kind: " + kind);
            }
        }

        private static int nearestWeekday(LocalDate firstDayOfMonth, int requestedDay) {
            int lastDay = firstDayOfMonth.lengthOfMonth();
            int targetDay = Math.min(requestedDay, lastDay);
            LocalDate candidate = firstDayOfMonth.withDayOfMonth(targetDay);
            DayOfWeek dayOfWeek = candidate.getDayOfWeek();
            if (dayOfWeek == DayOfWeek.SATURDAY) {
                return targetDay == 1 ? 3 : targetDay - 1;
            }
            if (dayOfWeek == DayOfWeek.SUNDAY) {
                return targetDay == lastDay ? lastDay - 2 : targetDay + 1;
            }
            return targetDay;
        }
    }

    private static final class DayOfWeekField {
        private enum Kind {
            ANY,
            NO_SPEC,
            NUMERIC,
            LAST_IN_MONTH,
            NTH_IN_MONTH
        }

        private final Kind kind;
        private final NumericField numericField;
        private final Integer dayOfWeek;
        private final Integer ordinal;

        private DayOfWeekField(Kind kind, NumericField numericField, Integer dayOfWeek, Integer ordinal) {
            this.kind = kind;
            this.numericField = numericField;
            this.dayOfWeek = dayOfWeek;
            this.ordinal = ordinal;
        }

        private static DayOfWeekField parse(String field, String expression) {
            if ("*".equals(field)) {
                return new DayOfWeekField(Kind.ANY, null, null, null);
            }
            if ("?".equals(field)) {
                return new DayOfWeekField(Kind.NO_SPEC, null, null, null);
            }
            if (field.contains("#")) {
                String[] parts = field.split("#", -1);
                if (parts.length != 2) {
                    throw invalid(expression, "invalid '#' syntax in day-of-week field");
                }
                int dayOfWeek = NumericField.parseNumber(parts[0], "day-of-week", 1, 7);
                int ordinal = NumericField.parseNumber(parts[1], "day-of-week ordinal", 1, 5);
                return new DayOfWeekField(Kind.NTH_IN_MONTH, null, dayOfWeek, ordinal);
            }
            if (field.endsWith("L")) {
                if (field.length() == 1) {
                    throw invalid(expression, "day-of-week 'L' must be used as nL");
                }
                int dayOfWeek = NumericField.parseNumber(field.substring(0, field.length() - 1), "day-of-week", 1, 7);
                return new DayOfWeekField(Kind.LAST_IN_MONTH, null, dayOfWeek, null);
            }
            if (field.contains("W")) {
                throw invalid(expression, "'W' is not supported in day-of-week field");
            }
            return new DayOfWeekField(
                    Kind.NUMERIC,
                    NumericField.parse(field, "day-of-week", 1, 7),
                    null,
                    null
            );
        }

        private boolean isUnconstrained() {
            return kind == Kind.ANY || kind == Kind.NO_SPEC;
        }

        private boolean matches(LocalDate date) {
            int cronDayOfWeek = toCronDayOfWeek(date.getDayOfWeek());
            switch (kind) {
                case ANY:
                case NO_SPEC:
                    return true;
                case NUMERIC:
                    return numericField.contains(cronDayOfWeek);
                case LAST_IN_MONTH:
                    return cronDayOfWeek == dayOfWeek && date.plusWeeks(1).getMonth() != date.getMonth();
                case NTH_IN_MONTH:
                    return cronDayOfWeek == dayOfWeek && ((date.getDayOfMonth() - 1) / 7 + 1) == ordinal;
                default:
                    throw new IllegalStateException("unsupported day-of-week kind: " + kind);
            }
        }

        /**
         * Cron 的周字段约定 1=周日，2=周一，...，7=周六。
         */
        private static int toCronDayOfWeek(DayOfWeek dayOfWeek) {
            return dayOfWeek == DayOfWeek.SUNDAY ? 1 : dayOfWeek.getValue() + 1;
        }
    }

    private static IllegalArgumentException invalid(String expression, String reason) {
        return new IllegalArgumentException("Invalid cron expression '" + expression + "': " + reason);
    }
}
