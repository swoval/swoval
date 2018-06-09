package com.swoval.files.apple;

import com.swoval.concurrent.ThreadFactory;
import com.swoval.files.NativeLoader;
import java.io.IOException;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Provides access to apple file system events. The class is created with two callbacks, one to run
 * when a file event is created and the other to run when the underlying native implementation
 * removes a redundant stream from monitoring. This class is low level and users should generally
 * prefer {@link com.swoval.files.DirectoryWatcher} or {@link
 * com.swoval.files.AppleDirectoryWatcher} if the code is only ever run on OSX.
 *
 * @see <a href="https://developer.apple.com/documentation/coreservices/file_system_events"
 *     target="_blank"></a>
 */
public class FileEventsApi implements AutoCloseable {
  public class ClosedFileEventsApiException extends IOException {
    public ClosedFileEventsApiException(final String msg) {
      super(msg);
    }
  }

  /**
   * Represents an operation that takes an input and returns no result
   *
   * @param <T> The input type
   */
  public interface Consumer<T> {
    void accept(T t);
  }

  private long handle;
  private final ExecutorService executor =
      new ThreadPoolExecutor(
          2,
          10,
          5,
          TimeUnit.SECONDS,
          new LinkedBlockingDeque<Runnable>(8192),
          new ThreadFactory("com.swoval.files.apple.FileEventsApi.run-loop-thread"));

  private FileEventsApi(final Consumer<FileEvent> c, final Consumer<String> pc)
      throws InterruptedException {
    final CountDownLatch latch = new CountDownLatch(1);
    executor.submit(
        new Runnable() {
          @Override
          public void run() {
            final Consumer<FileEvent> eventConsumer = new Consumer<FileEvent>() {
              @Override
              public void accept(final FileEvent fileEvent) {
                executor.submit(new Runnable() {
                  @Override
                  public void run() {
                    c.accept(fileEvent);
                  }
                });
              }
            };
            final Consumer<String> streamConsumer = new Consumer<String>() {
              @Override
              public void accept(final String s) {
                executor.submit(new Runnable() {
                  @Override
                  public void run() {
                    pc.accept(s);
                  }
                });
              }
            };
            FileEventsApi.this.handle = FileEventsApi.init(eventConsumer, streamConsumer);
            latch.countDown();
            loop();
          }
        });
    latch.await();
  }

  private AtomicBoolean closed = new AtomicBoolean(false);

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      stopLoop(handle);
      executor.shutdownNow();
      try {
        executor.awaitTermination(5, TimeUnit.SECONDS);
        close(handle);
      } catch (InterruptedException e) {
      }
    }
  }

  public void loop() {
    loop(handle);
  }

  /**
   * Creates an event stream
   *
   * @param path The directory to monitor for events
   * @param latency The minimum time in seconds between events for the path
   * @param flags The flags for the stream @see {@link Flags.Create}
   * @return handle that can be used to stop the stream in the future
   */
  public int createStream(String path, double latency, int flags)
      throws ClosedFileEventsApiException {
    if (closed.get()) {
      String err = "Tried to create watch stream for path " + path + " on closed watch service";
      throw new ClosedFileEventsApiException(err);
    }
    return createStream(path, latency, flags, handle);
  }

  /**
   * Stop monitoring the path that was previously created with {@link #createStream}
   *
   * @param streamHandle handle returned by {@link #createStream}
   */
  public void stopStream(int streamHandle) {
    if (!closed.get()) {
      stopStream(handle, streamHandle);
    }
  }

  /**
   * Creates a new {@link FileEventsApi} instance
   *
   * @param consumer The callback to run on file events
   * @param pathConsumer The callback to run when a redundant stream is removed
   * @return {@link FileEventsApi}
   * @throws InterruptedException thrown when initialization of the native library is interrupted
   */
  public static FileEventsApi apply(Consumer<FileEvent> consumer, Consumer<String> pathConsumer)
      throws InterruptedException {
    return new FileEventsApi(consumer, pathConsumer);
  }

  private static native void loop(long handle);

  private static native void close(long handle);

  private static native long init(Consumer<FileEvent> consumer, Consumer<String> pathConsumer);

  private static native int createStream(String path, double latency, int flags, long handle);

  private static native void stopLoop(long handle);

  private static native void stopStream(long handle, int streamHandle);

  static {
    try {
      NativeLoader.loadPackaged();
    } catch (IOException | UnsatisfiedLinkError e) {
      System.err.println("Couldn't load native library " + e);
      throw new ExceptionInInitializerError(e);
    }
  }
}
