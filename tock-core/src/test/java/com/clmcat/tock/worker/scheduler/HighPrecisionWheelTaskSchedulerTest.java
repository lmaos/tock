package com.clmcat.tock.worker.scheduler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class HighPrecisionWheelTaskSchedulerTest {

    private final HighPrecisionWheelTaskScheduler scheduler = new HighPrecisionWheelTaskScheduler(4, "wheel-test");

    @AfterEach
    void tearDown() {
        scheduler.stop();
    }

    @Test
    void shouldExecuteNegativeOrZeroDelayImmediately() throws Exception {
        scheduler.start(null);
        CountDownLatch latch = new CountDownLatch(2);

        Future<?> zeroDelay = scheduler.schedule(latch::countDown, 0L, TimeUnit.MILLISECONDS);
        Future<?> negativeDelay = scheduler.schedule(latch::countDown, -5L, TimeUnit.MILLISECONDS);

        Assertions.assertNotNull(zeroDelay);
        Assertions.assertNotNull(negativeDelay);
        Assertions.assertTrue(latch.await(1, TimeUnit.SECONDS));
    }

    @Test
    void shouldCancelDelayedTask() throws Exception {
        scheduler.start(null);
        AtomicInteger runCount = new AtomicInteger(0);

        Future<?> future = scheduler.schedule(runCount::incrementAndGet, 300L, TimeUnit.MILLISECONDS);
        Assertions.assertTrue(future.cancel(false));

        Thread.sleep(450L);
        Assertions.assertEquals(0, runCount.get());
        Assertions.assertTrue(future.isCancelled());
        Assertions.assertTrue(future.isDone());
    }

    @Test
    void shouldSupportVeryLargeDelayWithoutCrash() {
        scheduler.start(null);
        Future<?> future = scheduler.schedule(
                () -> {
                },
                Long.MAX_VALUE / 1_000_000L,
                TimeUnit.MILLISECONDS
        );

        Assertions.assertNotNull(future);
        Assertions.assertFalse(future.isDone());
        Assertions.assertTrue(future.cancel(false));
    }

    @Test
    void shouldKeepWorkingWhenBaseTimeChangedByReflection() throws Exception {
        scheduler.start(null);

        Field baseField = HighPrecisionWheelTaskScheduler.class.getDeclaredField("baseTimeNanos");
        baseField.setAccessible(true);
        baseField.setLong(scheduler, System.nanoTime() - TimeUnit.SECONDS.toNanos(10));

        CountDownLatch latch = new CountDownLatch(1);
        scheduler.schedule(latch::countDown, 10L, TimeUnit.MILLISECONDS);
        Assertions.assertTrue(latch.await(2, TimeUnit.SECONDS));
    }

    @Test
    void shouldStopDriverThreadOnStop() throws Exception {
        scheduler.start(null);
        Field threadField = HighPrecisionWheelTaskScheduler.class.getDeclaredField("driverThread");
        threadField.setAccessible(true);
        Thread driverThread = (Thread) threadField.get(scheduler);

        scheduler.stop();

        Assertions.assertNotNull(driverThread);
        Assertions.assertFalse(driverThread.isAlive());
    }

    @Test
    void shouldBeThreadSafeWhenSchedulingConcurrently() throws Exception {
        scheduler.start(null);
        int producerCount = 4;
        int tasksPerProducer = 300;
        CountDownLatch doneLatch = new CountDownLatch(producerCount * tasksPerProducer);
        List<Thread> producers = new ArrayList<>();

        for (int i = 0; i < producerCount; i++) {
            Thread producer = new Thread(() -> {
                for (int j = 0; j < tasksPerProducer; j++) {
                    scheduler.schedule(doneLatch::countDown, 1L, TimeUnit.MILLISECONDS);
                }
            });
            producer.setDaemon(true);
            producers.add(producer);
        }

        for (Thread producer : producers) {
            producer.start();
        }
        for (Thread producer : producers) {
            producer.join();
        }

        Assertions.assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
        Assertions.assertTrue(scheduler.isStarted());
    }

    @Test
    void shouldExecuteOnlyNonCancelledTasks() throws Exception {
        scheduler.start(null);
        int total = 200;
        AtomicInteger executed = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < total; i++) {
            futures.add(scheduler.schedule(executed::incrementAndGet, 200L, TimeUnit.MILLISECONDS));
        }

        int cancelled = 0;
        for (int i = 0; i < futures.size(); i += 2) {
            if (futures.get(i).cancel(false)) {
                cancelled++;
            }
        }

        Thread.sleep(500L);
        Assertions.assertEquals(total - cancelled, executed.get());
    }
}
