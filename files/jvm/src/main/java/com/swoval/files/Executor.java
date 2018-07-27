package com.swoval.files;

import com.swoval.functional.Consumer;
import com.swoval.functional.Either;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Provides an execution context to run tasks. Exists to allow source interoperability with scala.js
 */
abstract class Executor implements AutoCloseable {
  Executor() {}

  public class Thread {
    private final long id;
    private final String name;

    private Thread(final long id, final String name) {
      this.id = id;
      this.name = name;
    }

    @Override
    public String toString() {
      return "Executor.Thread(" + name + ", " + id + ")";
    }
  }

  abstract Thread getThread();

  <T> BiConsumer<T, Thread> delegate(final BiConsumer<T, Thread> consumer) {
    return new BiConsumer<T, Thread>() {
      @Override
      public void accept(final T t, final Thread thread) {
        run(
            new Consumer<Thread>() {
              @Override
              public void accept(Thread thread) {
                consumer.accept(t, thread);
              }
            });
      }
    };
  }

  /**
   * Runs the task on a thread.
   *
   * @param threadConsumer task to run
   */
  void run(final Consumer<Thread> threadConsumer) {
    run(threadConsumer, Integer.MAX_VALUE);
  }

  /**
   * Runs the task on a thread with a given priority.
   *
   * @param threadConsumer task to run
   */
  abstract void run(final Consumer<Thread> threadConsumer, final int priority);

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
      Thread getThread() {
        return self.getThread();
      }

      @Override
      public void run(final Consumer<Thread> consumer, final int priority) {
        self.run(consumer, priority);
      }
    };
  }

  /**
   * Blocks the current thread until the executor runs the Callable and returns the value.
   *
   * @param callable The callable whose value we're waiting on.
   * @param <T> The result type of the Callable
   * @return The result evaluated by the Callable
   */
  <T> Either<Exception, T> block(final Function<Thread, T> callable) {
    final ArrayBlockingQueue<Either<Exception, T>> queue = new ArrayBlockingQueue<>(1);
    try {
      run(
          new Consumer<Thread>() {
            @Override
            public void accept(final Thread thread) {
              try {
                queue.add(Either.<Exception, T, T>right(callable.apply(thread)));
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
   * Blocks the current thread until the executor runs the provided Runnable.
   *
   * @param consumer The consumer to invoke.
   */
  @SuppressWarnings("EmptyCatchBlock")
  void block(final Consumer<Thread> consumer) {
    final CountDownLatch latch = new CountDownLatch(1);
    try {
      run(
          new Consumer<Thread>() {
            @Override
            public void accept(final Thread thread) {
              consumer.accept(thread);
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
    private final Thread thread;
    private static final AtomicInteger threadId = new AtomicInteger(0);

    Thread getThread() {
      return thread;
    }

    private final java.lang.Thread executorThread;

    private java.lang.Thread getExecutorThread(
        final String name, final LinkedBlockingQueue<Thread> queue) {
      return new java.lang.Thread(name + "-" + threadId.getAndIncrement()) {
        @Override
        public void run() {
          final java.lang.Thread jThread = java.lang.Thread.currentThread();
          queue.offer(new Thread(jThread.getId(), jThread.getName()));
          boolean stop = false;
          while (!stop && !closed.get() && !jThread.isInterrupted()) {
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
                    consumer.accept(getThread());
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
      };
    }

    final LinkedBlockingQueue<PriorityConsumer> consumers = new LinkedBlockingQueue<>();

    ExecutorImpl(final String name) throws InterruptedException {
      final LinkedBlockingQueue<Thread> queue = new LinkedBlockingQueue<>(1);
      executorThread = getExecutorThread(name, queue);
      executorThread.start();
      thread = queue.take();
    }

    @SuppressWarnings("EmptyCatchBlock")
    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        super.close();
        executorThread.interrupt();
        synchronized (consumers) {
          consumers.clear();
          consumers.offer(STOP);
        }
        try {
          executorThread.join(5000);
        } catch (final InterruptedException e) {
        }
      }
    }

    @Override
    void run(final Consumer<Thread> consumer, final int priority) {
      if (closed.get()) {
        new Exception("Tried to submit to closed executor").printStackTrace(System.err);
      } else {
        if (java.lang.Thread.currentThread().getId() == executorThread.getId()) {
          try {
            consumer.accept(getThread());
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
   * @param name The name of the executor thread
   * @return Executo
   */
  static Executor make(final String name) throws InterruptedException {
    return new ExecutorImpl(name);
  }

  private static final class PriorityConsumer
      implements Consumer<Thread>, Comparable<PriorityConsumer> {
    private final Consumer<Thread> consumer;
    private final int priority;

    PriorityConsumer(final Consumer<Thread> consumer, final int priority) {
      this.consumer = consumer;
      this.priority = priority < 0 ? priority : 0;
    }

    @Override
    public int compareTo(final PriorityConsumer that) {
      return Integer.compare(this.priority, that.priority);
    }

    @Override
    public void accept(final Thread thread) {
      consumer.accept(thread);
    }
  }

  private static final PriorityConsumer STOP =
      new PriorityConsumer(
          new Consumer<Thread>() {
            @Override
            public void accept(final Thread thread) {}
          },
          -1);
}
