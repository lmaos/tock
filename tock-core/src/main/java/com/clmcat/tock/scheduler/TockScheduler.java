package com.clmcat.tock.scheduler;

import com.clmcat.tock.Lifecycle;
import com.clmcat.tock.TockContext;

/**
 * 调度器接口，由 Master 节点运行。
 * 负责根据 ScheduleConfig 生成定时任务，并推送给 Worker 执行。
 */
public interface TockScheduler extends Lifecycle {

    /**
     * 启动调度器（阻塞式循环，通常在一个独立线程中运行）。
     * 内部会持续从 ScheduleStore 加载配置，计算下次执行时间，
     * 并将待执行任务写入 JobStore，同时轮询到期任务推送给 Worker。
     */
    void start(TockContext context);

    /**
     * 停止调度器，优雅关闭循环。
     */
    void stop();

    /**
     * 手动触发一次配置重新加载（例如收到配置变更通知时调用）。
     * 实现类应重新从 ScheduleStore 拉取所有配置，刷新内部调度表。
     */
    void refreshSchedules();


    /**
     * 判断调度器是否正在运行。
     */
    boolean isStarted();

}