package com.clmcat.demo;

import com.clmcat.tock.Config;
import com.clmcat.tock.Tock;
import com.clmcat.tock.builders.MemoryConfigBuilder;
import com.clmcat.tock.money.MemoryManager;
import com.clmcat.tock.registry.memory.MemoryTockRegister;
import com.clmcat.tock.schedule.ScheduleConfig;
import com.clmcat.tock.schedule.memory.MemoryScheduleStore;
import com.clmcat.tock.worker.memory.MemorySubscribableWorkerQueue;
import com.clmcat.tock.worker.scheduler.TaskSchedulers;

public class LocalTimerDemo {

    static void main() {

        MemoryTockRegister register = new MemoryTockRegister("test-x", MemoryManager.create());
        Config config = MemoryConfigBuilder.builder("test-x")
                .withHighPrecisionWorker(true)
                .build();


        Tock tock = Tock.configure(config);

        tock.start();
        tock.joinGroup("test");
        tock.addSchedule(ScheduleConfig
                .builder()
                        .cron("*/1 * * * * ?")
                        .jobId("job1")
                        .workerGroup("test")
                        .scheduleId("schedule1")
                        .zoneId("Asia/Shanghai")
                .build());

        tock.registerJob("job1", (ctx)->{
            System.out.println("Job executed at: " + System.currentTimeMillis());
        });
        tock.refreshSchedules();
        tock.sync();


    }
}
