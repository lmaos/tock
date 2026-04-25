package com.clmcat.tock.health.client;

import java.nio.ByteBuffer;

public class HealthReportActiveRequest extends HealthRequest {

    private byte[] nodeIdBytes;
    private short length;
     public HealthReportActiveRequest(String nodeId) {
        super(0, 1);
        this.nodeIdBytes = nodeId.getBytes();
        this.length = (short) nodeId.length();

    }

    @Override
    public byte[] toBytes(int reqId) {
        return super.toByteBuffer(reqId, 6 + 2 + length)
                .putShort(length)
                .put(nodeIdBytes)
                .array();
    }
}
