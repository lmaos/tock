package com.clmcat.tock;

import com.clmcat.tock.health.HeartbeatReporter;
import com.clmcat.tock.health.HealthMaintainer;
import com.clmcat.tock.job.JobRegistry;
import com.clmcat.tock.registry.TockRegister;
import com.clmcat.tock.schedule.ScheduleStore;
import com.clmcat.tock.time.TimeSynchronizer;
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

@Getter
@Builder
public class TockContext {
    private String namespace;
    private Config config;
    /**
     * 序列化工具，可以不配置, 默认序列工具根据用户依赖的第三方库自动选择，优先级为：Jackson > Fastjson > Kryo > JavaSerializer。
     */
    private Serializer serializer;

    private TockRegister register;


    private HealthMaintainer healthMaintainer;

    private HeartbeatReporter heartbeatReporter;

    private TimeProvider timeProvider;

    private TimeSource timeSource;

    private TimeSynchronizer timeSynchronizer;
    /**
     * 注册中心，存储jobId → JobExecutor的映射。
     */
    private JobRegistry jobRegistry;


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

    private final TaskScheduler workerExecutor;              // Worker 执行任务的线程池

    private final ExecutorService consumerExecutor;            // Worker 消费队列的线程池（每个组的拉取线程）


    /**
     * 工作队列
     */
    @NonNull
    private WorkerQueue workerQueue;




    private TockWorker worker;

    private TockScheduler scheduler;



    /**
     * 返回当前时间戳（毫秒）。
     */
    public long currentTimeMillis() {
        return timeSource == null ? System.currentTimeMillis() : timeSource.currentTimeMillis();
    }

}
