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
     * 时间提供者，用于获取原始时间戳。
     * <p>
     * 该接口仅负责从某个具体的时间源（如本地系统时钟、Redis TIME 命令、NTP 服务器等）读取当前毫秒时间戳，
     * 不涉及任何同步或偏移校正逻辑。默认实现为 {@link SystemTimeProvider}，直接返回 {@link System#currentTimeMillis()}。
     * </p>
     * <p>
     * 当需要分布式环境下多节点时间一致时，可替换为基于远程时间源的实现（例如从 Redis 获取统一时间），
     * 此时 {@code timeProvider} 将作为时间同步器 {@link TimeSynchronizer} 的采样依据。
     * </p>
     * <br/> <hr/>
     * 时间提供者，用于获取原始时间戳（如本地系统时间、Redis TIME 命令等）。
     * <p>
     * <b>优先级说明：</b>
     * <ol>
     *     <li>如果用户显式设置了此字段，则直接使用。</li>
     *     <li>如果未设置，且 {@link TockRegister} 实现了 {@link TimeProvider} 接口，则自动使用注册中心作为时间提供者。</li>
     *     <li>否则，默认使用 {@link SystemTimeProvider}。</li>
     * </ol>
     * 自动检测逻辑在 {@link Tock} 构造器中执行，用户一般无需手动配置，除非需要自定义时间源（如 NTP）。
     * </p>

     * @see SystemTimeProvider
     * @see TimeSynchronizer
     */
    private TimeProvider timeProvider;

    /**
     * 时间同步器，提供单调递增且经过偏移校正的当前时间。
     * <p>
     * 该组件内部使用 {@link TimeProvider} 获取原始时间采样，通过算法消除网络延迟影响、校正本地与远程时钟的偏差，
     * 并保证对外提供的时间戳严格单调递增（不会因为系统时间回拨或偏移量跳变而出现时间倒退）。
     * </p>
     * <h3>核心算法</h3>
     * <ul>
     *     <li><b>采样中点补偿</b>：每次采样记录请求发出前后的本地时间（使用纳秒计时），估算 RTT 中点，计算远程时间与本地中点的差值作为单次偏移；多次采样时优先采用 RTT 最小的样本，以降低网络抖动与链路非对称带来的误差。</li>
     *     <li><b>单调性保证</b>：使用 CAS 自旋和上一次返回值缓存，确保任何并发调用返回的时间戳不小于前一次返回值，避免因时钟同步调整或本地时间回拨导致调度混乱。</li>
     *     <li><b>周期性同步</b>：后台线程定期执行采样，动态更新当前偏移量，使 {@link TimeSynchronizer#currentTimeMillis()} 返回值与远程时间源保持最终一致。</li>
     * </ul>
     * <p>
     * 默认实现为 {@link com.clmcat.tock.time.DefaultTimeSynchronizer}，若使用 {@link SystemTimeProvider} 作为 {@code timeProvider}，
     * 则会自动退化为直接返回本地系统时间（仍保留单调性保护），不启动后台同步线程，以节省资源。
     * </p>
     * <p>
     * 调度器（如 {@code CronScheduler}）和 Worker 中所有需要获取当前时间的操作都应通过该同步器，
     * 以确保整个分布式集群使用统一的时间基准，消除节点间时钟偏差对任务精度的影响。
     * </p>
     *
     * @see com.clmcat.tock.time.DefaultTimeSynchronizer
     * @see TimeProvider
     */
    private TimeSynchronizer timeSynchronizer;
}
