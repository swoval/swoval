package com.swoval.files.apple;

import com.swoval.concurrent.ThreadFactory;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

  /**
   * Represents an operation that takes an input and returns no result
   * @param <T> The input type
   */
  public interface Consumer<T> {
    void accept(T t);
  }

  private long handle;
  private final ExecutorService executor =
      Executors.newSingleThreadExecutor(
          new ThreadFactory("com.swoval.files.apple.FileEventsApi.run-loop-thread"));

  private FileEventsApi(final Consumer<FileEvent> c, final Consumer<String> pc)
      throws InterruptedException {
    final CountDownLatch latch = new CountDownLatch(1);
    executor.submit(
        new Runnable() {
          @Override
          public void run() {
            FileEventsApi.this.handle = FileEventsApi.init(c, pc);
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
   * @param path The directory to monitor for events
   * @param latency The minimum time in seconds between events for the path
   * @param flags The flags for the stream @see {@link Flags.Create}
   * @return handle that can be used to stop the stream in the future
   */
  public int createStream(String path, double latency, int flags) {
    if (closed.get()) {
      String err = "Tried to create watch stream for path " + path + " on closed watch service";
      throw new IllegalStateException(err);
    }
    return createStream(path, latency, flags, handle);
  }

  /**
   * Stop monitoring the path that was previously created with {@link #createStream}
   * @param streamHandle handle returned by {@link #createStream}
   */
  public void stopStream(int streamHandle) {
    if (!closed.get()) {
      stopStream(handle, streamHandle);
    }
  }

  /**
   * Creates a new {@link FileEventsApi} instance
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
    NativeLoader.loadPackaged();
  }
}

class NativeLoader {
  private static final String NATIVE_LIBRARY = "apple-file-events0";

  private static void exit(String msg) {
    System.err.println(msg);
    System.exit(1);
  }

  static void loadPackaged() {
    try {
      String lib = System.mapLibraryName(NATIVE_LIBRARY);
      File temp = File.createTempFile("jni-", "");
      if (!temp.delete()) {
        throw new IOException("Couldn't remove temp file");
      }
      if (!temp.mkdir()) {
        throw new IOException("Couldn't remove temp file");
      }
      String resourcePath = "/native/x86_64-darwin/" + lib;
      InputStream resourceStream = FileEventsApi.class.getResourceAsStream(resourcePath);
      if (resourceStream == null) {
        String msg = "Native library " + lib + " (" + resourcePath + ") can't be loaded.";
        throw new UnsatisfiedLinkError(msg);
      }

      final File extractedPath = new File(temp.getAbsolutePath() + "/" + lib);
      OutputStream out = new FileOutputStream(extractedPath);
      try {
        byte[] buf = new byte[1024];
        int len = 0;
        while ((len = resourceStream.read(buf)) >= 0) {
          out.write(buf, 0, len);
        }
      } catch (Exception e) {
        throw new UnsatisfiedLinkError("Error while extracting native library: " + e);
      } finally {
        resourceStream.close();
        out.close();
      }

      Runtime.getRuntime()
          .addShutdownHook(
              new Thread() {
                public void run() {
                  try {
                    Files.delete(extractedPath.toPath());
                  } catch (IOException e) {
                    System.err.println("Error deleting temporary files: " + e);
                  }
                }
              });

      System.load(extractedPath.getAbsolutePath());
    } catch (Exception e) {
      exit("Couldn't load packaged library " + NATIVE_LIBRARY);
    }
  }
}
