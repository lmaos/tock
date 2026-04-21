package com.clmcat.tock.registry;

public interface MasterListener {
    void onBecomeMaster();   // 成为主节点时回调
    void onLoseMaster();     // 失去主节点时回调
}