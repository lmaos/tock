package com.clmcat.tock.money;

import com.clmcat.tock.registry.memory.MemoryTockMaster;
import com.clmcat.tock.registry.memory.MemoryTockNode;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class MemoryManager {
    private final ConcurrentMap<String, MemoryTockMaster> masterMap = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, MemoryTockNode> nodeMap = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, Object> groupAttributeMap = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, String> runtimeStates = new ConcurrentHashMap<>();

    public ConcurrentMap<String, MemoryTockMaster> getMasterMap() {
        return masterMap;
    }

    public ConcurrentMap<String, MemoryTockNode> getNodeMap() {
        return nodeMap;
    }

    public ConcurrentMap<String, Object> getGroupAttributeMap() {
        return groupAttributeMap;
    }

    public ConcurrentMap<String, String> getRuntimeStates() {
        return runtimeStates;
    }

    public static MemoryManager create() {
        return new MemoryManager();
    }
}
