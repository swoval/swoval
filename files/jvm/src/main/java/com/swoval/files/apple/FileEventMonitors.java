package com.swoval.files.apple;

import com.swoval.concurrent.ThreadFactory;
import com.swoval.files.apple.FileEventMonitors.Handle;
import com.swoval.files.apple.FileEventMonitors.Handles;
import com.swoval.files.apple.Flags.Create;
import com.swoval.functional.Consumer;
import com.swoval.runtime.NativeLoader;
import com.swoval.runtime.ShutdownHooks;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class FileEventMonitors {
  private FileEventMonitors() {}

  public interface Handle {}

  public static class Handles {
    private Handles() {}
    public static final Handle INVALID =
        new Handle() {
          @Override
          public String toString() {
            return "INVALID";
          }
        };
  }

  public static FileEventMonitor get(
      final Consumer<FileEvent> eventConsumer, final Consumer<String> streamConsumer)
      throws InterruptedException {
    return new FileEventMonitorImpl(eventConsumer, streamConsumer);
  }
}

class FileEventMonitorImpl implements FileEventMonitor {
  private long handle = -1;
  private final Thread loopThread;
  private final ExecutorService callbackExecutor =
      Executors.newSingleThreadExecutor(
          new ThreadFactory("com.swoval.files.apple.FileEventsMonitor.callback"));
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final int shutdownHookId;
  private final Runnable closeRunnable = new Runnable() {
    @Override
    public void run() {
      if (closed.compareAndSet(false, true)) {
        ShutdownHooks.removeHook(shutdownHookId);
        stopLoop(handle);
        loopThread.interrupt();
        callbackExecutor.shutdownNow();
        try {
          loopThread.join(5000);
          callbackExecutor.awaitTermination(5, TimeUnit.SECONDS);
          close(handle);
        } catch (final InterruptedException e) {
          e.printStackTrace(System.err);
        }
      }
    }
  };

  FileEventMonitorImpl(
      final Consumer<FileEvent> eventConsumer, final Consumer<String> streamConsumer)
      throws InterruptedException {
    final CountDownLatch initLatch = new CountDownLatch(1);
    final Consumer<FileEvent> wrappedEventConsumer = new WrappedConsumer<>(eventConsumer);
    final Consumer<String> wrappedStreamConsumer = new WrappedConsumer<>(streamConsumer);
    loopThread =
        new Thread("com.swoval.files.apple.FileEventsMonitor.runloop") {
          @Override
          public void run() {
            handle = init(wrappedEventConsumer, wrappedStreamConsumer);
            initLatch.countDown();
            loop(handle);
          }
        };
    loopThread.start();
    initLatch.await(5, TimeUnit.SECONDS);
    assert (handle != -1);
    shutdownHookId = ShutdownHooks.addHook(1, closeRunnable);
  }

  private class NativeHandle implements Handle {
    private final int handle;

    NativeHandle(final int handle) {
      this.handle = handle;
    }

    @Override
    public String toString() {
      return "NativeHandle(" + handle + ")";
    }
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

  @Override
  public Handle createStream(final Path path, final long latency, TimeUnit timeUnit, Create flags)
      throws ClosedFileEventMonitorException {
    if (!closed.get()) {
      final int res =
          createStream(
              path.toString(), timeUnit.toNanos(latency) / 1.0e9, flags.getValue(), handle);
      return res == -1 ? Handles.INVALID : new NativeHandle(res);
    } else {
      throw new ClosedFileEventMonitorException();
    }
  }

  @Override
  public void stopStream(final Handle streamHandle) throws ClosedFileEventMonitorException {
    if (!closed.get()) {
      assert (streamHandle instanceof NativeHandle);
      stopStream(handle, ((NativeHandle) streamHandle).handle);
    } else {
      throw new ClosedFileEventMonitorException();
    }
  }

  @Override
  public void close() {
    closeRunnable.run();
  }

  private class WrappedConsumer<T> implements Consumer<T> {
    private final Consumer<T> consumer;

    WrappedConsumer(final Consumer<T> consumer) {
      this.consumer = consumer;
    }

    @Override
    public void accept(final T t) {
      callbackExecutor.submit(
          new Runnable() {
            @Override
            public void run() {
              try {
                consumer.accept(t);
              } catch (final Exception e) {
                e.printStackTrace(System.err);
              }
            }
          });
    }
  }
}
