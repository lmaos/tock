package com.clmcat.tock.health.server;

import java.util.concurrent.atomic.AtomicInteger;

public class DefaultSelectorBalancer implements SelectorBalancer {


    private int worker;
    private final AtomicInteger counter = new AtomicInteger(0);

    public DefaultSelectorBalancer(int worker) {
        this.worker = worker;
    }
    @Override
    public int next() {
        return counter.updateAndGet(i->{
            return (i+1) % worker;
        });
    }

}
