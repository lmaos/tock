package com.clmcat.tock;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

class ResumableLifecycleConcurrencyTest {

    @Test
    void resumeDoesNotRunBeforeStartFinishes() throws Exception {
        BlockingLifecycle lifecycle = new BlockingLifecycle();

        Thread startThread = new Thread(() -> lifecycle.start(null), "lifecycle-start");
        startThread.start();

        Assertions.assertTrue(lifecycle.startEntered.await(5, TimeUnit.SECONDS));

        Thread resumeThread = new Thread(lifecycle::resume, "lifecycle-resume");
        resumeThread.start();

        Assertions.assertFalse(lifecycle.resumeEntered.await(200, TimeUnit.MILLISECONDS));
        Assertions.assertFalse(lifecycle.resumeEnteredDuringStart.get());
        lifecycle.releaseStart.countDown();
        lifecycle.releaseResume.countDown();

        startThread.join(5000);
        resumeThread.join(5000);

        Assertions.assertFalse(startThread.isAlive());
        Assertions.assertFalse(resumeThread.isAlive());
        Assertions.assertFalse(lifecycle.resumeEnteredDuringStart.get());
        Assertions.assertTrue(lifecycle.isStarted());
        Assertions.assertTrue(lifecycle.isRunning());
    }

    @Test
    void pauseDoesNotOverlapResume() throws Exception {
        BlockingLifecycle lifecycle = new BlockingLifecycle();
        Thread startThread = new Thread(() -> lifecycle.start(null), "lifecycle-start");
        startThread.start();
        Assertions.assertTrue(lifecycle.startEntered.await(5, TimeUnit.SECONDS));
        lifecycle.releaseStart.countDown();
        startThread.join(5000);

        Thread resumeThread = new Thread(lifecycle::resume, "lifecycle-resume");
        resumeThread.start();

        Assertions.assertTrue(lifecycle.resumeEntered.await(5, TimeUnit.SECONDS));

        Thread pauseThread = new Thread(() -> lifecycle.pause(false), "lifecycle-pause");
        pauseThread.start();

        Assertions.assertFalse(lifecycle.pauseEntered.await(200, TimeUnit.MILLISECONDS));
        Assertions.assertFalse(lifecycle.pauseObservedWhileResumeActive.get());
        lifecycle.releaseResume.countDown();

        resumeThread.join(5000);
        pauseThread.join(5000);

        Assertions.assertFalse(resumeThread.isAlive());
        Assertions.assertFalse(pauseThread.isAlive());
        Assertions.assertFalse(lifecycle.pauseObservedWhileResumeActive.get());
    }

    private static final class BlockingLifecycle extends ResumableLifecycle.AbstractResumableLifecycle {
        private final CountDownLatch startEntered = new CountDownLatch(1);
        private final CountDownLatch releaseStart = new CountDownLatch(1);
        private final CountDownLatch resumeEntered = new CountDownLatch(1);
        private final CountDownLatch releaseResume = new CountDownLatch(1);
        private final CountDownLatch pauseEntered = new CountDownLatch(1);
        private final AtomicBoolean startFinished = new AtomicBoolean(false);
        private final AtomicBoolean resumeActive = new AtomicBoolean(false);
        private final AtomicBoolean resumeEnteredDuringStart = new AtomicBoolean(false);
        private final AtomicBoolean pauseObservedWhileResumeActive = new AtomicBoolean(false);

        @Override
        protected void onStart() {
            startEntered.countDown();
            awaitUnchecked(releaseStart);
            startFinished.set(true);
        }

        @Override
        protected void onStop() {
        }

        @Override
        protected void onResume() {
            if (!startFinished.get()) {
                resumeEnteredDuringStart.set(true);
            }
            resumeActive.set(true);
            resumeEntered.countDown();
            awaitUnchecked(releaseResume);
            resumeActive.set(false);
        }

        @Override
        protected void onPause(boolean force) {
            pauseEntered.countDown();
            if (resumeActive.get()) {
                pauseObservedWhileResumeActive.set(true);
            }
        }

        private void awaitUnchecked(CountDownLatch latch) {
            try {
                Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Assertions.fail(e);
            }
        }
    }
}
