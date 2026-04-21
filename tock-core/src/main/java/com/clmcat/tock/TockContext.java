package com.clmcat.tock;

import com.clmcat.tock.job.JobRegistry;
import com.clmcat.tock.registry.TockMaster;
import com.clmcat.tock.registry.TockRegister;
import com.clmcat.tock.schedule.ScheduleStore;
import com.clmcat.tock.worker.scheduler.TaskScheduler;
import com.clmcat.tock.scheduler.TockScheduler;
import com.clmcat.tock.serialize.Serializer;
import com.clmcat.tock.store.JobStore;
import com.clmcat.tock.time.TimeProvider;
import com.clmcat.tock.time.TimeSource;
import com.clmcat.tock.worker.TockWorker;
import com.clmcat.tock.worker.WorkerQueue;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

@Getter
@Builder
public class TockContext {
    private Config config;
    private TockRegister register;
    /**
     * 注册中心，存储jobId → JobExecutor的映射。
     */
    private JobRegistry jobRegistry;


    private TockScheduler scheduler;

    private TockWorker worker;

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
     * 调度选主。同一时间只有一个主服务，用来做调度，其他服务等待和只执行Worker。
     */
    @NonNull
    private TockMaster master;
    /**
     * 工作队列
     */
    @NonNull
    private WorkerQueue workerQueue;
    /**
     * 序列化工具，可以不配置, 默认序列工具根据用户依赖的第三方库自动选择，优先级为：Jackson > Fastjson > Kryo > JavaSerializer。
     */
    private Serializer serializer;




    private final ScheduledExecutorService schedulerExecutor;  // 调度器专用线程池（Master）
    private final TaskScheduler workerExecutor;              // Worker 执行任务的线程池
    private final ExecutorService consumerExecutor;            // Worker 消费队列的线程池（每个组的拉取线程）

    private TimeProvider timeProvider;
    private TimeSource timeSource;

    /**
     * 返回当前同步后的时间戳（毫秒）。
     * <p>
     * 该时间通过 {@link TimeSource} 获取，内部可能经过时钟偏移校正和单调性保护，
     * 适用于调度器和 Worker 中所有需要时间比较的场景。
     * 使用统一的时间源可以避免分布式环境下各节点因时钟差异导致的调度偏差。
     * </p>
     *
     * @return 单调递增的毫秒级时间戳
     */
    public long currentTimeMillis() {
        return timeSource == null ? System.currentTimeMillis() : timeSource.currentTimeMillis();
    }

}
