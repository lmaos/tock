package com.clmcat.tock.worker.scheduler;

import com.clmcat.tock.Lifecycle;

import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TaskSchedulers  {

    public static HighPrecisionWheelTaskScheduler highPrecision(String name) {
        return new HighPrecisionWheelTaskScheduler(name);
    }

    public static ScheduledExecutorTaskScheduler schedulerExecutor(String name) {
        return new ScheduledExecutorTaskScheduler(name);
    }

}