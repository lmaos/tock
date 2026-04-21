package com.clmcat.tock.time;

/**
 * 时间生产者，返回一个可被同步器采样的时间戳。
 */
public interface TimeProvider {
    long currentTimeMillis();
}
