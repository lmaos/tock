package com.clmcat.tock.registry;

import com.clmcat.tock.Lifecycle;
import com.clmcat.tock.TockContext;

/**
 * 选择自己是主机，成为主机， isMaster 返回true
 */
public interface TockMaster  {
    /**
     * 当前是否是主机, 非阻塞。
     * @return true 是主机
     */
    boolean isMaster();

    /** 注册监听器 */
    void addListener(MasterListener listener);

     /** 移除监听器 */
    void removeListener(MasterListener listener);

    /**
     * 启动选主循环（内部异步持续竞争）， 非阻塞，内部线程持续选主，状态变化时触发监听器
     */
    void start(TockContext context);

    /**
     * 停止竞争，如果我是主机则释放主机。
     */
    void stop();

    boolean isRunning();


    String getMasterName();
}
