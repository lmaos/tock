package com.clmcat.tock.registry;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

public interface TockNode {
    enum NodeStatus {
        ACTIVE, // 活跃
        INACTIVE, // 不活跃
        UNKNOWN // 未知
    }

    /**
    * 获取节点 ID。
    * @return 节点唯一标识
    */
    String getId();

    /**
    * 获取节点状态。
    * @return 节点状态
    */
    NodeStatus getStatus();

    /**
     * 配置节点的属性 (节点存活时有效)
     *
     * @return
     */
    boolean setAttributeIfAbsent(String key, Object value);

    void setAttributeIfAbsent(Map<String, Object> attributes);

    boolean removeNodeAttributes(String name);

    <T> T getAttribute(String key, Class<T> type);

    <T> Map<String, T> getAttributes(Collection<String> keys, Class<T> type);

    Set<String> getAttributeNamesAll();

    void clearAttributes();


}
