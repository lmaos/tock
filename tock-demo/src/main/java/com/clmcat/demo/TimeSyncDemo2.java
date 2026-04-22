package com.clmcat.demo;

import com.clmcat.tock.time.TimeProvider;
import redis.clients.jedis.Jedis;

import java.util.List;
import java.util.concurrent.TimeUnit;

public class TimeSyncDemo2 {

    static void main() throws InterruptedException {



        Jedis jedis = new Jedis("localhost", 6379);
        TimeProvider redis = () -> {
            List<String> time = jedis.time();
            long seconds = Long.parseLong(time.get(0));
            long microseconds = Long.parseLong(time.get(1));
            return seconds * 1000 + microseconds / 1000;
        };


        for (int i = 0; i < 10; i++) {
            RemoteTime remoteTime = remoteTime(redis);
            System.out.println(remoteTime +"," + System.currentTimeMillis());

            System.out.println(

            );

            Thread.sleep(100);
        }
    }


    public static RemoteTime remoteTime(TimeProvider timeProvider) {
        RemoteTime remoteTime = new RemoteTime();
        remoteTime.localTimeMs = System.currentTimeMillis();
        long start = System.nanoTime();
        long time = timeProvider.currentTimeMillis();
        long end = System.nanoTime();
        remoteTime.timeDiffNanos = (end - start);
        remoteTime.time = time;
        remoteTime.localOffsetMs = remoteTime.localTimeMs - remoteTime.time;
        return remoteTime;
    }



    public static class RemoteTime {
        private long time;
        private long timeDiffNanos;
        private long localOffsetMs;
        private long localTimeMs;

        @Override
        public String toString() {
            return "RemoteTime{" +
                    "time=" + time +
                    ", timeDiffNanos=" + timeDiffNanos +
                    ", localOffsetMs=" + localOffsetMs +
                    ", localTimeMs=" + localTimeMs +
                    '}';
        }
    }
}
