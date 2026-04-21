package com.clmcat.tock.time;

/**
 * 时间源，提供当前时间戳。
 * <p>
 * 该接口是最小化的时间抽象，仅暴露获取当前毫秒时间的方法。
 * 可用于替换 {@link System#currentTimeMillis()}，便于单元测试或切换不同时间策略。
 * </p>
 */
public interface TimeSource {
    /**
     * 返回当前时间戳（毫秒）。
     */
    long currentTimeMillis();
}