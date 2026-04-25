package com.clmcat.tock.health.client;

import lombok.Getter;
import lombok.Setter;

import java.nio.ByteBuffer;

@Getter
public class HealthRequest {

    private byte code;
    private byte type;

    public HealthRequest(int code, int type) {
        this.code = (byte)code;
        this.type = (byte)type;
    }

    public byte[] toBytes(int reqId) {
        return toByteBuffer(reqId, 6).array();
    }


    public ByteBuffer toByteBuffer(int reqId, int capacity) {
        ByteBuffer byteBuffer = ByteBuffer.allocate(Math.max(capacity, 6));
        byteBuffer.putInt(reqId);
        byteBuffer.put(code);
        byteBuffer.put(type);
        return byteBuffer;
    }
}