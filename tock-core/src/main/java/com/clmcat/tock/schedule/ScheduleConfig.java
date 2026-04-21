package com.clmcat.tock.schedule;

import lombok.Getter;

import java.io.Serializable;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Map;

/**
 *
 * <p>用途： 不可变配置对象，包含`scheduleId`、`jobId`、cron/fixedDelay、`workerGroup`、开关、参数等</p>
 *
 * <p> 描述“什么时间、由谁执行、执行哪个Job”。存储在`ScheduleStore`中，Master调度器读取它来生成定时任务。 </p>
 */


@Getter
public class ScheduleConfig implements Serializable {
    private static final long serialVersionUID = 1L;
    /**
     * 当前调度配置的唯一ID
     */
    private final String scheduleId;
    /**
     * 选择工作 ID， 工作执行者的名字
     */
    private final String jobId;
    /**
     * cron 表达式。 与 fixedDelay 互斥使用
     *
     * <pre>
     * 秒 分 时 日 月 周
     * *  *  *  *  *  *
     * 秒 0-59
     * 分 0-59
     * 时 0-23
     * 日 1-31
     * 月 1-12
     * 周 0-7 (0和7都代表周日, 1 = 周一…6 = 周六)
     * *：每一个（每分 / 每时 / 每日…）
     * * / n：每隔 n 个单位
     * n：固定在第 n 个
     * n,m,k：枚举多个时间点
     * n-k：范围 n 到 k
     * ?：不指定（仅用于 日 / 周，避免冲突）
     * L：最后一天（日 / 周）
     * W：最近工作日
     * 第几个周几（例如 5#3 = 第 3 个周五）
     * </pre>
     *
     *
     */
    private final String cron;
    /**
     * 固定延迟时间。 与 cron 互斥使用 毫秒
     */
    private final Long fixedDelayMs;
    /**
     * 所属的工作分组。
     */
    private final String workerGroup;
    /**
     * 启用 true,
     */
    private final boolean enabled;
    /**
     * 用户参数，一直传递到Worker执行器。
     */
    private final Map<String, Object> params;
    /**
     * 时区
     */
    private final String zoneId;

    private ScheduleConfig(Builder builder) {
        this.scheduleId = builder.scheduleId;
        this.jobId = builder.jobId;
        this.cron = builder.cron;
        this.fixedDelayMs = builder.fixedDelayMs;
        this.workerGroup = builder.workerGroup;
        this.enabled = builder.enabled;
        this.params = builder.params == null ? Collections.emptyMap() : Collections.unmodifiableMap(builder.params);
        this.zoneId = builder.zoneId;
    }


    // 生成新的 Builder，基于当前对象（用于修改）
    public Builder toBuilder() {
        return new Builder()
                .scheduleId(scheduleId)
                .jobId(jobId)
                .cron(cron)
                .fixedDelayMs(fixedDelayMs)
                .workerGroup(workerGroup)
                .enabled(enabled)
                .params(params)
                .zoneId(zoneId);
    }

    public static Builder builder() {
        return new Builder();
    }

    // Builder 静态内部类
    public static class Builder {
        private String scheduleId;
        private String jobId;
        private String cron;
        private Long fixedDelayMs;
        private String workerGroup;
        private boolean enabled = true;
        private Map<String, Object> params;
        private String zoneId = ZoneId.systemDefault().getId();  // 默认系统时区ID
        /**
         * @param scheduleId 当前调度配置的唯一ID
         */
        public Builder scheduleId(String scheduleId) { this.scheduleId = scheduleId; return this; }
        /**
         * @param jobId 选择工作 ID， 工作执行者的名字
         */
        public Builder jobId(String jobId) { this.jobId = jobId; return this; }
        /**
         * <pre>
         * 秒 分 时 日 月 周
         * *  *  *  *  *  *
         * 秒 0-59
         * 分 0-59
         * 时 0-23
         * 日 1-31
         * 月 1-12
         * 周 0-7 (0和7都代表周日, 1 = 周一…6 = 周六)
         * *：每一个（每分 / 每时 / 每日…）
         * * / n：每隔 n 个单位
         * n：固定在第 n 个
         * n,m,k：枚举多个时间点
         * n-k：范围 n 到 k
         * ?：不指定（仅用于 日 / 周，避免冲突）
         * L：最后一天（日 / 周）
         * W：最近工作日
         * 第几个周几（例如 5#3 = 第 3 个周五）
         * </pre>
         *
         * @param cron cron 表达式。 与 fixedDelay 互斥使用
         *
         */
        public Builder cron(String cron) { this.cron = cron; return this; }
        /**
         * @param fixedDelayMs 固定延迟时间。 与 cron 互斥使用 毫秒
         */
        public Builder fixedDelayMs(Long fixedDelayMs) { this.fixedDelayMs = fixedDelayMs; return this; }
        /**
         * @param workerGroup 所属的工作分组。
         */
        public Builder workerGroup(String workerGroup) { this.workerGroup = workerGroup; return this; }
        /**
         * 启用 true, 默认 true
         */
        public Builder enabled(boolean enabled) { this.enabled = enabled; return this; }
        /**
         * 用户参数，一直传递到Worker执行器。
         */
        public Builder params(Map<String, Object> params) { this.params = params; return this; }

        /**
         * @param zoneId  当前配置时区, UTC, GMT+? : GMT+8
         */
        public Builder zoneId(String zoneId) {
            this.zoneId = zoneId;
            return this;
        }

        /**
         *
         * @return 构建 ScheduleConfig
         */
        public ScheduleConfig build() {
            // 简单校验
            if (scheduleId == null || jobId == null) throw new IllegalArgumentException();
            return new ScheduleConfig(this);
        }
    }
}
