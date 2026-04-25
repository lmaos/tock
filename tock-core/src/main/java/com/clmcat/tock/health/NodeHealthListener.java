package com.clmcat.tock.health;


import com.clmcat.tock.registry.TockNode;

public interface NodeHealthListener {



    void onNodeExpired(TockNode node);
}