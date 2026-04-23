package com.clmcat.tock.registry;

import com.clmcat.tock.Tock;
import com.clmcat.tock.TockContext;

public interface TockCurrentNode extends TockNode {
    void addNodeListener(NodeListener listener);
    void removeNodeListener(NodeListener listener);


    void start(TockContext context);
    void stop();
    boolean isRunning();
}
