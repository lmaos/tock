package com.clmcat.tock;

import com.clmcat.tock.health.NodeHealthMaintainer;
import com.clmcat.tock.job.DefaultJobRegistry;
import com.clmcat.tock.job.JobExecutor;
import com.clmcat.tock.job.JobRegistry;
import com.clmcat.tock.registry.TockMaster;
import com.clmcat.tock.registry.TockRegister;
import com.clmcat.tock.schedule.ScheduleConfig;
import com.clmcat.tock.schedule.ScheduleStore;
import com.clmcat.tock.utils.LifecycleSupport;
import com.clmcat.tock.utils.ReferenceSupport;
import com.clmcat.tock.worker.scheduler.ScheduledExecutorTaskScheduler;
import com.clmcat.tock.scheduler.EventDrivenCronScheduler;
import com.clmcat.tock.worker.scheduler.TaskScheduler;
import com.clmcat.tock.scheduler.TockScheduler;
import com.clmcat.tock.serialize.Serializer;
import com.clmcat.tock.serialize.SerializerFactory;
import com.clmcat.tock.serialize.VersionedSerializer;
import com.clmcat.tock.store.JobStore;
import com.clmcat.tock.time.DefaultTimeSynchronizer;
import com.clmcat.tock.time.SystemTimeProvider;
import com.clmcat.tock.time.TimeProvider;
import com.clmcat.tock.time.TimeSynchronizer;
import com.clmcat.tock.worker.DefaultTockWorker;
import com.clmcat.tock.worker.TockWorker;
import com.clmcat.tock.worker.WorkerQueue;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 全局门面类，对外统一入口。
 * 负责组装配置、启动所有组件（选主、调度器、Worker）、提供注册Job和配置调度的API。
 * <p>用户代码的唯一交互点。</p>
 */
@Slf4j
@Getter
public class Tock {

    private static volatile Tock instance;

    /**
     * 初始化并配置 Tock 实例。 (全局唯一实例)
     * @param config 配置对象（包含 Redis 地址、选主实现、存储实现等）
     * @return 配置好的 Tock 单例（或新实例，这里采用单例模式）
     */
    public static synchronized Tock configure(Config config) {
        if (instance != null) throw new IllegalStateException("Tock already configured");
        instance = create(config);
        return instance;
    }

    static synchronized void resetForTest() {
        if (instance != null) {
            instance.shutdown();
            instance = null;
        }
    }

    /**
     * 创建一个新的独立实例。
     * @param config 实例配置
     * @return 返回 Tock
     */
    public static Tock create(Config config) {
        return new Tock(config);
    }

    /**
     * 获取已配置的 Tock 单例。
     */
    public static Tock get() {
        if (instance == null) {
            throw new IllegalStateException("Tock not configured, call configure() first");
        }
        return instance;
    }



    private volatile AtomicBoolean started = new  AtomicBoolean(false);



    private Config config;
    /**
     * 时间接口。
     */
    private TimeSynchronizer timeSynchronizer;
    /**
     * 时间提供者
     */
    private TimeProvider timeProvider;

    private TockRegister register;
    /**
     * 注册中心，存储jobId → JobExecutor的映射。
     */
    private JobRegistry jobRegistry;
    /**
     * 主调度器，用于读取配置创建执行任务。
     */
    private TockScheduler scheduler;
    /**
     * 监听任务触发，任务触发时候执行任务。
     */
    private TockWorker worker;
    /**
     * 存放所有`ScheduleConfig`的实现，主要是定时任务的配置， 比如：cron/延迟时间等，调度器会从这里加载配置。
     */
    private ScheduleStore scheduleStore;

    /**
     * 延时任务存储，按执行时间排序（类似延迟队列）。 由调度计算未来执行点的配置进行存储。
     * 使用时，调度器会定期检查到期任务并推送给 WorkerQueue 执行， Worker响应执行队列的执行操作。
     */
    private JobStore jobStore;
    /**
     * 工作队列
     */
    private WorkerQueue workerQueue;
    /**
     * 序列化工具，可以不配置, 默认序列工具根据用户依赖的第三方库自动选择，优先级为：Jackson > Fastjson > Kryo > JavaSerializer。
     */
    private Serializer serializer;
    /**
     * 调度器专用线程池（Master）
     */
    private ScheduledExecutorService schedulerExecutor;
    /**
     * Worker 执行任务的线程池
     */
    private TaskScheduler workerExecutor;
    /**
     * Worker 消费队列的线程池（每个组的拉取线程）
     */
    private ExecutorService consumerExecutor;

    /**
     * 节点监控
     */
    private NodeHealthMaintainer nodeHealthMaintainer;
    /**
     * 管理用户的线程池， true时，当 shutdown 时候会关闭线程池。 false 不会强制关闭，
     */
    private boolean manageThreadPools;
    /**
     * 全局上下文对象
     */
    private TockContext tockContext;


