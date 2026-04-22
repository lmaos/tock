package com.clmcat.tock.worker.scheduler;

import lombok.extern.slf4j.Slf4j;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
@Slf4j
public final class SpinWaitSupport {
    private static final boolean ON_SPIN_WAIT_AVAILABLE;
    private static final MethodHandle ON_SPIN_WAIT_HANDLE;

    static {
        boolean available = false;
        MethodHandle handle = null;
        try {
            // 尝试获取 Thread.onSpinWait() 的 MethodHandle
            MethodType mt = MethodType.methodType(void.class);
            handle = MethodHandles.publicLookup().findStatic(Thread.class, "onSpinWait", mt);
            available = true;
        } catch (NoSuchMethodException | IllegalAccessException e) {
            // Java 8 环境下会进入这里
        }
        ON_SPIN_WAIT_AVAILABLE = available;
        ON_SPIN_WAIT_HANDLE = handle;
    }

    /**
     * 执行一次自旋等待提示。如果当前 JVM 支持 Thread.onSpinWait()，则调用之；
     * 否则退化为空操作（纯自旋由外层循环控制）。
     */
    public static void onSpinWait() {
        if (ON_SPIN_WAIT_AVAILABLE) {
            try {
                ON_SPIN_WAIT_HANDLE.invokeExact();
            } catch (Throwable ignored) {
                // 不应发生
                log.debug("Spin Wait Thread has been closed", ignored);
            }
        }
    }

    /**
     * 当前 JVM 是否支持 Thread.onSpinWait()。
     */
    static boolean isOnSpinWaitAvailable() {
        return ON_SPIN_WAIT_AVAILABLE;
    }


    protected static void onSpinWait(long deadlineNanos) {
        // 默认实现：每 100 次自旋检查一次时间，其余迭代仅忙等
        int spins = 0;
        if (isOnSpinWaitAvailable()) {
            // Java 9+：使用 Thread.onSpinWait() 优化
            while (((spins++ & 0x7F) != 0 || System.nanoTime() - deadlineNanos < 0)) {
                onSpinWait();
            }
        } else {
            // Java 8：纯空循环，定期检查时间
            // 每 128 次自旋检查一次时间（掩码 0x7F）
            while (((spins++ & 0x7F) != 0 || System.nanoTime() - deadlineNanos < 0)) {
                // 空循环体
            }
        }
    }
}