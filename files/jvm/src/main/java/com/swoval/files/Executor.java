package com.swoval.files;

import com.swoval.concurrent.ThreadFactory;
import com.swoval.functional.Either;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Provides an execution context to run tasks. Exists to allow source interoperability with scala.js
 */
public abstract class Executor implements AutoCloseable {
  protected final AtomicBoolean closed = new AtomicBoolean(false);

  Executor() {}

  /**
   * Runs the task on a thread.
   *
   * @param runnable task to run
   */
  public abstract void run(final Runnable runnable);

  /**
   * Returns a copy of the executor. The purpose of this is to provide the executor to a class or
   * method without allowing the class or method to close the underlying executor.
   *
   * @return An executor that
   */
  public Executor copy() {
    final Executor self = this;
    return new Executor() {
      @Override
      public void run(Runnable runnable) {
        self.run(runnable);
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
  public <T> Either<T, Exception> block(final Callable<T> callable) {
    final ArrayBlockingQueue<Either<T, Exception>> queue = new ArrayBlockingQueue<>(1);
    run(
        new Runnable() {
          @Override
          public void run() {
            Either<T, Exception> result;
            try {
              result = Either.left(callable.call());
            } catch (Exception e) {
              result = Either.right(e);
            }
            try {
              queue.add(result);
            } catch (Exception e) {
            }
          }
        });
    try {
      return queue.take();
    } catch (InterruptedException e) {
      return Either.right(e);
    }
  }

  /**
   * Blocks the current thread until the executor runs the provided Runnable.
   *
   * @param runnable The Runnable to invoke.
   * @return true if the Runnable succeeds, false otherwise.
   */
  public boolean block(final Runnable runnable) {
    final CountDownLatch latch = new CountDownLatch(1);
    boolean result;
    try {
      run(
          new Runnable() {
            @Override
            public void run() {
              runnable.run();
              latch.countDown();
            }
          });
      latch.await();
      result = true;
    } catch (InterruptedException e) {
      result = false;
    }
    return result;
  }

  /** Close the executor. All exceptions must be handled by the implementation. */
  @Override
  public void close() {}

  public boolean isClosed() {
    return closed.get();
  }

  /**
   * Make a new instance of an Executor
   *
   * @param name The name of the executor thread
   * @return Executor
   */
  public static Executor make(final String name) {
    return new Executor() {
      final ThreadFactory factory = new ThreadFactory(name);
      final ExecutorService service = Executors.newSingleThreadExecutor(factory);

      @SuppressWarnings("EmptyCatchBlock")
      @Override
      public void close() {
        if (closed.compareAndSet(false, true)) {
          super.close();
          service.shutdownNow();
          try {
            if (!service.awaitTermination(5, TimeUnit.SECONDS)) {
              System.err.println("Couldn't close executor");
            }
          } catch (InterruptedException e) {
          }
          factory.close();
        }
      }

      @Override
      public void run(final Runnable runnable) {
        if (factory.created(Thread.currentThread())) runnable.run();
        else service.submit(runnable);
      }
    };
  }
}