    private CountDownLatch countDownLatch = new CountDownLatch(1);
    private final List<Object> components;
    private final List<Lifecycle> lifecyclesComponents;

    /**
     * 使用 config() 工厂方法
     *
     * @param config 初始化配置文件
     */
    Tock(Config config) {
        if (!ReferenceSupport.commonReference(config)) {
            throw new IllegalStateException("[ Config 已经被另一个Tock依赖，请重新创建 Config ] Config instance is already used by another Tock instance, please create a new Config instance.");
        }
        this.config = config;
        this.jobRegistry = new DefaultJobRegistry();

        // 初始化各个组件（根据 config 中的设置）
        this.scheduleStore = config.getScheduleStore();      // 由 Config 提供
        this.register = config.getRegister();
        this.workerQueue = config.getWorkerQueue();
        // 保留接口， 用于 Job 的调度预存储.
        this.jobStore = config.getJobStore();

        // 注册中心必须存在
        Objects.requireNonNull(this.register, "register is null");
        // 调度配置存储， 必须存在
        Objects.requireNonNull(this.scheduleStore, "scheduleStore is null");
        // Worker执行队列，必须存在
        Objects.requireNonNull(this.workerQueue, "workerQueue is null");

        String namespace = register.getNamespace();

        this.serializer = config.getSerializer();
        this.scheduler = config.getScheduler();
        this.worker = config.getWorker();

        this.timeProvider = config.getTimeProvider();
        this.timeSynchronizer = config.getTimeSynchronizer();

        this.workerExecutor = config.getWorkerExecutor();
        this.consumerExecutor = config.getConsumerExecutor();
        this.schedulerExecutor = config.getSchedulerExecutor();
        this.manageThreadPools = config.isManageThreadPools();

        if (Objects.isNull(serializer)) {
            /// 序列化方式。 默认序列工具根据用户依赖的第三方库自动选择，优先级为：Jackson > Fastjson > Kryo > JavaSerializer。
            this.serializer = SerializerFactory.getDefault();
        }
        // 包装一层版本控制，方便未来升级和兼容。
        this.serializer = new VersionedSerializer(serializer);
        // 初始化时间提供者（优先级：显式配置 > 注册中心实现 > 系统默认）
        if (Objects.isNull(this.timeProvider)) {
            this.timeProvider = new SystemTimeProvider();
        }
        // 时间接口默认实现
        if (Objects.isNull(timeSynchronizer)) {
            this.timeSynchronizer = new DefaultTimeSynchronizer(this.timeProvider, 5000, 5);
            this.timeSynchronizer.forceReinitialize();
        }

        if (Objects.isNull(this.scheduler)) {
            /// 默认调度器， 使用事件驱动执行调度
            this.scheduler =new EventDrivenCronScheduler();
        }

        if (Objects.isNull(this.worker)) {
            this.worker = new DefaultTockWorker();
        }

        if (Objects.isNull(this.workerExecutor)) {
            this.workerExecutor = ScheduledExecutorTaskScheduler.create(namespace + "-worker");
        }
        if (Objects.isNull(this.consumerExecutor)) {
            this.consumerExecutor = Executors.newCachedThreadPool((r) -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
                t.setName(namespace + "-worker-consumer");
                return t;
            });
        }
        if (Objects.isNull(this.schedulerExecutor)) {
            this.schedulerExecutor = Executors.newScheduledThreadPool(2, (r)->{
                Thread t = new Thread(r);
                t.setDaemon(true);
                t.setName(namespace + "-scheduler");
                return t;
            });
        }



        this.tockContext = TockContext.builder()
                .config(config)
                .register(register)
                .jobRegistry(jobRegistry)
                .scheduleStore(scheduleStore)
                .jobStore(jobStore)
                .serializer(serializer)
                .workerQueue(workerQueue)
                .workerExecutor(workerExecutor)
                .worker(worker)
                .scheduler(scheduler)
                .consumerExecutor(consumerExecutor)
                .schedulerExecutor(schedulerExecutor)
                .timeProvider(timeProvider)
                .timeSource(timeSynchronizer)
                .build();

        // 组件集合。
        this.components = Arrays.asList(
                serializer,
                register,
                timeProvider,
                timeSynchronizer,
                nodeHealthMaintainer,
                workerExecutor,
                workerQueue,
                jobRegistry,
                jobStore,
                scheduleStore,
                worker,
                scheduler);

        for (Object component : components) {
            if (component != null) {
                if (!ReferenceSupport.commonReference(component)) {
                    throw new IllegalStateException("[组件 " + component.getClass().getSimpleName() + " 已经被另一个Tock依赖，请重新创建组件实例] Component " + component.getClass().getSimpleName() + " is already used by another Tock instance, please create a new instance.");
                }
            }
        }

