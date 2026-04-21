package com.clmcat.tock.worker.scheduler;

import com.clmcat.tock.TockContext;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * 基于单层时间轮的高精度任务调度器：
 * 1. 槽位固定 1024（1ms/tick），通过 remainingRounds 支持超过单轮的延迟；
 * 2. 驱动线程采用“分段 park + 最终自旋”的混合等待策略，尽量降低唤醒误差；
 * 3. 追赶模式会顺序处理错过的 tick，避免 GC/调度抖动导致任务漏触发；
 * 4. 时间轮线程只负责触发，业务逻辑始终提交到独立执行线程池运行。
 */
@Slf4j
public class HighPrecisionWheelTaskScheduler implements TaskScheduler {
    public static final long DEFAULT_ADVANCE_NANOS = 200_000;
    public static final long DISTRIBUTED_DEFAULT_ADVANCE_NANOS = 1_000_000L;
    private static final int WHEEL_SIZE = 1024;
    private static final int WHEEL_MASK = WHEEL_SIZE - 1;
    private static final int WHEEL_BITS = 10;
    private static final long TICK_DURATION_NANOS = 1_000_000L;
    private static final long COARSE_PARK_NANOS = 10_000_000L; // 10ms
    private static final long SPIN_THRESHOLD_NANOS = 1_000_000L; // 1ms，扩大最终自旋窗口以吸收调度抖动

    private final TaskSlot[] wheel = new TaskSlot[WHEEL_SIZE];
    private final ExecutorService executor;
    private final boolean ownedExecutor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong tickCounter = new AtomicLong(0L);

    private volatile Thread driverThread;
    private volatile long baseTimeNanos;
    private volatile long nextTickDeadlineNanos;

    private long advanceNanos = DEFAULT_ADVANCE_NANOS;
    private volatile boolean advanceCustomized;

    public HighPrecisionWheelTaskScheduler() {
        this("tock-wheel-worker");
    }


    public HighPrecisionWheelTaskScheduler(String name) {
        this(Runtime.getRuntime().availableProcessors() * 2, name);
    }

    public HighPrecisionWheelTaskScheduler(int poolSize, String threadNamePrefix) {
        this(createOwnedExecutor(poolSize, threadNamePrefix), true);
    }

    public HighPrecisionWheelTaskScheduler(ExecutorService executor) {
        this(executor, false);
    }

