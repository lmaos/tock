package com.clmcat.tock.time;

import com.clmcat.tock.Lifecycle;
import com.clmcat.tock.health.HealthHost;
import com.clmcat.tock.health.client.MasterChangeListener;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class HealthTimeProvider extends Lifecycle.AbstractLifecycle implements TimeProvider {

    private MasterChangeListener  masterChangeListener;
    @Override
    public long currentTimeMillis() {
        long time = context.getHeartbeatReporter().serverTime();
        // log.debug("HealthTimeProvider: currentTimeMillis: {}", time);
        return time;
    }


    public static HealthTimeProvider create() {
        return new HealthTimeProvider();
    }


    @Override
    protected void onStart() {
        context.getHeartbeatReporter().addMasterChangeListener(masterChangeListener = new MasterChangeListener() {
            @Override
            public void onMasterChanged(HealthHost oldHost, HealthHost newHost) {
                // 当 Master 地址发生变化时，时间同步重建。
                context.getTimeSynchronizer().forceReinitialize();
            }
        });
    }

    @Override
    protected void onStop() {
        if (masterChangeListener != null) {
            context.getHeartbeatReporter().removeMasterChangeListener(masterChangeListener);
            masterChangeListener = null;
        }
    }
}
