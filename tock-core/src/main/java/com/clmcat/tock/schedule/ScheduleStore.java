package com.clmcat.tock.schedule;

import java.util.List;

/**
 * <p>用途: 调度配置的存储接口，支持增删改查以及获取全局版本号。</p>
 * <p>存放所有`ScheduleConfig`。Master通过它获取配置并监听变化（轮询版本号或订阅事件）。后期Web管理端通过它动态修改配置。</p>
 */
public interface ScheduleStore {
    /**
     * 整体保存或替换一个调度配置，保存后版本号递增。Master调度器通过版本号变化来感知配置更新。
     * @param config 调度的配置
     */
    void save(ScheduleConfig config);

    /**
     * 删除调度配置
     * @param scheduleId
     */
    void delete(String scheduleId);

    /**
     * 读取这个调度配置
     * @param scheduleId 调度的配置ID
     * @return 调度配置对象
     */
    ScheduleConfig get(String scheduleId);

    /**
     *
     * @return 获取全部调度配置的列表，Master调度器在启动时加载全部配置，并定期检查版本号变化来重新加载。后期Web管理端也可以调用它来显示当前的调度配置列表。
     */
    List<ScheduleConfig> getAll();

    /**
     * 变更版本号
     * @return 版本数字，每次+1的值， 用于是否有变更
     */
    long getGlobalVersion();
}
