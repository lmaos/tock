package com.clmcat.tock.registry.listener;

import com.clmcat.tock.registry.NodeListener;
import com.clmcat.tock.registry.TockCurrentNode;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
@Slf4j
public class NodeListeners {

    private TockCurrentNode currentNode;
    private final Set<NodeListener> nodeListeners = ConcurrentHashMap.newKeySet();
    public NodeListeners(TockCurrentNode node) {
        this.currentNode = node;
    }
    public void addNodeListener(NodeListener listener) {
        if (nodeListeners.add(listener)) {
            if (currentNode.isRunning()) {
                listener.onRunning();
            }
        }
    }

    public void removeNodeListener(NodeListener listener) {
        nodeListeners.remove(listener);
    }

    public void onRunning() {
        for (NodeListener listener : nodeListeners) {
            try {
                listener.onRunning();
            } catch (Exception e) {
                log.error("NodeListener onRunning error", e);
                throw new RuntimeException(e);
            }
        }
    }

    public void onStopped() {
        for (NodeListener listener : nodeListeners) {
            try {
                listener.onStopped();
            }  catch (Exception e) {
                log.error("NodeListener onStopped error", e);
            }
        }
    }
}