    private HighPrecisionWheelTaskScheduler(ExecutorService executor, boolean ownedExecutor) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.ownedExecutor = ownedExecutor;
        for (int i = 0; i < wheel.length; i++) {
            wheel[i] = new TaskSlot();
        }
    }
    public HighPrecisionWheelTaskScheduler(int poolSize, ThreadFactory threadFactory) {
        this(Executors.newFixedThreadPool(poolSize, threadFactory), true);
    }

    private static ExecutorService createOwnedExecutor(int poolSize, String threadNamePrefix) {
        final AtomicLong counter = new AtomicLong(0L);
        final String prefix = Objects.requireNonNull(threadNamePrefix, "threadNamePrefix");
        return Executors.newFixedThreadPool(poolSize, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            thread.setName(prefix + "-" + counter.incrementAndGet());
            return thread;
        });
    }

    @Override
    public Future<?> schedule(Runnable task, long delay, TimeUnit unit) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(unit, "unit");
        long delayNanos = unit.toNanos(delay);
        delayNanos = Math.max(0, delayNanos - advanceNanos);
        if (delayNanos <= 0L) {
            return submit(task);
        }
        if (!running.get()) {
            throw new RejectedExecutionException("scheduler is not running");
        }

        // 统一使用 nanoTime 计算精确触发时刻，避免与 currentTimeMillis 混用造成基准漂移。
        long nowNanos = System.nanoTime();
        long fireTimeNanos = saturatedAdd(nowNanos, delayNanos);
        // 直接除法（向下取整）保留真实 tick 数，避免 ceil 带来的系统性额外 1 tick 偏移。
        long ticks = delayNanos / TICK_DURATION_NANOS;
        if (ticks < 0L) {
            ticks = 0L;
        }
        long currentTick = tickCounter.get();
        long nextTickDeadlineSnapshot = nextTickDeadlineNanos;
        // 选择“最接近 fireTime 且不晚于 fireTime”的槽位，让当前槽位触发时再做最终纳秒级等待。
        long slotDistance = 0L;
        if (fireTimeNanos > nextTickDeadlineSnapshot) {
            slotDistance = (fireTimeNanos - nextTickDeadlineSnapshot) / TICK_DURATION_NANOS;
        }
        long targetTick = saturatedAdd(currentTick, slotDistance);
        long remainingRounds = slotDistance >>> WHEEL_BITS;
        int slotIndex = (int) (targetTick & WHEEL_MASK);

        TimerTaskEntry entry = new TimerTaskEntry(this, task, fireTimeNanos, remainingRounds);
        TaskSlot slot = wheel[slotIndex];
        synchronized (slot) {
            // 同一槽位内按 fireTimeNanos 升序插入，避免头插法把更早到期的任务压到链表尾部。
            if (slot.head == null || entry.fireTimeNanos < slot.head.fireTimeNanos) {
                entry.next = slot.head;
                slot.head = entry;
            } else {
                TimerTaskEntry current = slot.head;
                while (current.next != null && current.next.fireTimeNanos <= entry.fireTimeNanos) {
                    current = current.next;
                }
                entry.next = current.next;
                current.next = entry;
            }
        }
        log.debug("Schedule task: delayNs={}, newDalyNs:{}, fireTimeNanos={}, slotIndex={}, ticks={}, slotDistance={}, remainingRounds={}",
                unit.toNanos(delay), delayNanos, fireTimeNanos, slotIndex, ticks, slotDistance, remainingRounds);
        return new ScheduledTaskFuture(entry);
    }

    @Override
    public Future<?> submit(Runnable task) {
        Objects.requireNonNull(task, "task");
        return executor.submit(task);
    }

    @Override
    public long advanceNanos() {
        return advanceNanos;
    }

    public void setAdvanceNanos(long advanceNanos) {
        this.advanceNanos = Math.max(0L, advanceNanos);
        this.advanceCustomized = true;
    }

    public boolean isAdvanceCustomized() {
        return advanceCustomized;
    }

    public void applyDistributedDefaultAdvanceIfNeeded() {
        if (advanceCustomized) {
            return;
        }
        this.advanceNanos = DISTRIBUTED_DEFAULT_ADVANCE_NANOS;
    }

    @Override
    public void start(TockContext context) {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        // 预热线程池，避免后续首次提交任务时的额外调度延迟。
        try {
            executor.submit(() -> 1).get(1, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("Executor warm-up failed: {}", e.getMessage());
        }

        tickCounter.set(0L);
        baseTimeNanos = System.nanoTime();
        nextTickDeadlineNanos = baseTimeNanos + TICK_DURATION_NANOS;
        Thread thread = new Thread(this::runDriverLoop, "tock-wheel-driver");
        thread.setDaemon(true);
        thread.setPriority(Thread.MAX_PRIORITY - 1);
        driverThread = thread;
        thread.start();
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            if (ownedExecutor) {
                executor.shutdownNow();
            }
            return;
        }
        Thread thread = driverThread;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(2000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (ownedExecutor) {
            executor.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void runDriverLoop() {
        while (running.get()) {
            long now = System.nanoTime();

            // 追赶逻辑：如果错过了多个 tick，必须顺序处理所有错过槽位，避免任务漏触发。
            while (running.get() && now >= nextTickDeadlineNanos) {
                long tick = tickCounter.getAndIncrement();
                int slotIndex = (int) (tick & WHEEL_MASK);
                processSlot(slotIndex);
                nextTickDeadlineNanos += TICK_DURATION_NANOS;
                now = System.nanoTime();
            }

            if (!running.get()) {
                break;
            }
            waitUntil(nextTickDeadlineNanos);
        }
    }

    /**
     * 槽位遍历规则：
     * 1. cancelled 任务直接移除；
     * 2. remainingRounds > 0 仅递减轮次；
     * 3. remainingRounds == 0 触发提交到业务线程池并从链表移除。
     */
    private void processSlot(int slotIndex) {
        TaskSlot slot = wheel[slotIndex];
        synchronized (slot) {
            TimerTaskEntry previous = null;
            TimerTaskEntry current = slot.head;
            while (current != null) {
                TimerTaskEntry next = current.next;
                boolean removeCurrent = false;

                if (current.isCancelled()) {
                    removeCurrent = true;
                } else if (current.remainingRounds > 0) {
                    current.remainingRounds--;
                } else {
                    current.trySubmit(executor);
                    removeCurrent = true;
                }

                if (removeCurrent) {
                    if (previous == null) {
                        slot.head = next;
                    } else {
                        previous.next = next;
                    }
                } else {
                    previous = current;
                }
                current = next;
            }
        }
    }

    /**
     * 混合等待：
     * 1. 剩余时间 >10ms：按固定 10ms 粗粒度 park，减少空转；
     * 2. 剩余时间 >1ms：park(remaining-1ms) 预留最终精细窗口；
     * 3. 剩余 <=1ms：忙等自旋，尽量降低最后阶段的唤醒抖动。
     */
    private void waitUntil(long deadlineNanos) {
        long remaining;
        while (running.get() && (remaining = deadlineNanos - System.nanoTime()) > 0L) {
            if (remaining > COARSE_PARK_NANOS) {
                LockSupport.parkNanos(COARSE_PARK_NANOS);
            } else if (remaining > SPIN_THRESHOLD_NANOS) {
                LockSupport.parkNanos(remaining - SPIN_THRESHOLD_NANOS);
            } else {

//                while (running.get() && (deadlineNanos - System.nanoTime()) > 0L) {
//                    // JDK1.8没有： Thread.onSpinWait()，只能空循环自旋了。
//                }
                // 进入自旋阶段，预估剩余时间很短（≤1ms），采用有限次迭代 + 时间检查
                onSpinWait(deadlineNanos);

                return;
            }
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
        }
    }

    /**
     * 自旋等待钩子，在需要自旋时调用。
     * 子类可覆盖以提供自定义自旋逻辑（如调用 Thread.onSpinWait() 并自行检查时间）。
     *
     * @param deadlineNanos 目标唤醒时刻（纳秒）
     */
    protected void onSpinWait(long deadlineNanos) {
        // 默认实现：每 100 次自旋检查一次时间，其余迭代仅忙等
        int spins = 0;
        if (SpinWaitSupport.isOnSpinWaitAvailable()) {
            // Java 9+：使用 Thread.onSpinWait() 优化
            while (running.get() && ((spins++ & 0x7F) != 0 || System.nanoTime() - deadlineNanos < 0)) {
                SpinWaitSupport.onSpinWait();
            }
        } else {
            // Java 8：纯空循环，定期检查时间
            // 每 128 次自旋检查一次时间（掩码 0x7F）
            while (running.get() && ((spins++ & 0x7F) != 0 || System.nanoTime() - deadlineNanos < 0)) {
                // 空循环体
            }
        }
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        if (right < 0L && left < Long.MIN_VALUE - right) {
            return Long.MIN_VALUE;
        }
        return left + right;
    }

    private static final class TaskSlot {
        private TimerTaskEntry head;
    }

    private static final class TimerTaskEntry {
        private final Runnable action;
        private final long fireTimeNanos;
        private final CountDownLatch doneSignal = new CountDownLatch(1);
        private volatile boolean cancelled;
        private volatile boolean done;
        private volatile Future<?> executionFuture;
        private volatile Throwable failure;
        private volatile long remainingRounds;
        private TimerTaskEntry next;
        private HighPrecisionWheelTaskScheduler scheduler;
        private TimerTaskEntry(HighPrecisionWheelTaskScheduler scheduler, Runnable action, long fireTimeNanos, long remainingRounds) {
            this.scheduler = scheduler;
            this.action = action;
            this.fireTimeNanos = fireTimeNanos;
            this.remainingRounds = remainingRounds;
        }

        private synchronized boolean cancel(boolean mayInterruptIfRunning) {
            if (done || cancelled) {
                return false;
            }
            cancelled = true;
            Future<?> future = executionFuture;
            if (future == null) {
                markDone();
                return true;
            }
            boolean cancelledNow = future.cancel(mayInterruptIfRunning);
            if (cancelledNow) {
                markDone();
            }
            return cancelledNow;
        }

        private synchronized boolean isCancelled() {
            return cancelled;
        }

        private synchronized boolean isDone() {
            return done;
        }

        private void trySubmit(ExecutorService executor) {
            synchronized (this) {
                if (cancelled || done || executionFuture != null) {
                    return;
                }
            }
            waitUntilFireTime(fireTimeNanos);
            long nowNano = System.nanoTime();
            log.debug("Try submit: fireTimeMs={}, deadlineNanos={}, nowNano={}, lagNano={}",
                    System.currentTimeMillis(), fireTimeNanos, nowNano, nowNano - fireTimeNanos);

            Future<?> submittedFuture;
            try {
                submittedFuture = executor.submit(() -> {
                    try {
                        synchronized (TimerTaskEntry.this) {
                            if (cancelled) {
                                return;
                            }
                        }
                        action.run();
                    } catch (Throwable throwable) {
                        failure = throwable;
                        throw throwable;
                    } finally {
                        markDone();
                    }
                });
            } catch (RejectedExecutionException ex) {
                failure = ex;
                markDone();
                return;
            }

            synchronized (this) {
                executionFuture = submittedFuture;
                if (cancelled) {
                    submittedFuture.cancel(true);
                }
            }
        }

        private void waitUntilFireTime(long fireTimeNanos) {
            long remaining;
            while ((remaining = fireTimeNanos - System.nanoTime()) > 0L) {
                if (remaining > SPIN_THRESHOLD_NANOS) {
                    LockSupport.parkNanos(remaining - SPIN_THRESHOLD_NANOS);
                } else {
//                    while (fireTimeNanos - System.nanoTime() > 0L) {
//                        // 最后 1ms 以内采用自旋，尽量把提交时刻压近目标 fireTime。
//                    }
                    scheduler.onSpinWait(fireTimeNanos);
                    return;
                }
            }
        }

        private synchronized void markDone() {
            if (!done) {
                done = true;
                doneSignal.countDown();
            }
        }

        private Object awaitResult() throws InterruptedException, ExecutionException {
            doneSignal.await();
            if (cancelled) {
                throw new java.util.concurrent.CancellationException();
            }
            if (failure != null) {
                throw new ExecutionException(failure);
            }
            return null;
        }

        private Object awaitResult(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            if (!doneSignal.await(timeout, unit)) {
                throw new TimeoutException("timeout waiting for scheduled task completion");
            }
            if (cancelled) {
                throw new java.util.concurrent.CancellationException();
            }
            if (failure != null) {
                throw new ExecutionException(failure);
            }
            return null;
        }
    }

    /**
     * schedule 返回的 Future：主要承担“取消当前计划”的语义。
     */
    private static final class ScheduledTaskFuture implements Future<Object> {
        private final TimerTaskEntry taskEntry;

        private ScheduledTaskFuture(TimerTaskEntry taskEntry) {
            this.taskEntry = taskEntry;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return taskEntry.cancel(mayInterruptIfRunning);
        }

        @Override
        public boolean isCancelled() {
            return taskEntry.isCancelled();
        }

        @Override
        public boolean isDone() {
            return taskEntry.isDone();
        }

        @Override
        public Object get() throws InterruptedException, ExecutionException {
            return taskEntry.awaitResult();
        }

        @Override
        public Object get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            return taskEntry.awaitResult(timeout, unit);
        }
    }
}
