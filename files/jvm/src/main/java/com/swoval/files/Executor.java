package com.swoval.files;

import com.swoval.concurrent.ThreadFactory;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Provides an execution context to run tasks. Exists to allow source interoperability with scala.js
 */
public abstract class Executor implements AutoCloseable {
  private Executor() {}

  /**
   * Runs the task on a thread
   *
   * @param runnable task to run
   */
  public abstract void run(final Runnable runnable);

  @Override
  public void close() {}

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
        super.close();
        service.shutdownNow();
        try {
          service.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
        }
      }

      @Override
      public void run(final Runnable runnable) {
        service.submit(runnable);
      }
    };
  }
}
