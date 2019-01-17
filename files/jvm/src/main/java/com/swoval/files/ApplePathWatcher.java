package com.swoval.files;

import static com.swoval.files.PathWatchers.Event.Kind.Create;
import static com.swoval.files.PathWatchers.Event.Kind.Delete;
import static com.swoval.files.PathWatchers.Event.Kind.Modify;

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
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

class AppleFileEventStreams extends LockableMap<Path, ApplePathWatcher.Stream> {}
/**
 * Implements the PathWatcher for Mac OSX using the <a
 * href="https://developer.apple.com/library/content/documentation/Darwin/Conceptual/FSEvents_ProgGuide/UsingtheFSEventsFramework/UsingtheFSEventsFramework.html"
 * target="_blank">Apple File System Events Api</a>.
 */
class ApplePathWatcher implements PathWatcher<PathWatchers.Event> {
  static class Stream implements AutoCloseable {
    final Handle handle;
    final FileEventMonitor fileEventMonitor;

    Stream(final FileEventMonitor fileEventMonitor, final Handle handle) {
      this.fileEventMonitor = fileEventMonitor;
      this.handle = handle;
    }

    @Override
    public void close() throws ClosedFileEventMonitorException {
      if (!handle.equals(Handles.INVALID)) {
        fileEventMonitor.stopStream(handle);
      }
    }
  }

  private final DirectoryRegistry directoryRegistry;
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final long latency;
  private final AppleFileEventStreams appleFileEventStreams = new AppleFileEventStreams();
  private final TimeUnit timeUnit;
  private final Flags.Create flags;
  private final FileEventMonitor fileEventMonitor;
  private final Observers<PathWatchers.Event> observers = new Observers<>();
  private static final DefaultOnStreamRemoved DefaultOnStreamRemoved = new DefaultOnStreamRemoved();

  @Override
  public int addObserver(final Observer<? super Event> observer) {
    return observers.addObserver(observer);
  }

  @Override
  public void removeObserver(int handle) {
    observers.removeObserver(handle);
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
    final Path absolutePath = path.isAbsolute() ? path : path.toAbsolutePath();
    return register(absolutePath, flags, maxDepth);
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
          appleFileEventStreams.put(path, new Stream(fileEventMonitor, id));
        }
      } catch (final ClosedFileEventMonitorException e) {
        close();
        result = false;
      }
    }
    return Either.right(result);
  }

  private void removeRedundantStreams(final Path path) {
    final List<Path> toRemove = new ArrayList<>();
    if (appleFileEventStreams.lock()) {
      try {
        final Iterator<Entry<Path, Stream>> it = appleFileEventStreams.iterator();
        while (it.hasNext()) {
          final Entry<Path, Stream> e = it.next();
          final Path key = e.getKey();
          if (key.startsWith(path) && !key.equals(path)) {
            toRemove.add(key);
          }
        }
        final Iterator<Path> pathIterator = toRemove.iterator();
        while (pathIterator.hasNext()) {
          unregister(pathIterator.next());
        }
      } finally {
        appleFileEventStreams.unlock();
      }
    }
  }

  /**
   * Unregisters a path
   *
   * @param path The directory to remove from monitoring
   */
  @Override
  @SuppressWarnings("EmptyCatchBlock")
  public void unregister(final Path path) {
    final Path absolutePath = path.isAbsolute() ? path : path.toAbsolutePath();
    if (!closed.get()) {
      directoryRegistry.removeDirectory(absolutePath);
      final Stream stream = appleFileEventStreams.remove(absolutePath);
      if (stream != null && stream.handle != Handles.INVALID) {
        try {
          stream.close();
        } catch (final ClosedFileEventMonitorException e) {
          e.printStackTrace(System.err);
        }
      }
    }
  }

  /** Stops all appleFileEventStreams and closes the FileEventsApi */
  @Override
  @SuppressWarnings("EmptyCatchBlock")
  public void close() {
    if (closed.compareAndSet(false, true)) {
      appleFileEventStreams.clear();
      fileEventMonitor.close();
    }
  }

  /** A no-op callback to invoke when appleFileEventStreams are removed. */
  static class DefaultOnStreamRemoved implements Consumer<String> {
    DefaultOnStreamRemoved() {}

    @Override
    public void accept(final String stream) {}
  }

  ApplePathWatcher(final DirectoryRegistry directoryRegistry) throws InterruptedException {
    this(
        10,
        TimeUnit.MILLISECONDS,
        new Flags.Create().setNoDefer().setFileEvents(),
        DefaultOnStreamRemoved,
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
   * @param onStreamRemoved {@link com.swoval.functional.Consumer} to run when a redundant stream is
   *     removed from the underlying native file events implementation
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
      final Consumer<String> onStreamRemoved,
      final DirectoryRegistry managedDirectoryRegistry)
      throws InterruptedException {
    this.latency = latency;
    this.timeUnit = timeUnit;
    this.flags = flags;
    this.directoryRegistry =
        managedDirectoryRegistry == null ? new DirectoryRegistryImpl() : managedDirectoryRegistry;
    fileEventMonitor =
        FileEventMonitors.get(
            new Consumer<FileEvent>() {
              @Override
              public void accept(final FileEvent fileEvent) {
                if (!closed.get()) {
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
                    try {
                      observers.onNext(event);
                    } catch (final Exception e) {
                      observers.onError(e);
                    }
                  }
                }
              }
            },
            new Consumer<String>() {
              @Override
              @SuppressWarnings("EmptyCatchBlock")
              public void accept(final String stream) {
                if (!closed.get()) {
                  appleFileEventStreams.remove(Paths.get(stream));
                  onStreamRemoved.accept(stream);
                }
              }
            });
  }

  private Entry<Path, Stream> find(final Path path) {
    final Iterator<Entry<Path, Stream>> it = appleFileEventStreams.iterator();
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

  public static PathWatcher<PathWatchers.Event> get(
      final boolean followLinks, final DirectoryRegistry directoryRegistry)
      throws InterruptedException, IOException {
    final ApplePathWatcher pathWatcher = new ApplePathWatcher(directoryRegistry);
    return followLinks
        ? new SymlinkFollowingPathWatcher(pathWatcher, directoryRegistry)
        : pathWatcher;
  }
}
