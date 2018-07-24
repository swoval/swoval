package com.swoval.files;

import static com.swoval.files.PathWatchers.Event.Kind.Create;
import static com.swoval.files.PathWatchers.Event.Kind.Delete;
import static com.swoval.files.PathWatchers.Event.Kind.Modify;
import static com.swoval.functional.Either.leftProjection;

import com.swoval.files.Executor.Thread;
import com.swoval.files.FileTreeViews.Observer;
import com.swoval.files.PathWatchers.Event;
import com.swoval.files.apple.ClosedFileEventMonitorException;
import com.swoval.files.apple.FileEvent;
import com.swoval.files.apple.FileEventMonitor;
import com.swoval.files.apple.FileEventMonitors;
import com.swoval.files.apple.FileEventMonitors.Handle;
import com.swoval.files.apple.FileEventMonitors.Handles;
import com.swoval.files.apple.Flags;
import com.swoval.functional.Consumer;
import com.swoval.functional.Either;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Implements the PathWatcher for Mac OSX using the <a
 * href="https://developer.apple.com/library/content/documentation/Darwin/Conceptual/FSEvents_ProgGuide/UsingtheFSEventsFramework/UsingtheFSEventsFramework.html"
 * target="_blank">Apple File System Events Api</a>.
 */
public class ApplePathWatcher implements PathWatcher<PathWatchers.Event> {
  private final DirectoryRegistry directoryRegistry;
  private final Map<Path, Stream> streams = new HashMap<>();
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final long latency;
  private final TimeUnit timeUnit;
  private final Executor internalExecutor;
  private final Flags.Create flags;
  private final FileEventMonitor fileEventMonitor;
  private static final DefaultOnStreamRemoved DefaultOnStreamRemoved = new DefaultOnStreamRemoved();

  @Override
  public int addObserver(Observer<Event> observer) {
    return 0;
  }

  @Override
  public void removeObserver(int handle) {}

  private static class Stream {
    public final Handle handle;

    Stream(final Handle handle) {
      this.handle = handle;
    }
  }

  /**
   * Registers a path
   *
   * @param path The directory to watch for file events
   * @param maxDepth The maximum number of subdirectory levels to visit
   * @return an {@link com.swoval.functional.Either} containing the result of the registration or an
   *     IOException if registration fails. This method should be idempotent and return true the
   *     first time the directory is registered or when the depth is changed. Otherwise it should
   *     return false.
   */
  @Override
  public Either<IOException, Boolean> register(final Path path, final int maxDepth) {
    return register(path, flags, maxDepth);
  }

  /**
   * Registers with additional flags
   *
   * @param path The directory to watch for file events
   * @param flags The flags {@link com.swoval.files.apple.Flags.Create} to set for the directory
   * @param maxDepth The maximum number of subdirectory levels to visit
   * @return an {@link com.swoval.functional.Either} containing the result of the registration or an
   *     IOException if registration fails. This method should be idempotent and return true the
   *     first time the directory is registered or when the depth is changed. Otherwise it should
   *     return false.
   */
  public Either<IOException, Boolean> register(
      final Path path, final Flags.Create flags, final int maxDepth) {
    final Either<Exception, Boolean> either =
        internalExecutor.block(
            new TotalFunction<Executor.Thread, Boolean>() {
              @Override
              public Boolean apply(final Executor.Thread thread) {
                return registerImpl(path, flags, maxDepth);
              }
            });
    if (either.isLeft() && !(leftProjection(either).getValue() instanceof IOException)) {
      throw new RuntimeException(leftProjection(either).getValue());
    }
    return either.castLeft(IOException.class, false);
  }

  private boolean registerImpl(final Path path, final Flags.Create flags, final int maxDepth) {
    boolean result = true;
    final Entry<Path, Stream> entry = find(path);
    directoryRegistry.addDirectory(path, maxDepth);
    if (entry == null) {
      try {
        FileEventMonitors.Handle id = fileEventMonitor.createStream(path, latency, timeUnit, flags);
        if (id == Handles.INVALID) {
          result = false;
        } else {
          removeRedundantStreams(path);
          streams.put(path, new Stream(id));
        }
      } catch (final ClosedFileEventMonitorException e) {
        close();
        result = false;
      }
    }
    return result;
  }

