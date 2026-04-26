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
     * 创建一个当前同步时间的线程快照。
     *
     * @param ttlMs 快照有效期，毫秒
     * @return 时间快照
     */
    TimeSnapshot snapshot(long ttlMs);

    /**
     * 获取当前线程绑定的时间快照。
     *
     * @return 当前快照；不存在或已过期时返回 null
     */
    TimeSnapshot currentSnapshot();

    /**
     * 将快照绑定到当前线程。
     *
     * @param snapshot 快照，传入 null 时清除绑定
     */
    void bindSnapshot(TimeSnapshot snapshot);

    /**
     * 清除当前线程绑定的快照。
     */
    void clearSnapshot();

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

    void resetInitialization();

    void forceReinitialize();
}
