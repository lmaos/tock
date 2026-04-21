package com.clmcat.tock.registry;

public interface NodeListener {
    // 节点启动
    void onRunning();
    default void onStopped() {}
}
