package com.clmcat.tock.worker;

import com.clmcat.tock.TockContext;
import com.clmcat.tock.job.JobContext;
import com.clmcat.tock.job.JobExecutor;
import com.clmcat.tock.job.JobRegistry;
import com.clmcat.tock.registry.NodeListener;
import com.clmcat.tock.registry.TockRegister;
import com.clmcat.tock.schedule.ScheduleConfig;
import com.clmcat.tock.schedule.ScheduleExecutionGuard;
import com.clmcat.tock.store.JobExecution;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class DefaultTockWorker implements TockWorker {
    /**
     * 拉取模式（PullableWorkerQueue）下，每次 poll 操作的超时时间（毫秒）。
     * 超时后返回 null，进入空闲判断逻辑。
     */
    private static final long POLL_TIMEOUT_MS = 200;
    /**
     * 空闲阈值：连续多少次 poll 返回 null 后，进入短暂休眠，避免空轮询占用 CPU。
     */
    private static final int IDLE_THRESHOLD = 5;
    /**
     * 空闲休眠时间（毫秒）：当连续 IDLE_THRESHOLD 次 poll 无任务时，休眠该时长。
     */
    private static final long IDLE_SLEEP_MS = 100;


    private TockContext context;
    private AtomicBoolean started = new AtomicBoolean(false);
    private AtomicBoolean running = new AtomicBoolean(false);
    private WorkerQueue workerQueue;
    private JobRegistry jobRegistry;
    private TockRegister register;
    private final Map<String, Future<?>> pullFutures = new ConcurrentHashMap<>();
    private final Set<String> groups = ConcurrentHashMap.newKeySet();

    private final Map<String, Map<String, Future<?>>> executeJobFutures = new ConcurrentHashMap<>();
    private final Map<String, Map<String, JobExecution>> pendingExecutions = new ConcurrentHashMap<>();
    private NodeListener nodeListener;

    public static DefaultTockWorker create() {
        return new DefaultTockWorker();
    }

    @Override
    public void init(TockContext context) {
        this.context = context;
        this.workerQueue = context.getWorkerQueue();
        this.jobRegistry = context.getJobRegistry();
        this.register = context.getRegister();
    }

    @Override
    public synchronized void start(TockContext context) {
        if (started.compareAndSet(false, true)) {
            register.getCurrentNode().addNodeListener(nodeListener = new NodeListener() {
                @Override
                public void onRunning() {
                    resume();
                }
            });
            log.info("DefaultTockWorker started");
        } else {
            log.warn("DefaultTockWorker already started");
        }
    }

    protected void resume() {
        if (!isStarted()) {
            return;
        }
        if (running.compareAndSet(false, true)) {
            // 恢复之前已加入的组（例如 stop 后重新 start）
            for (String group : groups) {
                startConsume(group);
            }
        }
    }

    protected void pause(boolean force) {
        if (running.compareAndSet(true, false)) {
            if (workerQueue != null && workerQueue instanceof SubscribableWorkerQueue) {
                for (String group : groups) {
                    ((SubscribableWorkerQueue) workerQueue).unsubscribe(group);
                }
            }
            pullFutures.values().forEach(f -> f.cancel(force));
            pullFutures.clear();
            executeJobFutures.forEach((group, jobFutures) -> {
                jobFutures.forEach((k, v) -> v.cancel(force));
            });
            executeJobFutures.clear();
            requeueAllPendingExecutions();
        }
    }


    @Override
    public synchronized void stop() {
        if (started.compareAndSet(true, false)) {
            log.info("DefaultTockWorker stopped - 0");
            register.getCurrentNode().removeNodeListener(nodeListener);
            // 对于订阅模式，取消所有订阅
            pause(true);
            log.info("DefaultTockWorker stopped - 1");
        } else {
            log.warn("DefaultTockWorker already stopped");
        }
    }

    @Override
    public boolean isStarted() {
        return started.get();
    }

    @Override
    public void joinGroup(String groupName) {

        if (this.workerQueue == null) {
            throw new IllegalStateException("workerQueue is null");
        }
        if  (this.jobRegistry == null) {
            throw new IllegalStateException("jobRegistry is null");
        }
        if (!groups.add(groupName)) {
            // 已经加入过，忽略
            log.warn("Group {} already exists", groupName);
            return;
        }
        log.info("DefaultTockWorker joinGroup");
        // 只有Worker真实运行才允许Join组
        if (isStarted() && running.get()) {
            startConsume(groupName);
        }
    }

    // 提取公共消费启动逻辑
    private void startConsume(String groupName) {
        log.info("DefaultTockWorker startConsume: {}", groupName);
        if (workerQueue instanceof SubscribableWorkerQueue) {
            ((SubscribableWorkerQueue) workerQueue).subscribe(groupName, this::executeJob);
        } else if (workerQueue instanceof PullableWorkerQueue) {
            executePollJob(groupName, (PullableWorkerQueue) workerQueue);
        } else {
            // 需要实现 SubscribableWorkerQueue 或 PullableWorkerQueue
            throw new UnsupportedOperationException("Not supported yet. Please use SubscribableWorkerQueue or PullableWorkerQueue");
        }
    }

    void executePollJob(String groupName, PullableWorkerQueue  pollableWorkerQueue) {
        if (!isStarted()) {
            log.warn("DefaultTockWorker not started, executePollJob failed");
            return;
        }
        Future<?> submit = context.getConsumerExecutor().submit(() -> {
            int count = 0;
            while (isStarted() && !Thread.currentThread().isInterrupted()) {
                try {
                    JobExecution poll = ((PullableWorkerQueue) workerQueue).poll(groupName, POLL_TIMEOUT_MS);
                    if (poll != null) {
                        count = 0;
                        executeJob(poll);
                    } else if (count++ > IDLE_THRESHOLD) {
                        // 连续多次没有任务，休息一下，避免空轮询
                        Thread.sleep(IDLE_SLEEP_MS);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); break;
                } catch (Exception e) {
                    log.error("DefaultTockWorker poll failed, groupName: {}", groupName, e);
                }
            }
        });
        Future<?> future = pullFutures.put(groupName, submit);
        if (future != null) {
            future.cancel(true);
        }
    }

    void executeJob(JobExecution jobExecution) {
        if (!isStarted()) {
            return;
        }
        trackPendingExecution(jobExecution);
        long now = context.currentTimeMillis();
        long nextFireTime = jobExecution.getNextFireTime();
        long delayNs = (Math.max(nextFireTime - now, 0) * 1_000_000) - 500_000;
        onExecutionReceived(jobExecution, now, nextFireTime, delayNs);

        log.debug("executeJob({}), currentTime: {}, registerSyncTime:{}, fireTime:{}, (delay:{} ms)", jobExecution.getExecutionId(),
                System.currentTimeMillis(), context.currentTimeMillis(), nextFireTime, delayNs / 1_000_000);

        if (delayNs <= 0) {
            onExecutionScheduled(jobExecution, delayNs, true);
            Future<?> future = context.getWorkerExecutor().submit(() -> executeWhenDue(jobExecution));
            trackExecutionFuture(jobExecution, future);
        } else {
            onExecutionScheduled(jobExecution, delayNs, false);
            Future<?> schedule = context.getWorkerExecutor().schedule(() -> executeWhenDue(jobExecution), delayNs, TimeUnit.NANOSECONDS);
            trackExecutionFuture(jobExecution, schedule);
        }
    }

    private void executeWhenDue(JobExecution jobExecution) {
        if (!isStarted()) {
            return;
        }
        onExecutionDue(jobExecution, context.currentTimeMillis());
        doExecuteJob(jobExecution);
    }

    private void trackExecutionFuture(JobExecution jobExecution, Future<?> future) {
        executeJobFutures.computeIfAbsent(jobExecution.getWorkerGroup(), k -> new ConcurrentHashMap<>())
                .put(jobExecution.getExecutionId(), future);
    }

    void doExecuteJob(JobExecution jobExecution) {
        String scheduleId = jobExecution.getScheduleId();
        String executionId = jobExecution.getExecutionId();
        long nextFireTime = jobExecution.getNextFireTime();
        long currentTimeMillis = context.currentTimeMillis();
        log.debug("doExecuteJob({})-0, syncTime:{}, fireTime:{}" , executionId, currentTimeMillis, nextFireTime);

        String jobId = jobExecution.getJobId();
        String workerGroup = jobExecution.getWorkerGroup();
        String nodeId = register.getCurrentNode().getId(); // 当前节点ID
        String workerGroupScheduleId = WorkerExecutionKeys.activeKey(workerGroup, scheduleId);

        JobExecutor jobExecutor = jobRegistry.get(jobId);
        if (jobExecutor == null) {
            log.error("DefaultTockWorker jobExecutor is null, jobId: {}", jobId);
            return;
        }

        if (!register.setGroupAttributeIfAbsent(workerGroupScheduleId, new WorkerExecutionLease(nodeId, executionId))) {
            clearPendingExecution(jobExecution);
            removeScheduledExecution(jobExecution);
            log.warn("DefaultTockWorker job already being executed by another worker, workerGroup{}, scheduleId: {}", workerGroup, scheduleId);
            return;
        }
        if (Thread.currentThread().isInterrupted()) {
            cleanupExecution(jobExecution, workerGroupScheduleId);
            return;
        }

        try {
            log.debug("doExecuteJob({})-2, syncTime:{}, fireTime:{}" , executionId, context.currentTimeMillis(), nextFireTime);
            register.setNodeAttributeIfAbsent(workerGroupScheduleId, jobExecution);
            clearPendingExecution(jobExecution);
            log.debug("doExecuteJob({})-3, syncTime:{}, fireTime:{}" , executionId, context.currentTimeMillis(), nextFireTime);
            // long remaining = jobExecution.getNextFireTime() - context.currentTimeMillis();

            JobContext jobContext = JobContext.builder()
                    .scheduleId(scheduleId)
                    .jobId(jobId)
                    .scheduledTime(nextFireTime)
                    .actualFireTime(currentTimeMillis)
                    .params(jobExecution.getParams()).build();


            jobExecutor.execute(jobContext);
        } catch (Exception e) {
            log.error("DefaultTockWorker jobExecutor exception, jobExecution: {}", jobExecution, e);
        } finally {
            cleanupExecution(jobExecution, workerGroupScheduleId);
        }
    }


    @Override
    public void leaveGroup(String groupName) {
        if (!isStarted()) return;
        if (!groups.remove(groupName)) return;
        log.info("DefaultTockWorker leaveGroup");
        if (this.workerQueue == null) {
            throw new IllegalStateException("workerQueue is null");
        }
        if (workerQueue instanceof SubscribableWorkerQueue) {
            ((SubscribableWorkerQueue) workerQueue).unsubscribe(groupName);
        } else if (workerQueue instanceof PullableWorkerQueue) {
            Future<?> future = pullFutures.remove(groupName);
            if (future != null) future.cancel(true);
        } else {
            // 需要实现 SubscribableWorkerQueue 或 PullableWorkerQueue
            throw new UnsupportedOperationException("Not supported yet. Please use SubscribableWorkerQueue or PullableWorkerQueue");
        }
        requeueGroupExecutions(groupName);
    }

    ///  标记等待执行， 已经接受了任务
    private void trackPendingExecution(JobExecution jobExecution) {
        if (!isPendingRecoveryEnabled()) { // config 需要配置 true
            return;
        }
        pendingExecutions.computeIfAbsent(jobExecution.getWorkerGroup(), k -> new ConcurrentHashMap<>())
                .put(jobExecution.getExecutionId(), jobExecution);
        String pendingKey = WorkerExecutionKeys.pendingKey(jobExecution);
        if (!register.setNodeAttributeIfAbsent(pendingKey, jobExecution)) {
            log.debug("Pending execution key already exists: {}", pendingKey);
        }
    }

    private void clearPendingExecution(JobExecution jobExecution) {
        Map<String, JobExecution> executionMap = pendingExecutions.get(jobExecution.getWorkerGroup());
        if (executionMap != null) {
            executionMap.remove(jobExecution.getExecutionId());
            if (executionMap.isEmpty()) {
                pendingExecutions.remove(jobExecution.getWorkerGroup());
            }
        }
        if (isPendingRecoveryEnabled()) {
            register.removeNodeAttribute(WorkerExecutionKeys.pendingKey(jobExecution));
        }
    }

    private void cleanupExecution(JobExecution jobExecution, String activeKey) {
        clearPendingExecution(jobExecution);
        register.removeNodeAttribute(activeKey);
        register.removeGroupAttribute(activeKey);
        removeScheduledExecution(jobExecution);
    }

    private void removeScheduledExecution(JobExecution jobExecution) {
        Map<String, Future<?>> futureMap = executeJobFutures.get(jobExecution.getWorkerGroup());
        if (futureMap != null) {
            futureMap.remove(jobExecution.getExecutionId());
            if (futureMap.isEmpty()) {
                executeJobFutures.remove(jobExecution.getWorkerGroup());
            }
        }
    }

    private void requeueGroupExecutions(String groupName) {
        Map<String, JobExecution> executions = pendingExecutions.remove(groupName);
        Map<String, Future<?>> futures = executeJobFutures.remove(groupName);
        if (executions == null || executions.isEmpty()) {
            if (futures != null) {
                futures.values().forEach(f -> f.cancel(true));
            }
            return;
        }
        for (Map.Entry<String, JobExecution> entry : executions.entrySet()) {
            JobExecution execution = entry.getValue();
            register.removeNodeAttribute(WorkerExecutionKeys.pendingKey(execution));
            Future<?> future = futures == null ? null : futures.remove(entry.getKey());
            if (future != null) {
                future.cancel(true);
            }
            workerQueue.push(execution, groupName);
        }
        if (futures != null) {
            futures.values().forEach(f -> f.cancel(true));
        }
    }

    private void requeueAllPendingExecutions() {
        for (String group : new java.util.ArrayList<>(pendingExecutions.keySet())) {
            requeueGroupExecutions(group);
        }
    }

    private boolean isExecutionStillValid(JobExecution execution) {
        ScheduleConfig config = context.getScheduleStore().get(execution.getScheduleId());
        return ScheduleExecutionGuard.isExecutionStillValid(config, execution, context.currentTimeMillis());
    }

    private boolean isPendingRecoveryEnabled() {
        return context != null
                && context.getConfig() != null
                && context.getConfig().isPendingExecutionRecoveryEnabled();
    }

    protected void onExecutionReceived(JobExecution jobExecution, long currentTimeMs, long nextFireTimeMs, long delayMs) {
    }

    protected void onExecutionScheduled(JobExecution jobExecution, long delayMs, boolean immediate) {
    }

    protected void onScheduledCallback(JobExecution jobExecution, long remainingMs) {
    }

    protected void onExecutionRescheduled(JobExecution jobExecution, long remainingMs) {
    }

    protected void onExecutionDue(JobExecution jobExecution, long currentTimeMs) {
    }

    @Override
    public Set<String> getGroups() {
        return Collections.unmodifiableSet(groups);
    }
}
