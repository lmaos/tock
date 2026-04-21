package com.clmcat.demo;

import com.clmcat.tock.worker.scheduler.SpinWaitSupport;

public class OnSpinWaitDemo {


    static void main() {
        for (int i = 0; i < 1000000; i++) {
            // 模拟自旋等待
            SpinWaitSupport.onSpinWait();

        }

        System.out.println("Spin wait demo completed.");
    }


}
