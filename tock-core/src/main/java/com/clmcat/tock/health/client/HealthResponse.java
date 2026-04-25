package com.clmcat.tock.health.client;

import lombok.Builder;
import lombok.Getter;


@Getter
@Builder
public class HealthResponse {

    private int reqId; // 4
    private byte code; // 1
    private byte type; // 1
    private long time; // 8


    public final static HealthResponse SYSTEM_ERROR = HealthResponse.builder()
            .code((byte) 0xFF)
            .type((byte) 0xFF)
            .time(0)
            .build();

    public final static HealthResponse TIMEOUT = HealthResponse.builder()
            .code((byte) 0xFF)
            .type((byte) 0xFE)
            .time(0)
            .build();

    public final static HealthResponse NOT_FOUND = HealthResponse.builder()
            .code((byte) 0xFF)
            .type((byte) 0xFD)
            .time(0)
            .build();
}