package com.swoval.files;

import com.swoval.concurrent.ThreadFactory;
import com.swoval.logging.Logger;
import com.swoval.logging.Loggers;
import com.swoval.logging.Loggers.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Provides an execution context to run tasks. Exists to allow source interoperability with scala.js
 */
abstract class Executor implements AutoCloseable {
  Executor() {}

  /**
   * Runs the task on a threadHandle.
   *
   * @param runnable task to run
   */
  void run(final java.lang.Runnable runnable) {
    run(runnable, Integer.MAX_VALUE);
  }

  /**
   * Runs the task with a given priority.
   *
   * @param runnable task to run
   */
  abstract void run(final java.lang.Runnable runnable, final int priority);

  /** Close the executor. All exceptions must be handled by the implementation. */
  @Override
  public void close() {}

  static class ExecutorImpl extends Executor {
    private final AtomicBoolean closed = new AtomicBoolean(false);

    final ThreadFactory factory;
    final ExecutorService service;
    final LinkedBlockingQueue<PriorityRunnable> consumers = new LinkedBlockingQueue<>();
    private final Logger logger;

    ExecutorImpl(final ThreadFactory factory, final ExecutorService service, final Logger logger) {
      this.logger = logger;
      this.factory = factory;
      this.service = service;
      service.submit(
          new java.lang.Runnable() {
            @Override
            public void run() {
              boolean stop = false;
              while (!stop && !closed.get() && !java.lang.Thread.currentThread().isInterrupted()) {
                try {
                  final PriorityQueue<PriorityRunnable> queue = new PriorityQueue<>();
                  queue.add(consumers.take());
                  drainRunnables(queue);
                  while (queue.peek() != null && !stop) {
                    drainRunnables(queue);
                    final PriorityRunnable runnable = queue.poll();
                    assert (runnable != null);
                    stop = runnable.priority < 0;
                    try {
                      runnable.run();
                    } catch (final Exception e) {
                      if (Loggers.shouldLog(logger, Level.ERROR)) {
                        Loggers.logException(logger, e);
                      }
                    }
                  }
                } catch (final InterruptedException e) {
                  stop = true;
                }
              }
            }
          });
    }

    @SuppressWarnings("EmptyCatchBlock")
    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        super.close();
        synchronized (consumers) {
          consumers.clear();
          consumers.offer(STOP);
        }
        service.shutdownNow();
        try {
          if (!service.awaitTermination(5, TimeUnit.SECONDS)) {
            System.err.println("Couldn't close executor");
          }
        } catch (InterruptedException e) {
        }
      }
    }

    @Override
    void run(final Runnable runnable, final int priority) {
      if (closed.get()) {
        if (Loggers.shouldLog(logger, Level.ERROR)) {
          Loggers.logException(logger, new Exception("Tried to submit to closed executor"));
        }
      } else {
        synchronized (consumers) {
          if (!consumers.offer(new PriorityRunnable(runnable, priority))) {
            throw new IllegalStateException(
                "Couldn't run task due to full queue (" + consumers.size() + ")");
          }
        }
      }
    }

    private void drainRunnables(final PriorityQueue<PriorityRunnable> queue) {
      synchronized (consumers) {
        if (consumers.size() > 0) {
          final List<PriorityRunnable> list = new ArrayList<>();
          consumers.drainTo(list);
          queue.addAll(list);
        }
      }
    }
  }

  /**
   * Make a new instance of an Executor
   *
   * @param name The name of the executor threadHandle
   * @return Executor
   */
  static Executor make(final String name, final Logger logger) {
    final ThreadFactory factory = new ThreadFactory(name);
    final ExecutorService service =
        new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<java.lang.Runnable>(), factory) {
          @Override
          protected void afterExecute(java.lang.Runnable r, Throwable t) {
            super.afterExecute(r, t);
            if (t != null) {
              if (Loggers.shouldLog(logger, Level.ERROR)) {
                logger.error("Error running: " + r + "\n" + t);
                Loggers.logException(logger, t);
              }
            }
          }
        };
    return new ExecutorImpl(factory, service, logger);
  }

  private static final class PriorityRunnable implements Runnable, Comparable<PriorityRunnable> {
    private final Runnable runnable;
    private final int priority;

    PriorityRunnable(final Runnable runnable, final int priority) {
      this.runnable = runnable;
      this.priority = priority < 0 ? priority : 0;
    }

    @Override
    public int compareTo(final PriorityRunnable that) {
      return Integer.compare(this.priority, that.priority);
    }

    @Override
    public void run() {
      runnable.run();
    }
  }

  private static final PriorityRunnable STOP =
      new PriorityRunnable(
          new Runnable() {
            @Override
            public void run() {}
          },
          -1);
}
