package com.clmcat.tock.health;

import com.clmcat.tock.ResumableLifecycle;
import com.clmcat.tock.TockContext;
import com.clmcat.tock.TockContextAware;
import com.clmcat.tock.health.client.HealthClientManager;
import com.clmcat.tock.health.client.MasterChangeListener;
import com.clmcat.tock.registry.NodeListener;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
public class DefaultHeartbeatReporter extends ResumableLifecycle.AbstractResumableLifecycle implements HeartbeatReporter {


    private HealthClientManager healthClient;
    private NodeListener nodeListener;
    private ScheduledExecutorService heartbeatScheduler;
    private ScheduledFuture<?> heartbeatTask;


    @Override
    protected void onInit() {
        // 创建并启动 HealthClientManager（自动维护与 Master 的连接）
        this.healthClient = new HealthClientManager(context.getRegister());
    }

    @Override
    public void onStart() {

        this.healthClient.start();

        // 创建心跳上报的定时线程
        this.heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, context.getNamespace() + "-heartbeat-reporter");
            t.setDaemon(true);
            return t;
        });

        // 监听当前节点的状态变化
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

        // 启动时立即开始上报
        resume();

    }

    /**
     * 暂停心跳上报。
     */
    protected void onPause(boolean force) {

        if (heartbeatTask != null) {
            heartbeatTask.cancel(true);
            heartbeatTask = null;
        }
        log.info("Heartbeat reporting paused");

    }

    /**
     * 恢复心跳上报。
     */
    protected void onResume() {
        // 每秒上报一次心跳
        reportActive(); // 启动时 直接上报一次，快速让 Master 知道我在线。
        heartbeatTask = heartbeatScheduler.scheduleWithFixedDelay(() -> {
            reportActive();
        }, 0, 500, TimeUnit.MILLISECONDS);
        log.info("Heartbeat reporting resumed");

    }

    protected void reportActive() {
        try {
            String nodeId = context.getRegister().getCurrentNode().getId();
            healthClient.reportActive(nodeId);
        } catch (Exception e) {
            log.debug("Heartbeat report failed: {}", e.getMessage());
        }
    }

    @Override
    public void onStop() {

        if (nodeListener != null) {
            context.getRegister().getCurrentNode().removeNodeListener(nodeListener);
        }

        if (healthClient != null) {
            healthClient.stop();
        }

        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdownNow();
        }
        log.info("DefaultHeartbeatReporter stopped");

    }

    /**
     * 直接获取 Master 时间（供其他组件使用）。
     * 返回 -1 表示获取失败，调用方应降级为本地时间。
     */
    @Override
    public long serverTime() {
        if (healthClient != null) {
            return healthClient.serverTime();
        }
        return -1;
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
}