  private void removeRedundantStreams(final Path path) {
    final List<Path> toRemove = new ArrayList<>();
    final Iterator<Entry<Path, Stream>> it = streams.entrySet().iterator();
    while (it.hasNext()) {
      final Entry<Path, Stream> e = it.next();
      final Path key = e.getKey();
      if (key.startsWith(path) && !key.equals(path)) {
        toRemove.add(key);
      }
    }
    final Iterator<Path> pathIterator = toRemove.iterator();
    while (pathIterator.hasNext()) {
      unregisterImpl(pathIterator.next());
    }
  }

  private void unregisterImpl(final Path path) {
    if (!closed.get()) {
      directoryRegistry.removeDirectory(path);
      final Stream stream = streams.remove(path);
      if (stream != null && stream.handle != Handles.INVALID) {
        try {
          fileEventMonitor.stopStream(stream.handle);
        } catch (final ClosedFileEventMonitorException e) {
          e.printStackTrace(System.err);
        }
      }
    }
  }

  /**
   * Unregisters a path
   *
   * @param path The directory to remove from monitoring
   */
  @Override
  public void unregister(final Path path) {
    internalExecutor.block(
        new Consumer<Executor.Thread>() {
          @Override
          public void accept(final Executor.Thread thread) {
            unregisterImpl(path);
          }
        });
  }

  /** Closes the FileEventsApi and shuts down the {@code internalExecutor}. */
  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      internalExecutor.block(
          new Consumer<Executor.Thread>() {
            @Override
            public void accept(final Executor.Thread thread) {
              streams.clear();
              fileEventMonitor.close();
            }
          });
      internalExecutor.close();
    }
  }

  /** A no-op callback to invoke when streams are removed. */
  static class DefaultOnStreamRemoved implements BiConsumer<String, Executor.Thread> {
    DefaultOnStreamRemoved() {}

    @Override
    public void accept(final String stream, final Executor.Thread thread) {}
  }

  ApplePathWatcher(
      final BiConsumer<Event, Executor.Thread> onFileEvent,
      final Executor executor,
      final DirectoryRegistry directoryRegistry)
      throws InterruptedException {
    this(
        10,
        TimeUnit.MILLISECONDS,
        new Flags.Create().setNoDefer().setFileEvents(),
        onFileEvent,
        DefaultOnStreamRemoved,
        executor,
        directoryRegistry);
  }
  /**
   * Creates a new ApplePathWatcher which is a wrapper around {@link FileEventMonitor}, which in
   * turn is a native wrapper around <a
   * href="https://developer.apple.com/library/content/documentation/Darwin/Conceptual/FSEvents_ProgGuide/Introduction/Introduction.html#//apple_ref/doc/uid/TP40005289-CH1-SW1">
   * Apple File System Events</a>
   *
   * @param latency specified in fractional seconds
   * @param flags Native flags
   * @param onFileEvent {@link com.swoval.functional.Consumer} to run on file events
   * @param onStreamRemoved {@link com.swoval.functional.Consumer} to run when a redundant stream is
   *     removed from the underlying native file events implementation
   * @param executor The internal executor to manage the directory watcher state
   * @param managedDirectoryRegistry The nullable registry of directories to monitor. If this is
   *     non-null, then registrations are handled by an outer class and this watcher should not call
   *     add or remove directory.
   * @throws InterruptedException if the native file events implementation is interrupted during
   *     initialization
   */
  ApplePathWatcher(
      final long latency,
      final TimeUnit timeUnit,
      final Flags.Create flags,
      final BiConsumer<Event, Executor.Thread> onFileEvent,
      final BiConsumer<String, Executor.Thread> onStreamRemoved,
      final Executor executor,
      final DirectoryRegistry managedDirectoryRegistry)
      throws InterruptedException {
    this.latency = latency;
    this.timeUnit = timeUnit;
    this.flags = flags;
    this.internalExecutor =
        executor == null
            ? Executor.make("com.swoval.files.ApplePathWatcher-internalExecutor")
            : executor;
    this.directoryRegistry =
        managedDirectoryRegistry == null ? new DirectoryRegistryImpl() : managedDirectoryRegistry;
    fileEventMonitor =
        FileEventMonitors.get(
            new Consumer<FileEvent>() {
              @Override
              public void accept(final FileEvent fileEvent) {
                internalExecutor.run(
                    new Consumer<Executor.Thread>() {
                      @Override
                      public void accept(final Executor.Thread thread) {
                        final String fileName = fileEvent.fileName;
                        final TypedPath path = TypedPaths.get(Paths.get(fileName));
                        if (directoryRegistry.accept(path.getPath())) {
                          Event event;
                          if (fileEvent.itemIsFile()) {
                            if (fileEvent.isNewFile() && path.exists()) {
                              event = new Event(path, Create);
                            } else if (fileEvent.isRemoved() || !path.exists()) {
                              event = new Event(path, Delete);
                            } else {
                              event = new Event(path, Modify);
                            }
                          } else if (path.exists()) {
                            event = new Event(path, Modify);
                          } else {
                            event = new Event(path, Delete);
                          }
                          onFileEvent.accept(event, thread);
                        }
                      }
                    });
              }
            },
            new Consumer<String>() {
              @Override
              public void accept(final String stream) {
                internalExecutor.block(
                    new Consumer<Executor.Thread>() {
                      @Override
                      public void accept(final Executor.Thread thread) {
                        new Runnable() {
                          @Override
                          public void run() {
                            streams.remove(Paths.get(stream));
                            onStreamRemoved.accept(stream, thread);
                          }
                        }.run();
                      }
                    });
              }
            });
  }

  private Entry<Path, Stream> find(final Path path) {
    final Iterator<Entry<Path, Stream>> it = streams.entrySet().iterator();
    Entry<Path, Stream> result = null;
    while (result == null && it.hasNext()) {
      final Entry<Path, Stream> entry = it.next();
      if (path.startsWith(entry.getKey())) {
        result = entry;
      }
    }
    return result;
  }
}

