package com.clmcat.tock.health;

import com.clmcat.tock.ResumableLifecycle;
import com.clmcat.tock.health.client.HealthClientManager;
import com.clmcat.tock.health.client.HealthResponse;
import com.clmcat.tock.health.client.MasterChangeListener;
import com.clmcat.tock.registry.NodeListener;
import com.clmcat.tock.registry.TockRegister;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class DefaultHeartbeatReporter extends ResumableLifecycle.AbstractResumableLifecycle implements HeartbeatReporter {

    private static final long DEFAULT_HEARTBEAT_INTERVAL_MS = 500L;
    private static final int DEFAULT_FAILURE_THRESHOLD = 4;

    private HealthClientManager healthClient;
    private NodeListener nodeListener;
    private final Set<HeartbeatReportListener> heartbeatReportListeners = ConcurrentHashMap.newKeySet();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicBoolean heartbeatHealthy = new AtomicBoolean(true);
    private ScheduledExecutorService heartbeatScheduler;
    private ScheduledFuture<?> heartbeatTask;

    @Override
    protected void onInit() {
        this.healthClient = createHealthClientManager(context.getRegister());
    }

    protected HealthClientManager createHealthClientManager(TockRegister register) {
        return new HealthClientManager(register);
    }

    @Override
    public void onStart() {
        if (healthClient != null) {
            healthClient.start();
        }

        this.heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, context.getNamespace() + "-heartbeat-reporter");
            t.setDaemon(true);
            return t;
        });

        context.getRegister().getCurrentNode()
                .addNodeListener(nodeListener = new NodeListener() {
                    @Override
                    public void onRunning() {
                        resume();
                    }

                    @Override
                    public void onStopped() {
                        pause(true);
                    }
                });

        resume();
    }

    @Override
    protected void onPause(boolean force) {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(force);
            heartbeatTask = null;
        }
        log.info("Heartbeat reporting paused");
    }

    @Override
    protected void onResume() {
        reportActive();
        if (!isRunning()) {
            return;
        }
        heartbeatTask = heartbeatScheduler.scheduleWithFixedDelay(this::reportActive, 0, heartbeatIntervalMs(), TimeUnit.MILLISECONDS);
        log.info("Heartbeat reporting resumed");
    }

    protected void reportActive() {
        if (healthClient == null || context == null || context.getRegister() == null) {
            onHeartbeatReportFailure(HealthResponse.SYSTEM_ERROR);
            return;
        }
        try {
            String nodeId = context.getRegister().getCurrentNode().getId();
            HealthResponse response = healthClient.reportActive(nodeId);
            if (response != null && response.getCode() == 0) {
                onHeartbeatReportSuccess();
            } else {
                onHeartbeatReportFailure(response);
            }
        } catch (Exception e) {
            log.debug("Heartbeat report failed: {}", e.getMessage());
            onHeartbeatReportFailure(HealthResponse.SYSTEM_ERROR);
        }
    }

    @Override
    public void onStop() {
        if (nodeListener != null) {
            context.getRegister().getCurrentNode().removeNodeListener(nodeListener);
            nodeListener = null;
        }
        if (healthClient != null) {
            healthClient.stop();
        }
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdownNow();
            heartbeatScheduler = null;
        }
        heartbeatReportListeners.clear();
        consecutiveFailures.set(0);
        heartbeatHealthy.set(true);
        log.info("DefaultHeartbeatReporter stopped");
    }

    @Override
    public long serverTime() {
        if (healthClient != null) {
            return healthClient.serverTime();
        }
        return -1;
    }

    @Override
    public boolean isHeartbeatHealthy() {
        return heartbeatHealthy.get();
    }

    @Override
    public void addHeartbeatReportListener(HeartbeatReportListener listener) {
        if (listener != null) {
            heartbeatReportListeners.add(listener);
        }
    }

    @Override
    public void removeHeartbeatReportListener(HeartbeatReportListener listener) {
        if (listener != null) {
            heartbeatReportListeners.remove(listener);
        }
    }

    @Override
    public void addMasterChangeListener(MasterChangeListener listener) {
        if (healthClient != null) {
            healthClient.addMasterChangeListener(listener);
            log.info("addMasterChangeListener added: {}", listener);
        }
    }

    @Override
    public void removeMasterChangeListener(MasterChangeListener listener) {
        if (healthClient != null) {
            healthClient.removeMasterChangeListener(listener);
            log.info("removeMasterChangeListener removed: {}", listener);
        }
    }

    protected long heartbeatIntervalMs() {
        return DEFAULT_HEARTBEAT_INTERVAL_MS;
    }

    protected int heartbeatFailureThreshold() {
        return DEFAULT_FAILURE_THRESHOLD;
    }

    private void onHeartbeatReportFailure(HealthResponse response) {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= heartbeatFailureThreshold() && heartbeatHealthy.compareAndSet(true, false)) {
            for (HeartbeatReportListener listener : heartbeatReportListeners) {
                try {
                    listener.onHeartbeatReportFailed(failures);
                } catch (Exception e) {
                    log.warn("Heartbeat failure listener error", e);
                }
            }
        }
    }

    private void onHeartbeatReportSuccess() {
        int previousFailures = consecutiveFailures.getAndSet(0);
        if (previousFailures > 0 && heartbeatHealthy.compareAndSet(false, true)) {
            for (HeartbeatReportListener listener : heartbeatReportListeners) {
                try {
                    listener.onHeartbeatReportRecovered();
                } catch (Exception e) {
                    log.warn("Heartbeat recovery listener error", e);
                }
            }
        }
    }
}
