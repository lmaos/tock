package com.clmcat.tock.store;

import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 内存版本的 JobStore，用于本地测试。
 * <pre>
 * {@code 结构：Map<scheduleId, Map<nextFireTime, List<JobExecution>>> }
 * </pre>
 */
@Slf4j
public class MemoryJobStore implements JobStore {
    // 外层 key: scheduleId, 内层: 按时间排序的 SkipList
    private final ConcurrentHashMap<String, ConcurrentSkipListMap<Long, List<JobExecution>>> store = new ConcurrentHashMap<>();
    // 辅助映射：executionId -> JobExecution，用于快速删除
    private final ConcurrentHashMap<String, JobExecution> executionMap = new ConcurrentHashMap<>();
    private final ReentrantLock pollLock = new ReentrantLock();

    public static MemoryJobStore create() {
        return new MemoryJobStore();
    }

    @Override
    public void add(JobExecution execution) {
        String sid = execution.getScheduleId();
        long time = execution.getNextFireTime();
        store.compute(sid, (k, timeMap) -> {
            if (timeMap == null) timeMap = new ConcurrentSkipListMap<>();
            timeMap.compute(time, (t, list) -> {
                if (list == null) list = new ArrayList<>();
                list.add(execution);
                return list;
            });
            return timeMap;
        });
        executionMap.put(execution.getExecutionId(), execution);
    }

    @Override
    public List<JobExecution> pollDueTasks(long now, int limit) {
        pollLock.lock();
        try {
            List<JobExecution> result = new ArrayList<>();
            // 遍历所有 scheduleId
            for (ConcurrentSkipListMap<Long, List<JobExecution>> timeMap : store.values()) {
                if (result.size() >= limit) break;
                // 获取 <= now 的头部
                NavigableMap<Long, List<JobExecution>> head = timeMap.headMap(now, true);
                Iterator<Map.Entry<Long, List<JobExecution>>> it = head.entrySet().iterator();
                while (it.hasNext() && result.size() < limit) {
                    Map.Entry<Long, List<JobExecution>> entry = it.next();
                    List<JobExecution> list = entry.getValue();
                    int need = limit - result.size();
                    if (list.size() <= need) {
                        result.addAll(list);
                        it.remove(); // 移除整个时间点
                        for (JobExecution exec : list) {
                            executionMap.remove(exec.getExecutionId());
                        }
                    } else {
                        List<JobExecution> taken = list.subList(0, need);
                        List<JobExecution> remaining = new ArrayList<>(list.subList(need, list.size()));
                        result.addAll(taken);
                        entry.setValue(remaining);
                        for (JobExecution exec : taken) {
                            executionMap.remove(exec.getExecutionId());
                        }
                        break;
                    }
                }
            }
            // 清理空的 scheduleId 条目
            store.entrySet().removeIf(entry -> entry.getValue().isEmpty());
            return result;
        } finally {
            pollLock.unlock();
        }
    }

    @Override
    public boolean remove(String executionId) {
        JobExecution exec = executionMap.remove(executionId);
        if (exec == null) return false;
        String sid = exec.getScheduleId();
        store.computeIfPresent(sid, (k, timeMap) -> {
            timeMap.computeIfPresent(exec.getNextFireTime(), (t, list) -> {
                list.remove(exec);
                return list.isEmpty() ? null : list;
            });
            return timeMap.isEmpty() ? null : timeMap;
        });
        return true;
    }

    @Override
    public boolean hasPending(String scheduleId, long nextFireTime) {
        ConcurrentSkipListMap<Long, List<JobExecution>> timeMap = store.get(scheduleId);
        if (timeMap == null) return false;
        List<JobExecution> list = timeMap.get(nextFireTime);
        return list != null && !list.isEmpty();
    }

    @Override
    public long size() {
        return executionMap.size();
    }
}