package com.clmcat.tock;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicBoolean;

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
    boolean isStarted();


    default void init(TockContext context) {}

    abstract class AbstractLifecycle extends AbstractNoImplLifecycle implements Lifecycle {}

    @Slf4j
    abstract class AbstractNoImplLifecycle {
        final AtomicBoolean started = new AtomicBoolean(false);
        protected TockContext context;


        public void init(TockContext context) {
            this.context = context;
            onInit();
        }


        public void start(TockContext context) {
            this.context = context;
            if (started.compareAndSet(false, true)) {
                try {
                    onStart();
                } catch (Exception e) {
                    onError(e, 0);
                    started.set(false);
                }
            }
        }


        public void stop() {
            if (started.compareAndSet(true, false)) {
                try {
                    onStop();
                } catch (Exception e) {
                    onError(e, 1);
                }
            }
        }


        public boolean isStarted() {
            return started.get();
        }

        protected void onInit() {}
        protected abstract void onStart();
        protected abstract void onStop();
        protected void onError(Throwable throwable, int p) {
            log.error("Lifecycle error in {}: {}", getClass().getSimpleName(), throwable.getMessage(), throwable);
        }
    }


}