        // 给基础组件设置 TockContext, 需要实现: TockContextAware
        components.forEach(component -> {
            injectContext(component, tockContext);
        });
        lifecyclesComponents = new ArrayList<>(LifecycleSupport.loaderLifecycles(components));
        // 生命周期初始化。
        lifecyclesComponents.forEach(component -> {
            lifecycleInit(component, tockContext);
        });

    }


    private void injectContext(Object component, TockContext context) {
        if (component != null && component instanceof TockContextAware) {
            ((TockContextAware) component).setTockContext(context);
        }
    }

    private void lifecycleInit(Object component, TockContext context) {
        if (component != null && component instanceof Lifecycle) {
            ((Lifecycle) component).init(context);
            log.info("Initialized component: {}", component.getClass().getSimpleName());
        }
    }

    private void lifecycleStart(Object component, TockContext context) {
        if (component != null && component instanceof Lifecycle) {
            if (!((Lifecycle) component).isStarted()) {
                ((Lifecycle) component).start(context);
                log.info("Start component: {}", component.getClass().getSimpleName());
            } else {
                log.info("Component already started: {}", component.getClass().getSimpleName());
            }
        }
    }

    private void lifecycleStop(Object component, TockContext context) {
        if (component != null && component instanceof Lifecycle) {
            if (((Lifecycle) component).isStarted()) {
                ((Lifecycle) component).stop();
                log.info("Stop component: {}", component.getClass().getSimpleName());
            } else {
                log.info("Component already stopped: {}", component.getClass().getSimpleName());
            }
        }
    }

    /**
     * 启动所有组件（选主、调度器、Worker）。
     * 注意：选主成功与否决定是否启动调度器，Worker 始终启动。
     */
    public synchronized  Tock start() {

        if (started.compareAndSet(false, true)) {
            countDownLatch = new CountDownLatch(1);
            this.lifecyclesComponents.forEach(component -> {
                lifecycleStart(component, tockContext);
            });
            log.info("Tock ({}) started", register.getNamespace());
        } else {
            log.info("Tock Component already started");
        }

        return this;
    }

    /**
     * 优雅关闭所有组件。
     */
    public synchronized  void shutdown() {
        // config 被多个 Tock 依赖。


        if (started.compareAndSet(true, false)) {
            this.components.forEach(component -> {
                ReferenceSupport.commonRemoveReference(component);
            });
            ReferenceSupport.configRemoveReference(config);

            for (int i = this.lifecyclesComponents.size() - 1; i >=0 ; i--) {
                lifecycleStop(lifecyclesComponents.get(i), tockContext);
            }

            countDownLatch.countDown();
            log.info("Tock ({}) shutdown", register.getNamespace());
        } else {
            log.info("Tock Component already stopped");
        }
    }

    /**
     * 注册 Job 执行器（本地回调）。
     * @param jobId 唯一标识
     * @param executor 业务逻辑实现
     * @return this
     */
    public Tock registerJob(String jobId, JobExecutor executor) {
        jobRegistry.register(jobId, executor);
        return this;
    }

    public Tock joinGroup(String groupName) {
        worker.joinGroup(groupName);
        return this;
    }

    /**
     *
     * @param scheduleConfig 定时任务配置， scheduleId 代表当前任务的配置ID， 如果存在配置则覆盖。
     * @return this
     */
    public Tock addSchedule(ScheduleConfig scheduleConfig) {
        scheduleStore.save(scheduleConfig);
        return this;
    }

    public Tock refreshSchedules() {
        scheduler.refreshSchedules();
        return this;
    }

    /**
     * 删除当前调度配置
     * @param scheduleId 调度ID
     * @return this
     */
    public Tock removeSchedule(String scheduleId) {
        scheduleStore.delete(scheduleId);
        return this;
    }

    /**
     * 暂停当前调度的任务
     * @param scheduleId 调度ID
     * @return this
     */
    public Tock pauseSchedule(String scheduleId) {
        ScheduleConfig old = scheduleStore.get(scheduleId);
        if (old != null) {
            ScheduleConfig updater = old.toBuilder().enabled(false).build();
            scheduleStore.save(updater);
        }
        return this;
    }

    /**
     * 重启当前调度的任务
     * @param scheduleId 调度ID
     * @return this
     */
    public Tock resumeSchedule(String scheduleId) {
        ScheduleConfig old = scheduleStore.get(scheduleId);
        if (old != null) {
            ScheduleConfig updater = old.toBuilder().enabled(true).build();
            scheduleStore.save(updater);
        }
        return this;
    }

    public long currentTimeMillis() {
        return timeSynchronizer.currentTimeMillis();
    }

    public  Tock sync() {
        try {
            countDownLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return this;
    }
    public  Tock sync(long ms) {
        try {
            countDownLatch.await(ms, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return this;
    }


}
