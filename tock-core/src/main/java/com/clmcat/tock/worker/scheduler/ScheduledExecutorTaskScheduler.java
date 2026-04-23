package com.clmcat.tock.worker.scheduler;

import com.clmcat.tock.TockContext;
import lombok.Setter;

import java.util.concurrent.*;

public class ScheduledExecutorTaskScheduler implements TaskScheduler {

    private ScheduledExecutorService  scheduledExecutorService;

    public ScheduledExecutorTaskScheduler(ScheduledExecutorService scheduledExecutorService) {
        this.scheduledExecutorService = scheduledExecutorService;
    }

    public ScheduledExecutorTaskScheduler(String name) {
        this(Runtime.getRuntime().availableProcessors() * 2, name);
    }
    public ScheduledExecutorTaskScheduler(int poolSize, String name) {
        this.scheduledExecutorService = Executors.newScheduledThreadPool(poolSize, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r);
                t.setDaemon(true);
                t.setName(name);
                return t;
            }
        });
    }

    public static ScheduledExecutorTaskScheduler create(String threadNamePrefix) {
        return new ScheduledExecutorTaskScheduler(threadNamePrefix);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
        long delayNanos = unit.toNanos(delay);
        return scheduledExecutorService.schedule(task, delayNanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public Future<?> submit(Runnable task) {
        return scheduledExecutorService.submit(task);
    }

    @Override
    public void start(TockContext context) {

    }

    @Override
    public void stop() {
        scheduledExecutorService.shutdownNow();
    }

    @Override
    public boolean isStarted() {
        return !scheduledExecutorService.isShutdown();
    }


}
