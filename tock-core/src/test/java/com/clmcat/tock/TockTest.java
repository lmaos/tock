package com.clmcat.tock;

import com.clmcat.tock.money.MemoryManager;
import com.clmcat.tock.registry.TockRegister;
import com.clmcat.tock.registry.memory.MemoryTockRegister;
import com.clmcat.tock.schedule.ScheduleConfig;
import com.clmcat.tock.schedule.memory.MemoryScheduleStore;
import com.clmcat.tock.store.MemoryJobStore;
import com.clmcat.tock.worker.memory.MemoryPullableWorkerQueue;
import com.clmcat.tock.worker.scheduler.TaskSchedulers;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Slf4j
public class TockTest {

    @AfterEach
    void resetTock() {
        Tock.resetForTest();
    }

    @Test
    void tockMemorySubscribableWorkerQueueScheduleTest() {
        TockRegister register = new MemoryTockRegister("test-timer", MemoryManager.create());


        Config config = Config.builder()
                .register(register)
                .scheduleStore(MemoryScheduleStore.create())
//                .jobStore(MemoryJobStore.create())
                .workerQueue(MemoryPullableWorkerQueue.create())
                .workerExecutor(TaskSchedulers.highPrecision("test-worker"))
                .build();

        Assertions.assertDoesNotThrow(() -> {
            Tock.configure(config).start();
        });

        log.info("Memory Subscribable Worker Queue Schedule Test - START");

        CountDownLatch countDownLatch = new CountDownLatch(2);

        Tock.get().registerJob("test-job", context -> {
            log.warn("任务执行:" + Tock.get().currentTimeMillis() +"");
                    countDownLatch.countDown();
        }).addSchedule(ScheduleConfig.builder()
                    .scheduleId("test-schedule")
//                    .fixedDelayMs(1000L)
                    .cron("*/1 * * * * ?")
                    .jobId("test-job")
                    .workerGroup("default")
                    .zoneId("Asia/Shanghai").build())
                .refreshSchedules() // 立即刷新调度配置，Master会立刻加载新的调度任务
                .joinGroup("default") // 增加组监控
        ;

        try {
            countDownLatch.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {}
        Tock.get().shutdown();


        log.info("Memory Subscribable Worker Queue Schedule Test - END");
    }
}
