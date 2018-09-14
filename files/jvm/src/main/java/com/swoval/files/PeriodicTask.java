package com.swoval.files;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

class PeriodicTask implements AutoCloseable {
  private static final AtomicInteger threadID = new AtomicInteger(0);
  private final CountDownLatch latch = new CountDownLatch(1);
  private final CountDownLatch shutdownLatch = new CountDownLatch(1);
  private final Runnable runnable;
  private final PeriodicThread thread;
  private final long pollIntervalMS;
  private final AtomicBoolean isClosed = new AtomicBoolean(false);

  PeriodicTask(final Runnable runnable, final long pollIntervalMS) throws InterruptedException {
    this.runnable = runnable;
    this.pollIntervalMS = pollIntervalMS;
    this.thread = new PeriodicThread();
  }

  @Override
  public void close() throws InterruptedException {
    if (isClosed.compareAndSet(false, true)) {
      thread.interrupt();
      shutdownLatch.await(5, TimeUnit.SECONDS);
      thread.join(5000);
    }
  }

  private class PeriodicThread extends Thread {
    PeriodicThread() throws InterruptedException {
      super("com.swoval.files.PeriodicThread-" + threadID.getAndIncrement());
      setDaemon(true);
      start();
      latch.await(5, TimeUnit.SECONDS);
    }

    @Override
    public void run() {
      latch.countDown();
      while (!isClosed.get() && !Thread.currentThread().isInterrupted()) {
        try {
          runnable.run();
          Thread.sleep(pollIntervalMS);
        } catch (final InterruptedException e) {
          isClosed.set(true);
        }
      }
      shutdownLatch.countDown();
    }
  }
}
