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

    /**
     * 返回调度器期望的偏移量（纳秒）。实际计算delay 时候， 会 根据这个偏移量进行调整，以补偿系统时间的误差或调度器内部的处理延迟。
     * 正值：任务应延迟执行（调度器内部会做更精细的等待）
     * 负值：任务应提前执行（调度器内部会补偿）
     * 零：无偏移。
     *
     */
    long advanceNanos() ;
}