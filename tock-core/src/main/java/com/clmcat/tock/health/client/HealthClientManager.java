package com.clmcat.tock.health.client;

import com.clmcat.tock.health.HealthHost;
import com.clmcat.tock.registry.TockRegister;
import com.clmcat.tock.utils.NetworkUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * HealthClient 管理器，提供自动地址发现、连接重建与健康上报能力。
 * <p>
 * 使用方式：
 * <pre>{@code
 * HealthClientManager manager = new HealthClientManager(register);
 * manager.start(); // 开始自动维护与 Master 健康服务的连接
 * manager.reportActive(nodeId); // 上报心跳，失败时会自动重连
 * }</pre>
 */
@Slf4j
public class HealthClientManager {

    private final TockRegister register;
    private final ScheduledExecutorService scheduler;
    private final AtomicReference<HealthClient> clientRef = new AtomicReference<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile HealthHost lastHealthHost;
    private Set<MasterChangeListener> masterChangeListeners = ConcurrentHashMap.newKeySet();

    public void addMasterChangeListener(MasterChangeListener masterChangeListener) {
        masterChangeListeners.add(masterChangeListener);
    }

    public void removeMasterChangeListener(MasterChangeListener masterChangeListener) {
        masterChangeListeners.remove(masterChangeListener);
    }

    // 健康服务地址属性名
    private static final String HEALTH_HOST_KEY = "health.host";

    public HealthClientManager(TockRegister register) {
        this.register = register;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "health-client-mgr");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 启动管理器，后台线程每隔一定时间检查连接状态并自动重连。
     */
    public void start() {
        if (!running.compareAndSet(false, true)) return;
        // 立即尝试建立连接
        ensureConnection();
        // 定期维护连接（检测断连 + 地址变更）
        scheduler.scheduleWithFixedDelay(this::maintainConnection, 2, 2, TimeUnit.SECONDS);
    }

    /**
     * 停止管理器，关闭当前连接和后台线程。
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        scheduler.shutdownNow();
        closeCurrentClient();
    }

    /**
     * 上报节点活跃心跳，失败时会自动触发重连。
     *
     * @param nodeId 当前节点的唯一标识
     */
    public HealthResponse reportActive(String nodeId) {
        HealthClient client = clientRef.get();
        if (client != null) {
            try {
                HealthResponse resp = client.reportActive(nodeId);
                if (resp == null || resp.getCode() != 0) {
                    log.warn("Heartbeat report failed: {}", resp);
                    closeCurrentClient(); // 标记失效，等待下次重连
                }
                return resp == null ? HealthResponse.NOT_FOUND : resp;
            } catch (Exception e) {
                log.warn("Heartbeat report exception", e);
                closeCurrentClient();
                return HealthResponse.SYSTEM_ERROR;
            }
        } else {
            log.warn("No active health client, will retry on next maintenance cycle.");
            return HealthResponse.NOT_FOUND;
        }
    }

    /**
     * 从 Master 健康服务获取当前时间（毫秒）。
     * 如果连接不可用或调用失败，返回值为 -1 表示获取失败，
     * 调用方可根据需要降级为本地系统时间。
     */
    public long serverTime() {
        HealthClient client = clientRef.get();
        if (client != null) {
            long time = client.serverTime();
            if (time != -1) {
                return time;
            }
        }
        return -1; // 获取失败，明确通知调用方
    }

    // ==================== 内部逻辑 ====================

    /**
     * 维护连接：检测地址变更、连接有效性，必要时重建。
     */
    private void maintainConnection() {
        if (!running.get()) return;
        try {
            HealthHost currentHost = resolveHealthHost();
            if (currentHost == null) {
                disconnectIfNoHost();
                return;
            }

            // 检测 Master 是否切换（key 不同即为切换）
            if (lastHealthHost != null && !Objects.equals(currentHost.getKey(), lastHealthHost.getKey())) {
                log.info("Master changed detected: old key={}, new key={}", lastHealthHost.getKey(), currentHost.getKey());
                // 关闭旧连接
                closeCurrentClient();
                // 通知监听器
                notifyMasterChanged(lastHealthHost, currentHost);
            }
            lastHealthHost = currentHost;

            // 如果连接已断开，尝试重连
            if (currentClientBroken()) {
                String ip = probeReachableIp(currentHost);
                if (ip != null) {
                    connectToHost(currentHost, ip);
                }
            }
        } catch (Exception e) {
            log.error("Health connection maintenance error", e);
        }
    }

    /**
     * 确保连接存在（阻塞直到成功或上下文停止）。
     */
    private void ensureConnection() {
        try {
            HealthHost host = resolveHealthHost();
            if (host == null) return;
            String ip = probeReachableIp(host);
            if (ip != null) {
                connectToHost(host, ip);
            }
        } catch (Exception e) {
            log.error("Initial connection to health server failed", e);
        }
    }

    private HealthHost resolveHealthHost() {
        try {
            return register.getGroupAttribute(HEALTH_HOST_KEY, HealthHost.class);
        } catch (Exception e) {
            log.debug("Failed to read health.host from register", e);
            return null;
        }
    }

    private String probeReachableIp(HealthHost host) {
        if (host.getHosts() == null || host.getHosts().isEmpty()) return null;
        return NetworkUtils.probeReachableIp(host.getHosts(), host.getPort(), 1000);
    }

    private void connectToHost(HealthHost host, String ip) {
        HealthClient newClient = new HealthClient(ip, host.getPort()) {
            @Override
            protected void onConnectionBroken() {
                // 连接断开后，外部可以感知，管理器会在后续周期自动清理并重连
                log.warn("Health connection to {}:{} broken", ip, host.getPort());
            }
        };
        try {
            newClient.start();
            HealthClient old = clientRef.getAndSet(newClient);
            if (old != null) {
                old.stop(); // 关闭旧连接
            }
        } catch (IOException e) {
            log.error("Failed to start health client for {}:{}", ip, host.getPort(), e);
        }
    }

    private boolean currentClientBroken() {
        HealthClient client = clientRef.get();
        return client == null || !client.isStarted();
    }

    private void closeCurrentClient() {
        HealthClient old = clientRef.getAndSet(null);
        if (old != null) {
            old.stop();
        }
    }

    private void disconnectIfNoHost() {
        HealthClient client = clientRef.get();
        if (client != null && !client.isStarted()) {
            // 已经有断连，但无可用 host，可以保留当前 null 状态
            closeCurrentClient();
        }
    }

    private void notifyMasterChanged(HealthHost oldHost, HealthHost newHost) {
        for (MasterChangeListener listener : masterChangeListeners) {
            try {
                listener.onMasterChanged(oldHost, newHost);
            } catch (Exception e) {
                log.warn("Listener error", e);
            }
        }
    }
}
