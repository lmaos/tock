package com.clmcat.tock.time;

import com.clmcat.tock.Lifecycle;
import com.clmcat.tock.TockContext;

/**
 * 时间同步器，为分布式调度系统提供单调递增且经过偏移校正的当前时间。
 * <p>
 * 该组件负责维护本地时钟与远程时间源（如 Redis、NTP）之间的偏差，
 * 并确保所有调用者获得的时间戳严格单调递增（不会因系统时钟回拨或偏移跳变而倒退）。
 * 调度器（如 {@code CronScheduler}）和 Worker 中所有需要获取当前时间的操作都应通过该接口，
 * 以保证分布式环境下节点间时间基准的统一。
 * </p>
 *
 * <h3>典型实现</h3>
 * 可参考 {@link DefaultTimeSynchronizer}，它通过周期性采样远程时间源、使用中点补偿网络延迟，
 * 并利用 CAS 自旋保证时间单调性。
 *
 * @author clmcat
 * @see DefaultTimeSynchronizer
 * @see TimeProvider
 */
public interface TimeSynchronizer extends TimeSource, Lifecycle {

    /**
     * 返回同步后的当前时间戳（毫秒）。
     * <p>
     * 该时间戳基于本地系统时钟加上动态调整的偏移量，并保证单调递增。
     * 即使底层时钟因 NTP 调整而回拨，或偏移量发生突变，返回值也不会比前一次小。
     * </p>
     *
     * @return 单调递增的毫秒级时间戳
     */
    long currentTimeMillis();

    /**
     * 返回当前本地时钟与远程时间源之间的偏移量（毫秒）。
     * <p>
     * 偏移量定义为 {@code remoteTime - localTime}，即本地时间需要增加该值才能得到远程时间。
     * 该值可能为正、负或零，具体取决于本地时钟与远程源的快慢差异。
     * </p>
     *
     * @return 当前偏移量（毫秒）
     */
    long offset();

    /**
     * 启动时间同步器，开始后台采样与偏移更新任务。
     * <p>
     * 调用该方法后，同步器会定期从 {@link TimeProvider} 获取远程时间，计算新的偏移量，
     * 并更新内部状态。对于不需要同步的场景（如使用本地系统时间），实现可忽略此操作。
     * </p>
     */
    void start(TockContext context);

    /**
     * 停止时间同步器，释放后台线程资源。
     * <p>
     * 停止后，{@link #currentTimeMillis()} 将不再更新偏移量，但可能仍返回最后一次同步后的时间。
     * 通常与 {@link #start(TockContext)} 配对使用，在应用关闭时调用。
     * </p>
     */
    void stop();
}