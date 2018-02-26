package com.swoval.files.apple;

import com.swoval.concurrent.ThreadFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class FileEventsApi implements AutoCloseable {

  private long handle;
  private final ExecutorService executor = Executors.newSingleThreadExecutor(
      new ThreadFactory("com.swoval.files.apple.FileEventsApi.run-loop-thread"));

  private FileEventsApi(Consumer<FileEvent> c, Consumer<String> pc) throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);
    executor.submit(() -> {
      this.handle = FileEventsApi.init(c, pc);
      latch.countDown();
      loop();
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
      throw new IllegalStateException();
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
      Path tmp = Files.createTempDirectory("jni-");
      String resourcePath = "/native/x86_64-darwin/" + lib;
      InputStream resourceStream = FileEventsApi.class.getResourceAsStream(resourcePath);
      if (resourceStream == null) {
        String msg = "Native library " + lib + " (" + resourcePath + ") can't be loaded.";
        throw new UnsatisfiedLinkError(msg);
      }

      Path extractedPath = tmp.resolve(lib);
      try {
        Files.copy(resourceStream, extractedPath);
      } catch (Exception e) {
        throw new UnsatisfiedLinkError("Error while extracting native library: " + e);
      }

      System.load(extractedPath.toAbsolutePath().toString());
    } catch (Exception e) {
      exit("Couldn't load packaged library " + NATIVE_LIBRARY);
    }
  }

  static {
    try {
      System.loadLibrary(NATIVE_LIBRARY);
    } catch (UnsatisfiedLinkError e) {
      loadPackaged();
    }
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