class ApplePathWatchers {
  private ApplePathWatchers() {}

  private static final GlobalApplePathWatcher GLOBAL_APPLE_PATH_WATCHER;

  static {
    try {
      GLOBAL_APPLE_PATH_WATCHER = new GlobalApplePathWatcher();
    } catch (final InterruptedException e) {
      throw new ExceptionInInitializerError();
    }
  }

  public static PathWatcher<PathWatchers.Event> get(
      final BiConsumer<PathWatchers.Event, Executor.Thread> biConsumer,
      final Executor executor,
      final DirectoryRegistry directoryRegistry)
      throws InterruptedException {
    return GLOBAL_APPLE_PATH_WATCHER.newDelegate(biConsumer, executor, directoryRegistry);
  }
}

class GlobalApplePathWatcher implements AutoCloseable {
  private final DelegatePathWatchers pathWatchers = new DelegatePathWatchers();
  private final BiConsumer<PathWatchers.Event, Executor.Thread> biConsumer =
      new BiConsumer<Event, Executor.Thread>() {
        @Override
        public void accept(final Event event, final Executor.Thread thread) {
          final Iterator<DelegatePathWatcher> it = pathWatchers.iterator();
          while (it.hasNext()) {
            final DelegatePathWatcher watcher = it.next();
            if (watcher.directoryRegistry.accept(event.getPath())) {
              watcher.biConsumer.accept(event, thread);
            }
          }
        }
      };
  private final DirectoryRegistry registry =
      new DirectoryRegistry() {
        /*
         * No-op. This is managed by the underlying directory registries.
         */
        @Override
        public boolean addDirectory(final Path path, final int maxDepth) {
          return false;
        }

        @Override
        public int maxDepthFor(final Path path) {
          final Iterator<DelegatePathWatcher> it = pathWatchers.iterator();
          int result = Integer.MIN_VALUE;
          while (result < Integer.MAX_VALUE && it.hasNext()) {
            int maxDepth = it.next().directoryRegistry.maxDepthFor(path);
            if (maxDepth > result) result = maxDepth;
          }
          return result;
        }

        @Override
        public List<Path> registered() {
          final List<Path> result = new ArrayList<>();
          final Iterator<DelegatePathWatcher> it = pathWatchers.iterator();
          while (it.hasNext()) {
            result.addAll(it.next().directoryRegistry.registered());
          }
          return result;
        }

        /*
         * No-op. This is managed by the underlying directory registries.
         */
        @Override
        public void removeDirectory(final Path path) {}

        @Override
        public boolean acceptPrefix(final Path path) {
          boolean result = false;
          final Iterator<DelegatePathWatcher> it = pathWatchers.iterator();
          while (!result && it.hasNext()) {
            result = it.next().directoryRegistry.acceptPrefix(path);
          }
          return result;
        }

        @Override
        public boolean accept(final Path path) {
          boolean result = false;
          final Iterator<DelegatePathWatcher> it = pathWatchers.iterator();
          while (!result && it.hasNext()) {
            result = it.next().directoryRegistry.accept(path);
          }
          return result;
        }
      };
  private ApplePathWatcher pathWatcher;
  private final AtomicBoolean closed = new AtomicBoolean(true);
  private final Object lock = new Object();

