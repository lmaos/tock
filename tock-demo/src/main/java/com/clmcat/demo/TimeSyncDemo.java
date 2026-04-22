package com.clmcat.demo;

import com.clmcat.tock.time.DefaultTimeSynchronizer;
import com.clmcat.tock.time.TimeProvider;
import redis.clients.jedis.Jedis;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TimeSyncDemo {

    static void main() throws InterruptedException {




        ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

        for (int i = 0; i < 2; i++) {
            int k = i;
            executorService.execute(() -> {
                    Jedis jedis = new Jedis("localhost", 6379);
                TimeProvider redis = () -> {
                    List<String> time = jedis.time();
                    long seconds = Long.parseLong(time.get(0));
                    long microseconds = Long.parseLong(time.get(1));
                    return seconds * 1000 + microseconds / 1000;
                };
                DefaultTimeSynchronizer synchronizer = new DefaultTimeSynchronizer(redis, 5000, 5);
                synchronizer.start(null);
                int index = 0;
                while (true) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    System.out.println(k + ",index=" + index + ", sync=" + synchronizer.currentTimeMillis() + ",redis=" + redis.currentTimeMillis() + ", local=" + System.currentTimeMillis());
                    index++;
                }
            });
        }


        Thread.sleep(100000);


    }
}
