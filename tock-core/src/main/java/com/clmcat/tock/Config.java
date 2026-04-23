package com.clmcat.tock;

import com.clmcat.tock.registry.TockRegister;
import com.clmcat.tock.schedule.ScheduleStore;
import com.clmcat.tock.worker.scheduler.TaskScheduler;
import com.clmcat.tock.scheduler.TockScheduler;
import com.clmcat.tock.serialize.Serializer;
import com.clmcat.tock.store.JobStore;
import com.clmcat.tock.time.SystemTimeProvider;
import com.clmcat.tock.time.TimeProvider;
import com.clmcat.tock.time.TimeSynchronizer;
import com.clmcat.tock.worker.TockWorker;
import com.clmcat.tock.worker.WorkerQueue;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

@Builder
@Getter
public class Config {
    /**
     * 存放所有`ScheduleConfig`的实现，主要是定时任务的配置， 比如：cron/延迟时间等，调度器会从这里加载配置。
     */
    @NonNull
    private ScheduleStore scheduleStore;
    /**
     * 延时任务存储，按执行时间排序（类似延迟队列）。 由调度计算未来执行点的配置进行存储。
     * 使用时，调度器会定期检查到期任务并推送给 WorkerQueue 执行， Worker响应执行队列的执行操作。
     */
    private JobStore jobStore;
    /**
     * 注册节点和选择主机。 调度选主。同一时间只有一个主服务，用来做调度，其他服务等待和只执行Worker。 所有服务都默认拥有Node的注册
     */
    @NonNull
    private TockRegister register;

    /**
     * 工作队列
     */
    @NonNull
    private WorkerQueue workerQueue;
    /**
     * 序列化工具，可以不配置, 默认序列工具根据用户依赖的第三方库自动选择，优先级为：Jackson > Fastjson > Kryo > JavaSerializer。
     */
    private Serializer serializer;


    private TockWorker worker;

    private TockScheduler scheduler;

    private final ScheduledExecutorService schedulerExecutor;  // 调度器专用线程池（Master）
    private final TaskScheduler workerExecutor;              // Worker 执行任务的线程池
    private final ExecutorService consumerExecutor;            // Worker 消费队列的线程池（每个组的拉取线程）

    /**
     * 管理用户的线程池， true时，当 shutdown 时候会关闭线程池。 false 不会强制关闭，
     */
    @Builder.Default
    private boolean manageThreadPools = true;

    /**
     * 是否开启“计划已下发但尚未进入 doExecuteJob”阶段的恢复追踪。
     * <p>
     * 默认关闭，保持原始的轻量 worker 设计和最小开销。
     * 开启后，worker 会为尚未真正执行的计划记录一个短生命周期的待执行标记，
     * 便于主调度器在节点失联时决定是否重新下发。
     * </p>
     */
    @Builder.Default
    private boolean pendingExecutionRecoveryEnabled = false;


    /**
     * 时间提供接口。
     */
    private TimeProvider timeProvider;

    /**
     * 时间接口。
     */
    private TimeSynchronizer timeSynchronizer;

}
