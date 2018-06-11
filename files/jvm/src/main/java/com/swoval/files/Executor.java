package com.swoval.files;

import com.swoval.concurrent.ThreadFactory;
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
   * Runs the task on a thread
   *
   * @param runnable task to run
   */
  public abstract void run(final Runnable runnable);

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
      final ExecutorService service = Executors.newSingleThreadExecutor(new ThreadFactory(name));

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
        }
      }

      @Override
      public void run(final Runnable runnable) {
        service.submit(runnable);
      }
    };
  }
}
