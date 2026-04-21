package com.clmcat.tock;

/**
 * 生命周期接口，所有需要启动/停止的组件都应实现。
 */
public interface Lifecycle {
    /**
     * 启动组件，传入全局上下文。
     * 实现应保证幂等性（多次调用不会重复启动）。
     */
    void start(TockContext context);

    /**
     * 停止组件，释放资源。
     */
    void stop();

    /**
     * 是否正在运行。
     */
    boolean isRunning();
}