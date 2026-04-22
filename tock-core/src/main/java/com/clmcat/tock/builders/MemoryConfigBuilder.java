package com.clmcat.tock.builders;

import com.clmcat.tock.Config;
import com.clmcat.tock.Tock;
import com.clmcat.tock.money.MemoryManager;
import com.clmcat.tock.registry.memory.MemoryTockRegister;
import com.clmcat.tock.schedule.memory.MemoryScheduleStore;
import com.clmcat.tock.scheduler.EventDrivenCronScheduler;
import com.clmcat.tock.worker.DefaultTockWorker;
import com.clmcat.tock.worker.memory.MemorySubscribableWorkerQueue;
import com.clmcat.tock.worker.scheduler.HighPrecisionWheelTaskScheduler;
import com.clmcat.tock.worker.scheduler.TaskSchedulers;

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
    
    public Config build() {
        Config config = Config.builder()
                .scheduleStore(MemoryScheduleStore.create())
                .workerQueue(MemorySubscribableWorkerQueue.create())
                .register(MemoryTockRegister.create(namespace, memoryManager))
                .worker(DefaultTockWorker.create())
                .scheduler(EventDrivenCronScheduler.create())
                .workerExecutor(highPrecisionWorker ? TaskSchedulers.highPrecision(namespace + "-worker")
                        : TaskSchedulers.schedulerExecutor(namespace + "-worker"))
                .build();
        return config;
    }

    public static MemoryConfigBuilder builder(String namespace) {
        return new MemoryConfigBuilder(namespace);
    }

}
