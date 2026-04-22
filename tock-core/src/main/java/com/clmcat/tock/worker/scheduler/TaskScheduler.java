package com.clmcat.tock.worker.scheduler;

import com.clmcat.tock.Lifecycle;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public interface TaskScheduler extends Lifecycle {
    /**
     * 在指定延迟后执行任务。
     * @return 可用于取消的 Future，若调度器不支持取消可返回 null 或空 Future。
     */
    Future<?> schedule(Runnable task, long delay, TimeUnit unit);
    Future<?>  submit(Runnable task);

}