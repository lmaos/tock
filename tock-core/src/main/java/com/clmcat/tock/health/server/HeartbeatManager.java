package com.clmcat.tock.health.server;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

public class HeartbeatManager {

    // Map1: 客户端ID → 超时时间戳(秒对齐)
    private final ConcurrentHashMap<String, Long> clientTimeMap = new ConcurrentHashMap<>();

    // Map2: 时间戳 → 客户端集合(按时间排序)
    private final ConcurrentSkipListMap<Long, Set<String>> timeSortedMap = new ConcurrentSkipListMap<>();

    // ===================== 刷新心跳 =====================
    public void refresh(String clientId) {
        long now = System.currentTimeMillis() / 1000 * 1000;
        refresh(clientId, now);
    }
    public void refresh(String clientId, long now) {
        // 秒级对齐，减少时间节点


        clientTimeMap.compute(clientId, (k, oldTime) -> {
            // 旧时间存在 → 从有序Map移除
            if (oldTime != null && oldTime <= now) {
                timeSortedMap.computeIfPresent(oldTime, (timeKey, clientSet) -> {
                    clientSet.remove(clientId);
                    return clientSet.isEmpty() ? null : clientSet;
                });
            }

            // 加入新时间
            timeSortedMap.computeIfAbsent(now, key -> ConcurrentHashMap.newKeySet())
                    .add(clientId);
            return now;
        });
    }

    // ===================== 只查询超时，不删除 =====================
    public List<String> getTimeout(long timeoutMillis) {
        List<String> timeoutClientList = new ArrayList<>();
        long expireTime = System.currentTimeMillis() - timeoutMillis;

        // 安全遍历：小于 expireTime 的全部超时
        for (Long timestamp : timeSortedMap.headMap(expireTime).keySet()) {
            Set<String> clientIds = timeSortedMap.get(timestamp);
            if (clientIds == null || clientIds.isEmpty()) continue;

            clientIds.forEach(clientId -> {
                // 双重校验：时间戳一致才算真正超时（防并发）
                Long currentTs = clientTimeMap.get(clientId);
                if (currentTs != null && currentTs.equals(timestamp)) {
                    timeoutClientList.add(clientId);
                }
            });
        }
        return timeoutClientList;
    }

    public Map<String, Long> getTimeoutWithTimestamp(long timeoutMillis) {
        Map<String, Long> result = new HashMap<>();
        long expireTime = System.currentTimeMillis() - timeoutMillis;
        for (Map.Entry<Long, Set<String>> entry: timeSortedMap.headMap(expireTime).entrySet()) {
            Set<String> clientIds = entry.getValue();
            long timestamp = entry.getKey();
            for (String id : clientIds) {
                result.put(id, timestamp);
            }
        }
        return result;
    }

    // ===================== 安全删除客户端（带时间戳校验） =====================
    public boolean remove(String clientId, long timestamp) {
        return clientTimeMap.compute(clientId, (k, currentTs) -> {
            // 时间不匹配 → 不删
            if (currentTs == null || !currentTs.equals(timestamp)) {
                return currentTs;
            }
            // 从有序集合中移除
            timeSortedMap.computeIfPresent(timestamp, (timeKey, clientSet) -> {
                clientSet.remove(clientId);
                return clientSet.isEmpty() ? null : clientSet;
            });
            // 返回 null = 删除 clientId
            return null;
        }) == null;
    }

    // ===================== 获取客户端当前时间戳 =====================
    public Long getTimestamp(String clientId) {
        return clientTimeMap.get(clientId);
    }

    public void clear() {
        clientTimeMap.clear();
        timeSortedMap.clear();
    }

    public boolean containsKey(String clientId) {
        return clientTimeMap.containsKey(clientId);
    }
}