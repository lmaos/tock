package com.clmcat.tock.registry;

import com.clmcat.tock.Lifecycle;

import java.util.Collection;
import java.util.List;

/**
 * 服务注册中心，负责节点注册、选主、分布式状态存储。
 * <p>
 * <b>注意：</b>如果注册中心实现类同时实现了 {@link com.clmcat.tock.time.TimeProvider} 接口（例如基于 Redis 的实现），
 * 且用户未显式配置 {@link com.clmcat.tock.Config#timeProvider}，则 Tock 会自动将其作为时间提供者，
 * 用于获取分布式统一时间戳。这可以简化配置，避免额外的时间源设置。
 * </p>
 */
public interface TockRegister extends Lifecycle {

    /**
     * 主机
     * @return
     */
    TockMaster getMaster();

    /**
     * 节点
     * @return
     */
    TockCurrentNode getCurrentNode();

    TockNode getNode(String nodeId);

    List<TockNode> getNods();
    // 过期的Nodes
    List<TockNode> getExpiredNodes();

    void removeNode(String nodeId);


    boolean setNodeAttributeIfAbsent(String name, Object value);

    <T> T getNodeAttribute(String name, Class<T> type);

    boolean removeNodeAttribute(String name);

    boolean setGroupAttributeIfAbsent(String name, Object value);

    <T> T getGroupAttribute(String name,  Class<T> type);

    boolean removeGroupAttribute(String name);

    void removeGroupAttributes(Collection<String> names);

    void setRuntimeState(String key, String value);

    String getRuntimeState(String key);

    /**
     * 获取运行时状态并转换为 long 值。
     * @param key 状态键
     * @param defaultValue 当状态不存在或解析失败时返回的默认值
     * @return long 值
     */
    default long getRuntimeStateAsLong(String key, long defaultValue) {
        String value = getRuntimeState(key);
        if (value == null) return defaultValue;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
