package com.clmcat.demo;

import com.clmcat.tock.health.client.HealthClient;

import java.io.IOException;

public class HealthClientDemo {
    static void main() throws IOException, InterruptedException {
        HealthClient healthClient = new HealthClient("localhost", 38080);

        healthClient.start();


        while (true) {
            long startTime = System.nanoTime();
            long time = healthClient.reportActive("1234567890").getTime();
            long endTime = System.nanoTime();
            System.out.println(time);
            System.out.println(System.currentTimeMillis());
            System.out.println("请求耗时: " + (endTime - startTime) + " Ns");
            Thread.sleep(1000);
        }
    }
}
