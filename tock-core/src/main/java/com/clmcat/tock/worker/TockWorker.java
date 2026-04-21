package com.clmcat.tock.worker;

import com.clmcat.tock.Lifecycle;
import com.clmcat.tock.TockContext;

import java.util.Set;
/**
 * 工作者，消费调度器推送的任务，执行具体的业务逻辑。Worker 可以有多个实例，分布式部署，负责抢占执行任务。
 */
public interface TockWorker extends Lifecycle {

    /**
     * 启动 Worker，开始订阅队列（通常在一个独立线程池中运行）。
     */
    void start(TockContext context);

    /**
     * 停止 Worker，中断等待中的队列拉取，释放资源。
     */
    void stop();

    /**
     * 将当前 Worker 加入指定的工作组。
     * Worker 将订阅该组对应的任务队列。
     * @param groupName 工作组名称
     */
    void joinGroup(String groupName);

    /**
     * 离开指定的工作组，不再消费该组的任务。
     */
    void leaveGroup(String groupName);

    /**
     * 当前 Worker 是否正在运行。
     */
    boolean isRunning();

    /**
     * 获取 Worker 当前所属的所有工作组。
     */
    Set<String> getGroups();
}