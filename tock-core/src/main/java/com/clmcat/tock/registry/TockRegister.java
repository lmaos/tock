package com.clmcat.tock.registry;

import com.clmcat.tock.Lifecycle;

import java.util.Collection;
import java.util.List;

/**
 * 服务注册中心，负责节点注册、选主、分布式状态存储。
 */
public interface TockRegister extends Lifecycle {

    String getNamespace();

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
