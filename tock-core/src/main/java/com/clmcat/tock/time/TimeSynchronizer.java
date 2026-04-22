package com.clmcat.tock.time;

import com.clmcat.tock.Lifecycle;
import com.clmcat.tock.TockContext;

/**
 * 时间接口。
 */
public interface TimeSynchronizer extends TimeSource, Lifecycle {

    /**
     * 返回当前时间戳（毫秒）。
     */
    long currentTimeMillis();

    /**
     * @return 当前偏移量（毫秒）
     */
    long offset();

    /**
     * 启动组件。
     */
    void start(TockContext context);

    /**
     * 停止组件。
     */
    void stop();
}
