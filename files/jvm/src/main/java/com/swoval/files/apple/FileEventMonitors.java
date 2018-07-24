package com.swoval.files.apple;

import com.swoval.concurrent.ThreadFactory;
import com.swoval.files.apple.FileEventMonitors.Handle;
import com.swoval.files.apple.Flags.Create;
import com.swoval.files.apple.GlobalFileEventMonitor.Consumers;
import com.swoval.functional.Consumer;
import com.swoval.runtime.NativeLoader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class FileEventMonitors {
  private FileEventMonitors() {}

  private static final Object lock = new Object();
  private static AtomicReference<GlobalFileEventMonitor> global = new AtomicReference<>();

  public interface Handle {}

  public static FileEventMonitor get(
      final Consumer<FileEvent> eventConsumer, final Consumer<String> streamConsumer) throws InterruptedException {
    synchronized (lock) {
      GlobalFileEventMonitor monitor = global.get();
      if (monitor == null) {
        monitor = new GlobalFileEventMonitor(new Consumers<FileEvent>(), new Consumers<String>());
        global.set(monitor);
      }
      return monitor.getDelegate(eventConsumer, streamConsumer);
    }
  }

 static void clearGlobal(final GlobalFileEventMonitor globalFileEventMonitor) {
    if (global.compareAndSet(globalFileEventMonitor, null)) {
      globalFileEventMonitor.close();
    }
  }
}

class FileEventMonitorImpl implements FileEventMonitor {
  private long handle = -1;
  private final Thread loopThread;
  private final ExecutorService callbackExecutor =
      Executors.newSingleThreadExecutor(
          new ThreadFactory("com.swoval.files.apple.FileEventsMonitor.callback"));
  private final AtomicBoolean closed = new AtomicBoolean(false);

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
            super.run();
          }
        };
    initLatch.await(5, TimeUnit.SECONDS);
    assert (handle != -1);
  }

  private class NativeHandle implements Handle {
    private final int handle;

    NativeHandle(final int handle) {
      this.handle = handle;
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
  public Handle createStream(final Path path, final int latency, TimeUnit timeUnit, Create flags) {
    return new NativeHandle(
        createStream(path.toString(), timeUnit.toNanos(latency) / 1.0e9, flags.getValue(), handle));
  }

  @Override
  public void stopStream(final Handle streamHandle) {
    assert (streamHandle instanceof NativeHandle);
    stopStream(handle, ((NativeHandle) streamHandle).handle);
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
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
    callbackExecutor.shutdownNow();
    try {
      callbackExecutor.awaitTermination(5, TimeUnit.SECONDS);
    } catch (final InterruptedException e) {
      e.printStackTrace(System.err);
    }
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

class GlobalFileEventMonitor extends FileEventMonitorImpl {
  private final Consumers<FileEvent> eventConsumers;
  private final Consumers<String> streamConsumers;
  private static final AtomicInteger currentConsumerId = new AtomicInteger(0);

  GlobalFileEventMonitor(
      final Consumers<FileEvent> eventConsumers, final Consumers<String> streamConsumers)
      throws InterruptedException {
    super(eventConsumers, streamConsumers);
    this.eventConsumers = eventConsumers;
    this.streamConsumers = streamConsumers;
  }

  int addConsumers(final Consumer<FileEvent> eventConsumer, final Consumer<String> streamConsumer) {
    final int id = currentConsumerId.getAndIncrement();
    eventConsumers.addConsumer(id, eventConsumer);
    streamConsumers.addConsumer(id, streamConsumer);
    return id;
  }

  boolean removeConsumers(final int id) {
    return eventConsumers.removeConsumer(id) && streamConsumers.removeConsumer(id);
  }

  static final class Consumers<T> implements Consumer<T> {
    private final Map<Integer, Consumer<T>> consumers = new LinkedHashMap<>();

    @Override
    public void accept(final T t) {
      final Iterator<Consumer<T>> it = consumers.values().iterator();
      while (it.hasNext()) {
        try {
          it.next().accept(t);
        } catch (final Exception e) {
          e.printStackTrace();
        }
      }
    }

    void addConsumer(final int id, Consumer<T> consumer) {
      assert (consumers.put(id, consumer) == null);
    }

    boolean removeConsumer(final int id) {
      consumers.remove(id);
      return consumers.isEmpty();
    }

    boolean isEmpty() {
      return consumers.isEmpty();
    }
  }

  FileEventMonitor getDelegate(
      final Consumer<FileEvent> eventConsumer, final Consumer<String> streamConsumer) {
    return new DelegateFileEventMonitor(eventConsumer, streamConsumer);
  }

  private class DelegateFileEventMonitor implements FileEventMonitor {
    private final int consumerId;

    DelegateFileEventMonitor(
        final Consumer<FileEvent> eventConsumer,
        final Consumer<String> streamConsumer) {
      this.consumerId = addConsumers(eventConsumer, streamConsumer);
    }

    @Override
    public Handle createStream(Path path, int latency, TimeUnit timeUnit, Create flags) {
      return GlobalFileEventMonitor.this.createStream(path, latency, timeUnit, flags);
    }

    @Override
    public void stopStream(Handle streamHandle) {
      GlobalFileEventMonitor.this.stopStream(streamHandle);
    }

    @Override
    public void close() {
      if (GlobalFileEventMonitor.this.removeConsumers(consumerId)) {
        FileEventMonitors.clearGlobal(GlobalFileEventMonitor.this);
      }
    }
  }
}
