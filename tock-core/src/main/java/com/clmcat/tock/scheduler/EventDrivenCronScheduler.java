package com.clmcat.tock.scheduler;

import com.clmcat.tock.Lifecycle;
import com.clmcat.tock.TockContext;
import com.clmcat.tock.TockContextAware;
import com.clmcat.tock.cron.CronCalculators;
import com.clmcat.tock.cron.CronCalculator;
import com.clmcat.tock.registry.MasterListener;
import com.clmcat.tock.registry.TockNode;
import com.clmcat.tock.registry.TockRegister;
import com.clmcat.tock.schedule.ScheduleConfig;
import com.clmcat.tock.schedule.ScheduleExecutionGuard;
import com.clmcat.tock.schedule.ScheduleStore;
import com.clmcat.tock.store.JobExecution;
import com.clmcat.tock.worker.WorkerExecutionKeys;
import com.clmcat.tock.worker.WorkerExecutionLease;
import com.clmcat.tock.worker.WorkerQueue;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class EventDrivenCronScheduler implements TockScheduler, Lifecycle, TockContextAware {

    private static final long DEFAULT_CONFIG_REFRESH_INTERVAL_MS = 10000;
    private static final long CLEANER_INTERVAL_MS = 1000; // 1秒
    private static final long ADVANCE_THRESHOLD_MS = 1000; // 超过此阈值，提前x秒；否则提前1毫秒
    private static final long ADVANCE_LONG_MS = 1000;      // 提前 x 秒
    private static final long ADVANCE_SHORT_MS = 15;        // 提前 x 毫秒
    private ScheduleStore scheduleStore;
    private WorkerQueue workerQueue;
    private TockRegister register;
    private AtomicBoolean started = new AtomicBoolean(false);
    private AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong lastConfigVersion = new AtomicLong(-1);
    private volatile Map<String, ScheduleConfig> cachedConfigMap = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, ScheduledFuture<?>> scheduledFutures = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> lastFireTimeLocalCache = new ConcurrentHashMap<>();


    private ScheduledFuture<?> startWorkerNodeCleanerFuture;
    private ScheduledFuture<?> startConfigRefreshFuture;
    private TockContext context;

    private MasterListener masterListener;


    public static EventDrivenCronScheduler create() {
        return new EventDrivenCronScheduler();
    }

    @Override
    public void setTockContext(TockContext context) {
        this.scheduleStore = context.getScheduleStore();
        this.workerQueue = context.getWorkerQueue();
        this.register = context.getRegister();
        this.context = context;
    }

    @Override
    public void start(TockContext context) {
        if (!started.compareAndSet(false, true)) return;

        register.getMaster().addListener(masterListener = new MasterListener() {
            @Override
            public void onBecomeMaster() {
                resume();
            }

            @Override
            public void onLoseMaster() {
                pause();
            }
        });

        log.info("CronScheduler started");
    }


    protected void resume() {
        if (!this.isStarted()) return;

        if (running.compareAndSet(false, true)) {
            // 默认先进行执行。
            startWorkerNodeCleaner();
            // 直接刷新一次配置， 避免等待定时器第一次触发。
            refreshSchedulesIfNeeded();
            if (startConfigRefreshFuture == null) {
                // 配置刷新（低频）
                startConfigRefreshFuture = context.getSchedulerExecutor()
                        .scheduleWithFixedDelay(this::refreshSchedulesIfNeeded,
                                0,
                                DEFAULT_CONFIG_REFRESH_INTERVAL_MS,
                                TimeUnit.MILLISECONDS);
            }
            if (startWorkerNodeCleanerFuture == null) {
                startWorkerNodeCleanerFuture = context.getSchedulerExecutor()
                        .scheduleWithFixedDelay(this::startWorkerNodeCleaner,
                                0,
                                CLEANER_INTERVAL_MS,
                                TimeUnit.MILLISECONDS);
            }
            log.info("CronScheduler resume");
        } else {
            log.info("CronScheduler already resumed");
        }



    }
    protected void pause() {
        if (running.compareAndSet(true, false)) {
            if (startConfigRefreshFuture != null) {
                startConfigRefreshFuture.cancel(true);
                startConfigRefreshFuture = null;

            }
            if (startWorkerNodeCleanerFuture != null) {
                startWorkerNodeCleanerFuture.cancel(true);
                startWorkerNodeCleanerFuture = null;
            }
            for (ScheduledFuture<?> future : scheduledFutures.values()) {
                future.cancel(false);
            }
            scheduledFutures.clear();
            lastFireTimeLocalCache.clear();
            lastConfigVersion.set(-1);
            log.info("CronScheduler paused");
        } else {
            log.info("CronScheduler already paused");
        }
    }

    void push(JobExecution execution) {
        String workerGroup = execution.getWorkerGroup();
        workerQueue.push(execution, workerGroup);
    }

    private void startWorkerNodeCleaner() {
        try {
            ///  获得过期节点
            List<TockNode> expiredNodes = register.getExpiredNodes();
            for (TockNode node : expiredNodes) {
                if (node.getStatus() != TockNode.NodeStatus.ACTIVE) {
                    recoverExpiredNode(node);
                }
            }
        } catch (Exception e) {
            log.error("Scheduler loop error", e);
        }
    }

    void recoverExpiredNode(TockNode node) {
        log.info("Removing expired node: {}", node.getId());
        Set<String> recoveredExecutionIds = new HashSet<>();
        for (String attributeName : node.getAttributeNamesAll()) {
            if (WorkerExecutionKeys.isActiveKey(attributeName)) {
                recoverActiveExecution(node, attributeName, recoveredExecutionIds);
                continue;
            }
            if (WorkerExecutionKeys.isPendingKey(attributeName)) {
                recoverPendingExecution(node, attributeName, recoveredExecutionIds);
            }
        }
        register.removeNode(node.getId());
    }

    private boolean isScheduleParamChanged(ScheduleConfig old, ScheduleConfig newCfg) {
        return !Objects.equals(old.getCron(), newCfg.getCron())
                || !Objects.equals(old.getFixedDelayMs(), newCfg.getFixedDelayMs())
                || old.isEnabled() != newCfg.isEnabled();
    }
    /**
     * 刷新 ScheduleConfig 配置文件
     */
    synchronized void refreshSchedulesIfNeeded() {
        long currentVersion = scheduleStore.getGlobalVersion();
        if (currentVersion == lastConfigVersion.get()) return;

        log.info("Config version changed from {} to {}, reloading", lastConfigVersion.get(), currentVersion);

        Set<String> existingKeys = new HashSet<>(scheduledFutures.keySet());
        Map<String, ScheduleConfig> newConfigMap = new HashMap<>();
        List<ScheduleConfig> toReschedule = new ArrayList<>();

        for (ScheduleConfig newCfg : scheduleStore.getAll()) {
            String sid = newCfg.getScheduleId();
            existingKeys.remove(sid); // 标记存在

            ScheduledFuture<?> oldFuture = scheduledFutures.get(sid);
            if (oldFuture != null) {
                ScheduleConfig oldCfg = cachedConfigMap.get(sid);
                if (isScheduleParamChanged(oldCfg, newCfg)) {
                    oldFuture.cancel(false);
                    scheduledFutures.remove(sid);
                    if (newCfg.isEnabled()) toReschedule.add(newCfg);
                }
            } else if (newCfg.isEnabled()) {
                toReschedule.add(newCfg);
            }

            if (newCfg.isEnabled()) newConfigMap.put(sid, newCfg);
        }

        // 清理已删除的配置
        for (String sid : existingKeys) {
            ScheduledFuture<?> f = scheduledFutures.remove(sid);
            if (f != null) f.cancel(true);
        }

        cachedConfigMap = newConfigMap;
        for (ScheduleConfig cfg : toReschedule) scheduleConfig(cfg);
        lastConfigVersion.set(currentVersion);
    }

    void scheduleConfig(ScheduleConfig config) {
        long now = context.currentTimeMillis();
        scheduleConfig(config, now, now);
    }

    void scheduleConfig(ScheduleConfig config, long nextFireBaseTime, long currentTime) {
        if (!this.isStarted()) return;
        if (config == null || !config.isEnabled()) return;

        String scheduleId = config.getScheduleId();
        long nextFireTime = computeNextFireTime(config, nextFireBaseTime);
        if (nextFireTime > 0) {
            long delay = nextFireTime - currentTime;
            delay = delay < ADVANCE_THRESHOLD_MS ? Math.max(0, delay - ADVANCE_SHORT_MS) : delay - ADVANCE_LONG_MS; // 提前x秒， 避免时间误差导致错过。
            log.debug("scheduleConfig({}) to fireTime at {} (delay {} ms / {}ms); syncTime={} [调度器通过配置进行初始化调度任务， 创建事件驱动的定时器执行调度]",
                    scheduleId, nextFireTime, delay, (nextFireTime - currentTime), currentTime);
            ScheduledFuture<?> schedule = context.getSchedulerExecutor().schedule(() -> onFire(config, nextFireTime), delay, TimeUnit.MILLISECONDS);
            scheduledFutures.put(scheduleId, schedule);
        }

    }

    void onFire(ScheduleConfig snapshot, long nextFireTime) {
        if (!this.isStarted()) return;
        String scheduleId = snapshot.getScheduleId();
        ScheduleConfig latest = cachedConfigMap.get(scheduleId);

        // 如果最新配置不存在或已被禁用，或者调度参数（cron/fixedDelay/enabled）发生变化，则放弃本次执行
        if (latest == null || !latest.isEnabled() || isScheduleParamChanged(snapshot, latest)) {
            return;
        }
        long now = context.currentTimeMillis();

        log.debug("onFire({}) currentTime={}, syncTime={},fireTime={}; (delay:{})", scheduleId, System.currentTimeMillis(), now, nextFireTime, nextFireTime - now);
        // 生成并推送任务
        JobExecution execution = JobExecution.builder()
                .executionId(generateExecutionId(latest))
                .scheduleId(latest.getScheduleId())
                .jobId(latest.getJobId())
                .nextFireTime(nextFireTime)
                .workerGroup(latest.getWorkerGroup())
                .scheduleFingerprint(ScheduleExecutionGuard.fingerprint(latest))
                .params(latest.getParams())
                .build();

        this.push(execution);
        if (latest.getFixedDelayMs() != null && latest.getCron() == null) {
            setLastFireTime(scheduleId, nextFireTime);
        }
        /// 继续执行
        scheduleConfig(latest, nextScheduleBaseTime(nextFireTime), now);
    }

    private long nextScheduleBaseTime(long currentFireTime) {
        return currentFireTime == Long.MIN_VALUE ? Long.MIN_VALUE : currentFireTime - 1L;
    }

    private void setLastFireTime(String scheduleId, long time) {
        lastFireTimeLocalCache.put(scheduleId, time);
        register.setRuntimeState("last_fire:" + scheduleId, String.valueOf(time));
    }

    private Long getLastFireTimeFromCache(String scheduleId) {
        Long last = lastFireTimeLocalCache.get(scheduleId);
        if (last == null) {
            String lastStr = register.getRuntimeState("last_fire:" + scheduleId);
            if (lastStr != null) {
                last = Long.parseLong(lastStr);
                lastFireTimeLocalCache.put(scheduleId, last);
            }
        }
        return last;
    }

    /**
     * 通过配置计算下一次执行的时间点
     * @param config 调度配置
     * @param now 传入时间
     * @return 返回值
     */
    long computeNextFireTime(ScheduleConfig config, long now) {
        if (config.getCron() != null && !config.getCron().isEmpty()) {
            String zoneId = config.getZoneId();
            CronCalculator calculator = CronCalculators.get(zoneId);
            Optional<Long> nextOpt = calculator.getNextExecutionTime(config.getCron(), now);
            return nextOpt.orElse(-1L);
        } else if (config.getFixedDelayMs() != null && config.getFixedDelayMs() > 0) {
            Long last = getLastFireTimeFromCache(config.getScheduleId());
            if (last == null) return now;
            long next = last + config.getFixedDelayMs();
            if (next < now) {
                next = now + config.getFixedDelayMs();
            }
            return next;
        } else {
            return -1L;
        }
    }

    ScheduleConfig findConfigByScheduleId(String scheduleId) {
        return cachedConfigMap.get(scheduleId);
    }

    String generateExecutionId(ScheduleConfig config) {
        return config.getScheduleId() + ":" + UUID.randomUUID().toString();
    }

    private void recoverActiveExecution(TockNode node, String activeKey, Set<String> recoveredExecutionIds) {
        WorkerExecutionLease lease = register.getGroupAttribute(activeKey, WorkerExecutionLease.class);
        JobExecution execution = node.getAttribute(activeKey, JobExecution.class);
        if (execution == null) {
            register.removeGroupAttribute(activeKey);
            return;
        }
        if (lease != null && Objects.equals(lease.getNodeId(), node.getId())) {
            recoveredExecutionIds.add(execution.getExecutionId());
            if (shouldRecover(execution)) {
                push(execution);
            }
            register.removeGroupAttribute(activeKey);
        }
    }

    private void recoverPendingExecution(TockNode node, String pendingKey, Set<String> recoveredExecutionIds) {
        JobExecution execution = node.getAttribute(pendingKey, JobExecution.class);
        if (execution == null || recoveredExecutionIds.contains(execution.getExecutionId())) {
            return;
        }
        if (shouldRecover(execution)) {
            push(execution);
        }
    }

    private boolean shouldRecover(JobExecution execution) {
        if (execution == null) {
            return false;
        }
        ScheduleConfig latest = scheduleStore.get(execution.getScheduleId());
        return ScheduleExecutionGuard.isExecutionStillValid(latest, execution, context.currentTimeMillis());
    }

    @Override
    public void stop() {
        if (started.compareAndSet(true, false)) {
            register.getMaster().removeListener(masterListener);
            pause();
            log.info("CronScheduler stopped");
        }
    }

    @Override
    public boolean isStarted() {
        return started.get();
    }

    @Override
    public void refreshSchedules() {
        refreshSchedulesIfNeeded();
    }

}
