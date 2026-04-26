package com.clmcat.tock.health;

import com.clmcat.tock.Lifecycle;
import com.clmcat.tock.ResumableLifecycle;
import com.clmcat.tock.TockContext;
import com.clmcat.tock.TockContextAware;
import com.clmcat.tock.health.server.HealthServer;
import com.clmcat.tock.health.server.HeartbeatManager;
import com.clmcat.tock.registry.MasterListener;
import com.clmcat.tock.registry.TockNode;
import com.clmcat.tock.registry.TockRegister;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

@Slf4j
public class DefaultHealthMaintainer extends ResumableLifecycle.AbstractResumableLifecycle implements HealthMaintainer, Lifecycle {

    private TockRegister  register;
    private MasterListener masterListener;

    private Set<NodeHealthListener> listeners = ConcurrentHashMap.newKeySet();

    private ScheduledExecutorService task;

    private ScheduledFuture<?> scheduledFuture;



    private HealthServer healthServer;

    private HeartbeatManager heartbeatManager = new HeartbeatManager();



    @Override
    public void onInit() {
        this.register = context.getRegister();
    }
    @Override
    public void addListener(NodeHealthListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeListener(NodeHealthListener listener) {
        listeners.remove(listener);
    }


    @Override
    public void onStart() {


            healthServer  = new HealthServer(0, 2) {
                @Override
                protected void onActive(ChannelContext channelContext) {
                    reportActive(channelContext.getClientId());
                }
            };
            task = Executors.newScheduledThreadPool(1, new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r);
                    t.setDaemon(true);
                    t.setName(context.getRegister().getNamespace() + "-NodeHealthMaintainer");
                    return t;
                }
            });

            context.getRegister().getMaster()
                    .addListener(masterListener = new MasterListener() {
                @Override
                public void onBecomeMaster() {
                    resume();
                }

                @Override
                public void onLoseMaster() {
                    pause(false);
                }
            });

    }


    @Override
    public void onStop() {
        if (context != null && context.getRegister() != null && masterListener != null) {
            context.getRegister().getMaster().removeListener(masterListener);
        }
        if (task != null) {
            task.shutdownNow();
            task = null;
        }
    }

    protected void onResume() {


            heartbeatManager.clear();
            // 启用内置心跳服务。
            try {
                HealthHost healthHost = healthServer.start();
                register.setGroupAttribute("health.host", healthHost);
            } catch (Exception e) {
                log.warn("Failed to start health server", e);
            }
            // 启动定时任务，扫描过期节点并通知监听器。
            this.scheduledFuture = task.scheduleWithFixedDelay(() -> {
                long timeoutMillis = 2000;
                // 更快的超时
                Map<String, Long> timeoutMap  = heartbeatManager.getTimeoutWithTimestamp(timeoutMillis);
                for (Map.Entry<String, Long> entry : timeoutMap.entrySet()) {
                    String timeoutClientId = entry.getKey();
                    long snapshotTimestamp  = entry.getValue();
                    TockNode node = context.getRegister().getNode(timeoutClientId);
                    if (node != null) {
                        if (heartbeatManager.remove(timeoutClientId, snapshotTimestamp )) {
                            // 在同一线程执行。 保证先处理过期前的逻辑。
                            for (NodeHealthListener listener : listeners) {
                                try {
                                    listener.onNodeExpired(node);
                                } catch (Exception e) {
                                    // 不干扰其他执行， 异常就是异常了。
                                    log.error("Failed to notify listener: {}", timeoutClientId, e);
                                }
                            }
                            // 移除当前节点。
                            context.getRegister().removeNode(timeoutClientId);
                        }
                    }
                }


                // 最后的保证， 如果未在心跳管理中，或者 之前的心跳管理移除失败，则从这里开始继续监控， 默认是 5s超时， 稍微大一些的时间。
                List<TockNode> expiredNodes = context.getRegister().getExpiredNodes();
                for (TockNode node : expiredNodes) {
                    if (heartbeatManager.containsKey(node.getId())) {
                        // 心跳管理器里有这个节点，说明它还活着，不算过期。
                        continue;
                    }
                    for (NodeHealthListener listener : listeners) {
                        listener.onNodeExpired(node);
                    }
                    context.getRegister().removeNode(node.getId());
                }
            }, 1, 1, TimeUnit.SECONDS);


    }

    protected void onPause(boolean force) {

        if (this.scheduledFuture != null) {
            this.scheduledFuture.cancel(force);
        }
        this.scheduledFuture = null;
        if (register != null) {
            register.removeGroupAttribute("health.host");
        }
        if (healthServer != null) {
            healthServer.stop();
        }
        heartbeatManager.clear();

    }




    protected void reportActive(String nodeId) {
        if (!isRunning()) return; // 没有启用，则无效
//        log.debug("Node {} has been activated", nodeId);

        task.submit(() -> {
            heartbeatManager.refresh(nodeId);
        });
    }

}
