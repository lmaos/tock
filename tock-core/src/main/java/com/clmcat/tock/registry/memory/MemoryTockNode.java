package com.clmcat.tock.registry.memory;

import com.clmcat.tock.TockContext;
import com.clmcat.tock.registry.NodeListener;
import com.clmcat.tock.registry.TockCurrentNode;
import com.clmcat.tock.money.MemoryManager;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Slf4j
public class MemoryTockNode implements TockCurrentNode {

    private Map<String, Object> attributes = new ConcurrentHashMap<>();

    private String name;
    private String nodeId;
    private NodeStatus status = NodeStatus.INACTIVE;
    private MemoryManager memoryManager;
    private TockContext tockContext;

    private Set<NodeListener> nodeListeners = ConcurrentHashMap.newKeySet();

    // 续租的时间。
    private long leaseTime;
    private final long leaseTimeoutMs;     // 租约超时时间，默认3000
    private final long heartbeatIntervalMs; // 续期间隔，默认1000


    private ScheduledExecutorService task;
    private ScheduledFuture<?> nodeRegisterFuture;
    public MemoryTockNode(String name, MemoryManager memoryManager) {
        this.name = name;
        this.nodeId = name + "-" + UUID.randomUUID().toString();
        this.memoryManager = memoryManager;

        this.leaseTimeoutMs = 3000;
        this.heartbeatIntervalMs = 1000;
    }

    @Override
    public String getId() {
        return nodeId;
    }

    @Override
    public NodeStatus getStatus() {

        if (status == NodeStatus.ACTIVE) {
            // 超时了，未知状态。
            if (currentTimeMillis() - leaseTime > leaseTimeoutMs) {
                return NodeStatus.UNKNOWN;
            }
        }
        return status;
    }

    @Override
    public boolean setAttributeIfAbsent(String key, Object value) {
        return this.attributes.putIfAbsent(key, value) ==  null;
    }

    @Override
    public void setAttributeIfAbsent(Map<String, Object> attributes) {
        this.attributes.putAll(attributes);
    }
    @Override
    public boolean removeNodeAttributes(String name) {
        return attributes.remove(name) !=  null;
    }

    @Override
    public <T> T getAttribute(String key, Class<T> type) {
        return (T) attributes.get(key);
    }

    @Override
    public <T> Map<String, T> getAttributes(Collection<String> keys, Class<T> type) {

        return (Map<String, T>) keys.stream()
                .filter(attributes::containsKey)
                .collect(Collectors.toMap(k -> k, attributes::get));
    }

    @Override
    public Set<String> getAttributeNamesAll() {
        return Collections.unmodifiableSet(attributes.keySet());
    }

    @Override
    public void clearAttributes() {
        attributes.clear();
    }

    @Override
    public void addNodeListener(NodeListener listener) {
        nodeListeners.add(listener);
    }

    @Override
    public void removeNodeListener(NodeListener listener) {
        nodeListeners.remove(listener);
    }

    void onRunning() {
        for (NodeListener listener : nodeListeners) {
            try {
                listener.onRunning();
            } catch (Exception e) {
               log.error("NodeListener onRunning error", e);
               throw new RuntimeException(e);
            }
        }
    }

    void onStopped() {
        for (NodeListener listener : nodeListeners) {
            try {
                listener.onStopped();
            } catch (Exception e) {
                log.error("NodeListener onStopped error", e);
            }
        }
    }

    @Override
    public void start(TockContext context) {
        if (nodeRegisterFuture == null) {
            this.tockContext = context;
            this.leaseTime = currentTimeMillis(); // 立即初始化
            status = NodeStatus.ACTIVE;
            task = createScheduler();
            nodeRegisterFuture = task.scheduleWithFixedDelay(() -> {
                this.leaseTime = currentTimeMillis();
            }, 0, heartbeatIntervalMs, TimeUnit.MILLISECONDS);
            memoryManager.getNodeMap().put(nodeId, this);
            onRunning();
        }

    }

    @Override
    public void stop() {
        if (nodeRegisterFuture != null) {
            nodeRegisterFuture.cancel(true);
            nodeRegisterFuture = null;
            if (task != null) {
                task.shutdownNow();
                task = null;
            }

            status = NodeStatus.INACTIVE;
            memoryManager.getNodeMap().remove(nodeId);
            onStopped();
            attributes.clear();
        }
    }

    @Override
    public boolean isRunning() {
        return status == NodeStatus.ACTIVE;
    }

    private long currentTimeMillis() {
        return tockContext == null ? System.currentTimeMillis() : tockContext.currentTimeMillis();
    }
    private ScheduledExecutorService createScheduler() {
        ThreadFactory factory = r -> {
            Thread thread = new Thread(r, "tock-memory-node-heartbeat");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadScheduledExecutor(factory);
    }
}
