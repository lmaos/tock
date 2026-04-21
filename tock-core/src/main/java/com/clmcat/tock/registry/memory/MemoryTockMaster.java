package com.clmcat.tock.registry.memory;

import com.clmcat.tock.TockContext;
import com.clmcat.tock.registry.MasterListener;
import com.clmcat.tock.registry.TockMaster;
import com.clmcat.tock.money.MemoryManager;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class MemoryTockMaster implements TockMaster {

    private MemoryManager memoryManager;
    private TockContext tockContext;
    private AtomicBoolean master = new AtomicBoolean(false);
    private final String masterName;
    private Set<MasterListener> listeners = ConcurrentHashMap.newKeySet();
    private ScheduledFuture<?> startFuture;
    // 续租的时间。
    private long leaseTime;
    private final long leaseTimeoutMs;     // 租约超时时间，默认3000
    private final long heartbeatIntervalMs; // 续期间隔，默认1000
    // -1 永远重试，0 不重试, >0 重试次数
    private long maxRetryCount = -1;

    private ScheduledExecutorService task;

    public MemoryTockMaster(String masterName, MemoryManager memoryManager) {
        this.masterName = masterName;
        this.leaseTimeoutMs = 3000;
        this.heartbeatIntervalMs = 1000;
        this.maxRetryCount = -1;
        this.memoryManager = memoryManager;
    }
    public MemoryTockMaster(MemoryMasterConfig config, MemoryManager memoryManager) {
        this.masterName = config.getMasterName();
        this.leaseTimeoutMs = config.getLeaseTimeoutMs();
        this.heartbeatIntervalMs = config.getHeartbeatIntervalMs();
        this.maxRetryCount = config.getMaxRetryCount();
        this.memoryManager = memoryManager;
    }

    @Override
    public boolean isMaster() {
        return this.master.get();
    }

    @Override
    public void addListener(MasterListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeListener(MasterListener listener) {
        listeners.remove(listener);
    }

    private void onBecomeMaster() {
        LinkedList<MasterListener> activeListeners = new LinkedList<>(listeners);
        onBecomeMaster(activeListeners, 0);
    }

    private void onLoseMaster() {
        LinkedList<MasterListener> activeListeners = new LinkedList<>();
        activeListeners.addAll(listeners);
        onLoseMaster(activeListeners, 0);
    }

    private void onBecomeMaster(LinkedList<MasterListener> activeListeners, int retryCount) {

        if (activeListeners.isEmpty() || !isMaster()) {
            return;
        }

        if (maxRetryCount >= 0 && retryCount > maxRetryCount) {
            log.warn("onBecomeMaster Max retry {} exceeded, giving up", maxRetryCount);
            return;
        }

        Iterator<MasterListener> iterator = activeListeners.iterator();
        while (iterator.hasNext()) {
            MasterListener listener = iterator.next();
            try {
                if (isMaster()) {
                    log.info("onBecomeMaster start, count:{}", retryCount);
                    listener.onBecomeMaster();
                }
                iterator.remove();
            } catch (Exception e) {
                log.error("onBecomeMaster error, count:{}", retryCount, e);
            }

        }
        if (!activeListeners.isEmpty()) {
            // 发生重试:
            task.schedule(() -> onBecomeMaster(activeListeners, retryCount + 1), Math.min(Math.max(retryCount, 1), 5), TimeUnit.SECONDS);
        }
    }

    private void onLoseMaster(LinkedList<MasterListener> activeListeners, int retryCount) {

        if (activeListeners.isEmpty() || isMaster()) {
            return;
        }

        if (maxRetryCount >= 0 && retryCount > maxRetryCount) {
            log.warn("onLoseMaster Max retry {} exceeded, giving up", maxRetryCount);
            return;
        }

        Iterator<MasterListener> iterator = activeListeners.iterator();
        while (iterator.hasNext()) {
            MasterListener listener = iterator.next();
            try {
                if (!isMaster()) {
                    log.info("onLoseMaster start, count:{}", retryCount);
                    listener.onLoseMaster();
                }
                iterator.remove();
            } catch (Exception e) {
                log.error("onLoseMaster error, count:{}", retryCount, e);
            }
        }
        if (!activeListeners.isEmpty()) {
            // 发生重试:
            task.schedule(()-> onLoseMaster(activeListeners, retryCount + 1), Math.min(Math.max(retryCount, 1), 5), TimeUnit.SECONDS);
        }
    }

    void selectMaster() {
        final MemoryTockMaster[] lostMasterHolder = new MemoryTockMaster[1];
        memoryManager.getMasterMap().compute(masterName, (key, existingMaster) -> {
            long now = currentTimeMillis();
            if (existingMaster == null || (now - existingMaster.leaseTime) > leaseTimeoutMs) {
                if (existingMaster != null && existingMaster != this) {
                    lostMasterHolder[0] = existingMaster;
                }
                this.master.set(true);
                this.leaseTime = now;
                log.info("Became master: {}", masterName);
                onBecomeMaster();
                return this; // 新的主机
            } else if (this == existingMaster) { // 我是我。
                this.master.set(true);
                this.leaseTime = now;
            } else if (this.master.get()) { // 我不是我了。
                log.warn("Lost master: {}", masterName);
                this.master.set(false);
                onLoseMaster();
            }
            return existingMaster; // 还是这个主机
        });
        MemoryTockMaster lostMaster = lostMasterHolder[0];
        if (lostMaster != null && lostMaster.master.compareAndSet(true, false)) {
            lostMaster.onLoseMaster();
        }
    }

    private long currentTimeMillis() {
        return tockContext == null ? System.currentTimeMillis() : tockContext.currentTimeMillis();
    }


    @Override
    public void start(TockContext context) {
        if (startFuture == null) {
            this.tockContext = context;
            task = createScheduler();
            selectMaster();
            startFuture = task.scheduleWithFixedDelay(this::selectMaster , heartbeatIntervalMs, heartbeatIntervalMs, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void stop() {
        if  (startFuture != null) {
            startFuture.cancel(true);
            startFuture = null;
            if (task != null) {
                task.shutdownNow();
                task = null;
            }

            if (isMaster()) {
                // 我是主机，让我变成不是主机
                this.master.set(false);
                // 使我在是主机了。
                memoryManager.getMasterMap().remove(masterName, this);
                // 通知监听器我不是主机了。
                onLoseMaster();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return startFuture != null && !startFuture.isCancelled();
    }


    public String getMasterName() {
        return masterName;
    }

    long getMaxRetryCount() {
        return maxRetryCount;
    }

    // 配置类
    @Getter
    public static class MemoryMasterConfig {
        private final String masterName;
        private final long maxRetryCount;      // -1:无限重试, 0:不重试, >0:最多重试次数
        private final long leaseTimeoutMs;     // 租约超时时间，默认3000
        private final long heartbeatIntervalMs; // 续期间隔，默认1000

        private MemoryMasterConfig(Builder builder) {
            this.masterName = builder.masterName;
            this.maxRetryCount = builder.maxRetryCount;
            this.leaseTimeoutMs = builder.leaseTimeoutMs;
            this.heartbeatIntervalMs = builder.heartbeatIntervalMs;
        }


        public static Builder builder(String masterName) {
            return new Builder(masterName);
        }

        public static class Builder {
            private final String masterName;
            private long maxRetryCount = 0;
            private long leaseTimeoutMs = 3000;
            private long heartbeatIntervalMs = 1000;

            public Builder maxRetryCount(long maxRetryCount) { this.maxRetryCount = maxRetryCount; return this; }
            public Builder leaseTimeoutMs(long leaseTimeoutMs) { this.leaseTimeoutMs = leaseTimeoutMs; return this; }
            public Builder heartbeatIntervalMs(long heartbeatIntervalMs) { this.heartbeatIntervalMs = heartbeatIntervalMs; return this; }
            public MemoryMasterConfig build() { return new MemoryMasterConfig(this); }
            Builder(String masterName) {
                this.masterName = masterName;
            }
        }
    }

    private ScheduledExecutorService createScheduler() {
        ThreadFactory factory = r -> {
            Thread thread = new Thread(r, "tock-memory-master");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadScheduledExecutor(factory);
    }
}
