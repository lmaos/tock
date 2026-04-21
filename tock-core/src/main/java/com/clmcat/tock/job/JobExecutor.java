package com.clmcat.tock.job;

/**
 * <p>用途: 用户实现的业务逻辑接口，包含 execute(JobContext)方法。</p>
 *
 * <p>Worker执行任务时调用的回调。</p>
 */
public interface JobExecutor {

    void execute(JobContext context) throws Exception;
}
