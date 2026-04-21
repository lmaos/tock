package com.clmcat.tock.registry;

import com.clmcat.tock.Lifecycle;

public interface TockCurrentNode extends Lifecycle , TockNode{
    void addNodeListener(NodeListener listener);
    void removeNodeListener(NodeListener listener);
}
