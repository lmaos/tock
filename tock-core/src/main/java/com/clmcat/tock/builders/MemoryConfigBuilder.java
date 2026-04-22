package com.clmcat.tock.builders;

import com.clmcat.tock.Config;
import com.clmcat.tock.money.MemoryManager;
import com.clmcat.tock.registry.memory.MemoryTockRegister;
import com.clmcat.tock.schedule.memory.MemoryScheduleStore;
import com.clmcat.tock.scheduler.EventDrivenCronScheduler;
import com.clmcat.tock.worker.DefaultTockWorker;
import com.clmcat.tock.worker.memory.MemorySubscribableWorkerQueue;
import com.clmcat.tock.worker.scheduler.TaskSchedulers;

/**
 * 内存模式配置构建器，适用于本地和单机部署。
 * <p>
 * 使用此构建器创建的 Config 将完全基于内存组件，无需外部依赖，
 * 所有数据在 JVM 内共享，重启即丢失。 可使用 toConfigBuilder()后 自行替换存储配置。
 * </p>
 *
 * <p>示例：
 * <pre>{@code
 * Config config = MemoryConfigBuilder.builder("test-app")
 *         .withHighPrecisionWorker(true)
 *         .build();
 * Tock tock = Tock.configure(config).start();
 * }</pre>
 */
public class MemoryConfigBuilder {

    boolean highPrecisionWorker = false;
    private String namespace;
    private MemoryManager memoryManager = MemoryManager.getSingleton();

    public MemoryConfigBuilder(String namespace) {
        this.namespace = namespace;
    }

    public MemoryConfigBuilder withHighPrecisionWorker(boolean highPrecisionWorker) {
        this.highPrecisionWorker = highPrecisionWorker;
        return this;
    }

    public MemoryConfigBuilder withMemoryManage(MemoryManager memoryManager) {
        this.memoryManager = memoryManager;
        return this;
    }
    
    public Config.ConfigBuilder toConfigBuilder() {
        return Config.builder()
                .scheduleStore(MemoryScheduleStore.create())
                .workerQueue(MemorySubscribableWorkerQueue.create())
                .register(MemoryTockRegister.create(namespace, memoryManager))
                .worker(DefaultTockWorker.create())
                .scheduler(EventDrivenCronScheduler.create())
                .workerExecutor(highPrecisionWorker ? TaskSchedulers.highPrecision(namespace + "-worker")
                        : TaskSchedulers.schedulerExecutor(namespace + "-worker"));
    }

    public Config build() {
        return toConfigBuilder().build();
    }

    public static MemoryConfigBuilder builder(String namespace) {
        return new MemoryConfigBuilder(namespace);
    }

}