  GlobalApplePathWatcher() throws InterruptedException {}

  private void maybeInit() throws InterruptedException {
    if (closed.getAndSet(false)) {
      pathWatcher =
          new ApplePathWatcher(
              biConsumer,
              Executor.make("com.swoval.files.ApplePathWatcher.global.executor"),
              registry);
    }
  }

  PathWatcher<PathWatchers.Event> newDelegate(
      final BiConsumer<PathWatchers.Event, Executor.Thread> biConsumer,
      final Executor executor,
      final DirectoryRegistry registry)
      throws InterruptedException {
    synchronized (lock) {
      maybeInit();
      final int id = pathWatchers.watcherID.incrementAndGet();
      final DelegatePathWatcher watcher =
          new DelegatePathWatcher(id, biConsumer, executor, registry);
      pathWatchers.addPathWatcher(id, watcher);
      return watcher;
    }
  }

  @Override
  public void close() {
    synchronized (lock) {
      if (pathWatchers.isEmpty()) {
        if (closed.compareAndSet(false, true)) {
          pathWatcher.close();
          pathWatcher = null;
        }
      }
    }
  }

  private PathWatcher<PathWatchers.Event> getPathWatcher() {
    synchronized (lock) {
      return pathWatcher;
    }
  }

  private class DelegatePathWatcher implements PathWatcher<PathWatchers.Event> {
    private final DirectoryRegistry directoryRegistry;
    private final Executor executor;
    private final BiConsumer<PathWatchers.Event, Executor.Thread> biConsumer;
    private final int id;

    DelegatePathWatcher(
        final int id,
        final BiConsumer<PathWatchers.Event, Executor.Thread> biConsumer,
        final Executor executor,
        final DirectoryRegistry directoryRegistry) {
      this.id = id;
      this.biConsumer = biConsumer;
      this.executor = executor;
      this.directoryRegistry = directoryRegistry;
    }

    @Override
    public Either<IOException, Boolean> register(final Path path, final int maxDepth) {
      return executor
              .block(
                  new Function<Executor.Thread, Boolean>() {
                    @Override
                    public Boolean apply(final Executor.Thread thread) {
                      directoryRegistry.addDirectory(path, maxDepth);
                      return Either.getOrElse(getPathWatcher().register(path, maxDepth), false);
                    }
                  })
              .castLeft(IOException.class, false);
    }

    @Override
    public void unregister(final Path path) {
      executor.block(new Consumer<Executor.Thread>() {
                       @Override
                       public void accept(Executor.Thread thread) {
                         directoryRegistry.removeDirectory(path);
                         pathWatcher.unregister(path);
                       }
                     });
    }

    @Override
    public void close() {
      executor.block(new Consumer<Thread>() {
        @Override
        public void accept(Thread thread) {
          if (pathWatchers.removePathWatcher(id)) {
            GlobalApplePathWatcher.this.close();
          }
        }
      });
      executor.close();
    }

    @Override
    public int addObserver(final Observer<Event> observer) {
      return Either.getOrElse(executor.block(new Function<Executor.Thread, Integer>() {
        @Override
        public Integer apply(final Executor.Thread thread) {
          return getPathWatcher().addObserver(observer);
        }
      }), -1);
    }

    @Override
    public void removeObserver(final int handle) {
      executor.block(new Consumer<Executor.Thread>() {
                       @Override
                       public void accept(Executor.Thread thread) {
                         getPathWatcher().removeObserver(handle);
                       }
                     });
    }
  }

  private static class DelegatePathWatchers {
    private static final Map<Integer, DelegatePathWatcher> pathWatchers = new LinkedHashMap<>();
    private final Object lock = new Object();
    private final AtomicInteger watcherID = new AtomicInteger(1);

    void addPathWatcher(final int id, final DelegatePathWatcher pathWatcher) {
      synchronized (lock) {
        pathWatchers.put(id, pathWatcher);
      }
    }

    boolean isEmpty() {
      synchronized (lock) {
        return pathWatchers.isEmpty();
      }
    }

    boolean removePathWatcher(final int id) {
      synchronized (lock) {
        return (pathWatchers.remove(id) != null) && pathWatchers.isEmpty();
      }
    }

    private Iterator<DelegatePathWatcher> iterator() {
      synchronized (lock) {
        return new ArrayList<>(pathWatchers.values()).iterator();
      }
    }
  }
}
