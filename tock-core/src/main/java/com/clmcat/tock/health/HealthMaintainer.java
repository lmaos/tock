package com.clmcat.tock.health;

import com.clmcat.tock.Lifecycle;

/**
 * 节点健康维护器，负责追踪集群中 Worker 节点的存活状态。
 * <p>
 * 它不直接执行清理动作，而是在节点状态变化时通知已注册的 {@link NodeHealthListener}。
 * 具体的清理逻辑（如释放分布式锁、重新入队任务）应由监听器实现。
 * </p>
 *
 *
 * @see NodeHealthListener
 * @see Lifecycle
 */
public interface HealthMaintainer {

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


}