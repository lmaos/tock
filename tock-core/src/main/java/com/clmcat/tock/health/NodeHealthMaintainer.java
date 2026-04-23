package com.clmcat.tock.health;

import com.clmcat.tock.Lifecycle;

import java.util.List;

/**
 * 节点健康维护器，负责追踪集群中 Worker 节点的存活状态。
 * <p>
 * 它不直接执行清理动作，而是在节点状态变化时通知已注册的 {@link NodeHealthListener}。
 * 具体的清理逻辑（如释放分布式锁、重新入队任务）应由监听器实现。
 * </p>
 *
 * <h3>实现模式</h3>
 * 根据底层基础设施的不同，维护器可运行在以下两种模式之一：
 * <ul>
 *   <li><b>时间服务模式</b>：当 Master 启用内置时间服务时，Worker 拉取时间的同时上报心跳，
 *        维护器通过 {@link #recordHeartbeat(String)} 被动接收心跳，实现精准的存活判断。</li>
 *   <li><b>轮询模式</b>：当时间服务不可用时，维护器通过定期扫描注册中心（如 Redis）中的
 *        节点心跳时间戳，主动发现过期节点。</li>
 * </ul>
 * 两种模式对上层监听器完全透明，回调语义保持一致。
 *
 * <h3>生命周期</h3>
 * 维护器通常在 Master 当选时启动，在 Master 退位时停止。
 *
 * @see NodeHealthListener
 * @see Lifecycle
 */
public interface NodeHealthMaintainer extends Lifecycle {

    /**
     * 注册健康状态监听器。
     * <p>
     * 同一个监听器重复注册会被忽略。回调在维护器的扫描线程中同步执行，
     * 监听器实现应避免耗时操作，建议异步处理。
     * </p>
     *
     * @param listener 要注册的监听器，不能为 {@code null}
     * @throws NullPointerException 如果 {@code listener} 为 {@code null}
     */
    void addListener(NodeHealthListener listener);

    /**
     * 移除健康状态监听器。
     *
     * @param listener 要移除的监听器
     */
    void removeListener(NodeHealthListener listener);

    /**
     * 获取当前被认为存活的节点 ID 列表。
     * <p>
     * 存活判断标准由具体实现定义，通常为最近心跳时间在允许的超时范围内。
     * </p>
     *
     * @return 存活节点 ID 的不可变列表（可能为空）
     */
    List<String> getAliveNodes();

    /**
     * 记录指定节点的心跳。
     * <p>
     * <b>该方法仅在时间服务模式下有意义</b>，当 Worker 通过 Master 时间服务拉取时间时，
     * Master 应调用此方法上报该 Worker 的节点 ID。
     * 在轮询模式下，此方法应为空操作，并记录警告日志。
     * </p>
     *
     * @param nodeId 节点唯一标识，不能为 {@code null}
     * @throws NullPointerException 如果 {@code nodeId} 为 {@code null}
     */
    void recordHeartbeat(String nodeId);
}