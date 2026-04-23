package com.clmcat.tock.health;

/**
 * 节点健康状态监听器。
 * <p>
 * 当集群中 Worker 节点的存活状态发生变化时，已注册的监听器会收到相应回调。
 * 这些回调通常由 Master 节点的健康检查组件触发。
 * </p>
 *
 * <h3>回调线程说明</h3>
 * 回调方法在健康检查的扫描线程中同步执行。实现类应避免执行耗时操作，
 * 如有必要请异步处理，以免阻塞后续状态扫描。
 *
 * @see NodeHealthMaintainer
 */
public interface NodeHealthListener {

    /**
     * 节点首次被判定为不健康（心跳超时）。
     * <p>
     * 此回调可能在节点持续不健康期间被多次调用，调用频率由
     * {@code NodeHealthMaintainer} 的实现决定。
     * </p>
     *
     * @param nodeId     节点唯一标识
     * @param lastSeenMs 最后一次收到该节点心跳的本地时间戳（毫秒）
     */
    void onNodeUnhealthy(String nodeId, long lastSeenMs);

    /**
     * 节点从不健康状态恢复（再次收到心跳）。
     *
     * @param nodeId     节点唯一标识
     * @param downtimeMs 节点处于不健康状态的持续时长（毫秒）
     */
    void onNodeRecovered(String nodeId, long downtimeMs);

    /**
     * 节点被确认死亡（超过最大容忍时间仍未恢复）。
     * <p>
     * 收到此回调后，系统应执行与该节点相关的清理工作，例如释放其持有的分布式锁、
     * 将其未完成的任务重新入队等。
     * </p>
     *
     * @param nodeId     节点唯一标识
     * @param lastSeenMs 最后一次收到该节点心跳的本地时间戳（毫秒）
     */
    void onNodeExpired(String nodeId, long lastSeenMs);
}