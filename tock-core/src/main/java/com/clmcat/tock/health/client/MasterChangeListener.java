package com.clmcat.tock.health.client;

import com.clmcat.tock.health.HealthHost;

public interface MasterChangeListener {
    void onMasterChanged(HealthHost oldHost, HealthHost newHost);
}