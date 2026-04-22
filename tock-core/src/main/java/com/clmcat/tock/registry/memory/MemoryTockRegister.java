package com.clmcat.tock.registry.memory;

import com.clmcat.tock.TockContext;
import com.clmcat.tock.registry.TockNode;
import com.clmcat.tock.registry.TockRegister;
import com.clmcat.tock.money.MemoryManager;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Getter
public class MemoryTockRegister implements TockRegister {

    private MemoryTockMaster master;
    private MemoryTockNode currentNode;
    private MemoryManager memoryManager;
    private TockContext tockContext;

    private boolean running = false;

    public MemoryTockRegister(String name, MemoryManager memoryManager) {
        this.master = new MemoryTockMaster(name, memoryManager);
        this.currentNode = new MemoryTockNode(name, memoryManager);
        this.memoryManager = memoryManager;
    }

    public static MemoryTockRegister create(String name, MemoryManager memoryManager) {
        return new MemoryTockRegister(name, memoryManager);
    }


    @Override
    public void start(TockContext context) {
        running = true;
        this.tockContext = context;
        master.start(context);
        currentNode.start(context);
    }

    @Override
    public void stop() {
        running = false;
        master.stop();
        currentNode.stop();
    }

    @Override
    public boolean isRunning() {
        return running;
    }


    @Override
    public TockNode getNode(String nodeId) {
        MemoryTockNode node = memoryManager.getNodeMap().get(nodeId);
        return node == null ? null : new NodeView(node);
    }

    @Override
    public List<TockNode> getNods() {
        List<TockNode> nodes = new ArrayList<>();
        for (MemoryTockNode node : memoryManager.getNodeMap().values()) {
            nodes.add(new NodeView(node));
        }
        return nodes;
    }

    @Override
    public List<TockNode> getExpiredNodes() {
        List<TockNode>  list = new ArrayList<>();
        for (TockNode node : memoryManager.getNodeMap().values()) {
            if (node.getStatus() != MemoryTockNode.NodeStatus.ACTIVE) {
                list.add(new NodeView((MemoryTockNode) node));
            }
        }
        return list;
    }

    @Override
    public void removeNode(String nodeId) {
        MemoryTockNode remove = memoryManager.getNodeMap().remove(nodeId);
        if (remove != null) {

        }
    }

    @Override
    public boolean setNodeAttributeIfAbsent(String name, Object value) {
        return currentNode.setAttributeIfAbsent(name, value);
    }

    @Override
    public <T> T getNodeAttribute(String name,  Class<T> type) {
        return currentNode.getAttribute(name, type);
    }

    @Override
    public boolean removeNodeAttribute(String name) {
        return currentNode.removeNodeAttributes(name);
    }

    @Override
    public boolean setGroupAttributeIfAbsent(String name, Object value) {
        return memoryManager.getGroupAttributeMap().putIfAbsent(name, value) == null;
    }

    @Override
    public <T> T getGroupAttribute(String name, Class<T> type) {
        return (T) memoryManager.getGroupAttributeMap().get(name);
    }

    @Override
    public boolean removeGroupAttribute(String name) {
        return memoryManager.getGroupAttributeMap().remove(name) != null;
    }

    @Override
    public void removeGroupAttributes(Collection<String> names) {
        for (String name : names) {
            removeGroupAttribute(name);
        }
    }

    @Override
    public void setRuntimeState(String key, String value) {
        memoryManager.getRuntimeStates().put(key, value);
    }

    @Override
    public String getRuntimeState(String key) {
        return memoryManager.getRuntimeStates().get(key);
    }

    private static final class NodeView implements TockNode {
        private final MemoryTockNode delegate;

        private NodeView(MemoryTockNode delegate) {
            this.delegate = delegate;
        }

        @Override
        public String getId() {
            return delegate.getId();
        }

        @Override
        public TockNode.NodeStatus getStatus() {
            return delegate.getStatus();
        }

        @Override
        public boolean setAttributeIfAbsent(String key, Object value) {
            return delegate.setAttributeIfAbsent(key, value);
        }

        @Override
        public void setAttributeIfAbsent(Map<String, Object> attributes) {
            delegate.setAttributeIfAbsent(attributes);
        }

        @Override
        public boolean removeNodeAttributes(String name) {
            return delegate.removeNodeAttributes(name);
        }

        @Override
        public <T> T getAttribute(String key, Class<T> type) {
            return delegate.getAttribute(key, type);
        }

        @Override
        public <T> Map<String, T> getAttributes(Collection<String> keys, Class<T> type) {
            return delegate.getAttributes(keys, type);
        }

        @Override
        public Set<String> getAttributeNamesAll() {
            return delegate.getAttributeNamesAll();
        }

        @Override
        public void clearAttributes() {
            delegate.clearAttributes();
        }
    }
}
