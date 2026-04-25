package com.clmcat.tock.health.client;

import lombok.Getter;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;

/**
 * 请求的上下文处理。
 */

public  class RequestContext {
    @Getter
    private final byte[] requestData;
    private volatile HealthResponse response;
    private volatile Thread currentThread;


    public RequestContext(byte[] requestData) {
        this.requestData = requestData;
    }

    public HealthResponse response(long readTimeout) throws InterruptedException, TimeoutException {
        this.currentThread = Thread.currentThread();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(readTimeout);
        while (response == null) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new TimeoutException();
            }
            LockSupport.parkNanos(remaining);
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
        }
        return response;
    }

    public void setResponse(HealthResponse response) {
        this.response = response;
        if (this.currentThread != null) {
            LockSupport.unpark(currentThread);
        }
    }
}