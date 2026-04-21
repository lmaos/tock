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
}