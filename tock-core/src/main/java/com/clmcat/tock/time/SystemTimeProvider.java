package com.clmcat.tock.time;

/**
 * 系统时间， 如果继承这个类，则不会被 DefaultTimeSynchronizer 使用， 直接返回当前结果时间
 */
public class SystemTimeProvider implements TimeProvider {
    @Override
    public long currentTimeMillis() {
        return System.currentTimeMillis();
    }
}
