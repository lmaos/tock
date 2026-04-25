package com.clmcat.demo;

import com.clmcat.tock.health.server.HealthServer;

import java.io.IOException;

public class HealthServerDemo {
    static void main() throws IOException, InterruptedException {
        HealthServer healthServer = new HealthServer(38080, 1) {
            @Override
            protected void onActive(ChannelContext channelContext) {
                System.out.println("Active: " + channelContext.getClientId());
            }
        };
        healthServer.start();

        Thread.sleep(1000000);
    }
}
