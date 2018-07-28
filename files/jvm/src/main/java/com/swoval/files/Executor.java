package com.swoval.files;

import com.swoval.concurrent.ThreadFactory;
import com.swoval.functional.Consumer;
import com.swoval.functional.Either;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
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

  public static class ThreadHandle {
    private ThreadHandle() {}

    private long id = -1;
    private String name = "";

    void setID() {
      if (id == -1) {
        name = java.lang.Thread.currentThread().getName();
        id = java.lang.Thread.currentThread().getId();
      }
    }

    @Override
    public String toString() {
      return "Executor.ThreadHandle(" + name + ", " + id + ")";
    }
  }

  abstract ThreadHandle getThreadHandle();

  /**
   * Runs the task on a threadHandle.
   *
   * @param threadConsumer task to run
   */
  void run(final Consumer<ThreadHandle> threadConsumer) {
    run(threadConsumer, Integer.MAX_VALUE);
  }

  /**
   * Runs the task on a threadHandle with a given priority.
   *
   * @param threadConsumer task to run
   */
  abstract void run(final Consumer<ThreadHandle> threadConsumer, final int priority);

  /**
   * Returns a copy of the executor. The purpose of this is to provide the executor to a class or
   * method without allowing the class or method to close the underlying executor.
   *
   * @return An executor that
   */
  Executor copy() {
    final Executor self = this;
    return new Executor() {
      @Override
      ThreadHandle getThreadHandle() {
        return self.getThreadHandle();
      }

      @Override
      public void run(final Consumer<ThreadHandle> consumer, final int priority) {
        self.run(consumer, priority);
      }
    };
  }

  /**
   * Blocks the current threadHandle until the executor runs the Callable and returns the value.
   *
   * @param callable The callable whose value we're waiting on.
   * @param <T> The result type of the Callable
   * @return The result evaluated by the Callable
   */
  <T> Either<Exception, T> block(final Function<ThreadHandle, T> callable) {
    final ArrayBlockingQueue<Either<Exception, T>> queue = new ArrayBlockingQueue<>(1);
    try {
      run(
          new Consumer<ThreadHandle>() {
            @Override
            public void accept(final ThreadHandle threadHandle) {
              try {
                queue.add(Either.<Exception, T, T>right(callable.apply(threadHandle)));
              } catch (Exception e) {
                queue.add(Either.<Exception, T, Exception>left(e));
              }
            }
          },
          0);
      try {
        return queue.take();
      } catch (InterruptedException e) {
        return Either.left(e);
      }
    } catch (final Exception e) {
      return Either.left(e);
    }
  }

  /**
   * Blocks the current threadHandle until the executor runs the provided Runnable.
   *
   * @param consumer The consumer to invoke.
   */
  @SuppressWarnings("EmptyCatchBlock")
  void block(final Consumer<ThreadHandle> consumer) {
    final CountDownLatch latch = new CountDownLatch(1);
    try {
      run(
          new Consumer<ThreadHandle>() {
            @Override
            public void accept(final ThreadHandle threadHandle) {
              consumer.accept(threadHandle);
              latch.countDown();
            }
          });
      latch.await();
    } catch (InterruptedException e) {
    }
  }

  /** Close the executor. All exceptions must be handled by the implementation. */
  @Override
  public void close() {}

  static class ExecutorImpl extends Executor {
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ThreadHandle threadHandle = new ThreadHandle();
    private final java.lang.Thread executorThread;

    ThreadHandle getThreadHandle() {
      return threadHandle;
    }

    final ThreadFactory factory;
    final ExecutorService service;
    final LinkedBlockingQueue<PriorityConsumer> consumers = new LinkedBlockingQueue<>();

    ExecutorImpl(final ThreadFactory factory, final ExecutorService service)
        throws InterruptedException {
      this.factory = factory;
      this.service = service;
      final LinkedBlockingQueue<java.lang.Thread> queue = new LinkedBlockingQueue<>(1);
      service.submit(
          new Runnable() {
            @Override
            public void run() {
              queue.offer(java.lang.Thread.currentThread());
              boolean stop = false;
              threadHandle.setID();
              while (!stop && !closed.get() && !java.lang.Thread.currentThread().isInterrupted()) {
                try {
                  final PriorityQueue<PriorityConsumer> queue = new PriorityQueue<>();
                  queue.add(consumers.take());
                  drainRunnables(queue);
                  while (queue.peek() != null && !stop) {
                    drainRunnables(queue);
                    final PriorityConsumer consumer = queue.poll();
                    assert (consumer != null);
                    stop = consumer.priority < 0;
                    if (!stop) {
                      try {
                        consumer.accept(getThreadHandle());
                      } catch (final Exception e) {
                        e.printStackTrace();
                      }
                    }
                  }
                } catch (final InterruptedException e) {
                  stop = true;
                }
              }
            }
          });
      executorThread = queue.take();
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
    void run(final Consumer<ThreadHandle> consumer, final int priority) {
      if (closed.get()) {
        new Exception("Tried to submit to closed executor").printStackTrace(System.err);
      } else {
        if (java.lang.Thread.currentThread() == executorThread) {
          try {
            consumer.accept(getThreadHandle());
          } catch (final Exception e) {
            e.printStackTrace();
          }
        } else {
          synchronized (consumers) {
            if (!consumers.offer(new PriorityConsumer(consumer, priority))) {
              throw new IllegalStateException(
                  "Couldn't run task due to full queue (" + consumers.size() + ")");
            }
          }
        }
      }
    }

    private void drainRunnables(final PriorityQueue<PriorityConsumer> queue) {
      synchronized (consumers) {
        if (consumers.size() > 0) {
          final List<PriorityConsumer> list = new ArrayList<>();
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
  static Executor make(final String name) throws InterruptedException {
    final ThreadFactory factory = new ThreadFactory(name);
    final ExecutorService service =
        new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(), factory) {
          @Override
          protected void afterExecute(Runnable r, Throwable t) {
            super.afterExecute(r, t);
            if (t != null) {
              System.err.println("Error running: " + r + "\n" + t);
              t.printStackTrace(System.err);
            }
          }
        };
    return new ExecutorImpl(factory, service);
  }

  private static final class PriorityConsumer
      implements Consumer<ThreadHandle>, Comparable<PriorityConsumer> {
    private final Consumer<ThreadHandle> consumer;
    private final int priority;

    PriorityConsumer(final Consumer<ThreadHandle> consumer, final int priority) {
      this.consumer = consumer;
      this.priority = priority < 0 ? priority : 0;
    }

    @Override
    public int compareTo(final PriorityConsumer that) {
      return Integer.compare(this.priority, that.priority);
    }

    @Override
    public void accept(final ThreadHandle threadHandle) {
      consumer.accept(threadHandle);
    }
  }

  private static final PriorityConsumer STOP =
      new PriorityConsumer(
          new Consumer<ThreadHandle>() {
            @Override
            public void accept(final ThreadHandle threadHandle) {}
          },
          -1);
}
