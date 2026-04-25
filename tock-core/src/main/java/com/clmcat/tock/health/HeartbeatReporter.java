package com.clmcat.tock.health;

import com.clmcat.tock.Lifecycle;
import com.clmcat.tock.TockContext;
import com.clmcat.tock.health.client.MasterChangeListener;

/**
 * 心跳上报客户端，运行在 Worker 节点。
 * 负责定期连接 Master 的健康检查服务，发送携带本节点 ID 的心跳。
 */
public interface HeartbeatReporter extends Lifecycle {

    /**
     * 开始上报心跳。
     * 实现应通过注册中心发现 Master 地址，并定期发送心跳。
     */
    @Override
    void start(TockContext context);

    /** 停止上报心跳 */
    @Override
    void stop();

    /**
     * 心跳服务器的时间。
     */
    long serverTime();

    boolean isHeartbeatHealthy();

    void addHeartbeatReportListener(HeartbeatReportListener listener);

    void removeHeartbeatReportListener(HeartbeatReportListener listener);

    void addMasterChangeListener(MasterChangeListener listener);

    void removeMasterChangeListener(MasterChangeListener listener);
}
