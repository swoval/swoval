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

public class FileEventsApi implements AutoCloseable {

  public interface Consumer<T> {
    void accept(T t);
  }

  private long handle;
  private final ExecutorService executor = Executors.newSingleThreadExecutor(
      new ThreadFactory("com.swoval.files.apple.FileEventsApi.run-loop-thread"));

  private FileEventsApi(final Consumer<FileEvent> c, final Consumer<String> pc)
      throws InterruptedException {
    final CountDownLatch latch = new CountDownLatch(1);
    executor.submit(new Runnable() {
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
      stopLoop();
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

  public int createStream(String path, double latency, int flags) {
    if (closed.get()) {
      String err = "Tried to create watch stream for path " + path + " on closed watch service";
      throw new IllegalStateException(err);
    }
    return createStream(path, latency, flags, handle);
  }

  public void stopLoop() {
    stopLoop(handle);
  }

  public void stopStream(int streamHandle) {
    if (!closed.get()) {
      stopStream(handle, streamHandle);
    }
  }

  private static final String NATIVE_LIBRARY = "apple-file-events0";

  private static final void exit(String msg) {
    System.err.println(msg);
    System.exit(1);
  }

  private static void loadPackaged() {
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

      Runtime.getRuntime().addShutdownHook(new Thread() {
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

  static {
    loadPackaged();
  }

  public static FileEventsApi apply(Consumer<FileEvent> consumer, Consumer<String> pathConsumer)
      throws InterruptedException {
    return new FileEventsApi(consumer, pathConsumer);
  }

  public static native void loop(long handle);

  public static native void close(long handle);

  public static native long init(Consumer<FileEvent> consumer, Consumer<String> pathConsumer);

  public static native int createStream(String path, double latency, int flags, long handle);

  public static native void stopLoop(long handle);

  public static native void stopStream(long handle, int streamHandle);

}
