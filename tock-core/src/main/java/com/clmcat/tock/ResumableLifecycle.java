package com.clmcat.tock;

import java.util.concurrent.atomic.AtomicBoolean;

public interface ResumableLifecycle extends Lifecycle {


    void pause(boolean force);
    void resume();
    boolean isRunning();

    /**
     * 支持暂停/恢复的生命周期抽象基类。
     * <p>
     * 在 {@link AbstractLifecycle} 的基础上增加了 {@code running} 状态，
     * 并提供 {@link #resume()} 和 {@link #pause(boolean)} 方法。
     * 子类只需实现 {@link #onResume()} 和 {@link #onPause(boolean)} 即可。
     * </p>
     *
     * <p>
     * <b>状态转换：</b>
     * <ul>
     *   <li>启动后调用 {@code resume()} 进入工作状态。</li>
     *   <li>暂停时调用 {@code pause(force)} 退出工作状态。</li>
     *   <li>优雅关闭时先暂停再调用 {@link #stop()}。</li>
     * </ul>
     * </p>
     */
    abstract class AbstractResumableLifecycle extends AbstractResumableNoImplLifecycle implements ResumableLifecycle {

    }
    abstract class AbstractResumableNoImplLifecycle extends Lifecycle.AbstractNoImplLifecycle {



        private final AtomicBoolean running = new AtomicBoolean(false);

        /**
         * 当前是否处于工作（running）状态。
         */
        public boolean isRunning() {
            return isStarted() && running.get();
        }

        /**
         * 尝试进入工作状态。如果组件尚未启动，则无效。
         * 如果已经在工作状态，则忽略。
         */
        public final void resume() {
            if (!isStarted()) {
                return;
            }
            if (running.compareAndSet(false, true)) {
                try {
                    onResume();
                } catch (Exception e) {
                    onError(e, 2); // 可用不同的错误码区分 resume 中的异常
                    running.set(false);
                }
            }
        }

        /**
         * 尝试暂停工作状态。
         *
         * @param force 是否强制中断正在执行的任务（通常传递给 Future.cancel）
         */
        public final void pause(boolean force) {
            if (running.compareAndSet(true, false)) {
                try {
                    onPause(force);
                } catch (Exception e) {
                    onError(e, 3);
                    // 即使 onPause 失败，running 依然为 false，保持暂停语义
                }
            }
        }

        /**
         * 子类实现：从非工作状态进入工作状态时应执行的操作。
         * （例如重新加入工作组、启动拉取线程等）
         */
        protected abstract void onResume();

        /**
         * 子类实现：从工作状态进入暂停状态时应执行的操作。
         * （例如退出工作组、取消正在执行的任务等）
         *
         * @param force 是否强制中断
         */
        protected abstract void onPause(boolean force);

        @Override
        public void stop() {
            if (started.compareAndSet(true, false)) {
                try {
                    pause(true); // 停止前先强制暂停，确保所有任务都已中断
                    onStop();
                } catch (Exception e) {
                    onError(e, 1);
                }
            }
        }
    }
}
