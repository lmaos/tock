package com.clmcat.tock.health;

/**
 * 心跳上报状态监听器。
 * <p>
 * 当心跳连续失败达到阈值时触发 {@link #onHeartbeatReportFailed(int)}；
 * 当上报恢复正常时触发 {@link #onHeartbeatReportRecovered()}。
 * </p>
 */
public interface HeartbeatReportListener {

    void onHeartbeatReportFailed(int consecutiveFailures);

    void onHeartbeatReportRecovered();
}
