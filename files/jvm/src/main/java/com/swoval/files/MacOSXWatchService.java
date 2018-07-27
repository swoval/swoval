package com.swoval.files;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import com.swoval.files.apple.ClosedFileEventMonitorException;
import com.swoval.files.apple.FileEvent;
import com.swoval.files.apple.FileEventMonitor;
import com.swoval.files.apple.FileEventMonitors;
import com.swoval.files.apple.FileEventMonitors.Handle;
import com.swoval.files.apple.FileEventMonitors.Handles;
import com.swoval.files.apple.Flags.Create;
import com.swoval.functional.Consumer;
import com.swoval.functional.Either;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.WatchEvent;
import java.nio.file.WatchEvent.Kind;
import java.nio.file.Watchable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Provides an alternative {@link java.nio.file.WatchService} for mac that uses native file system
 * events rather than polling for file changes.
 */
class MacOSXWatchService implements RegisterableWatchService {
  private final int watchLatency;
  private final TimeUnit watchTimeUnit;
  private final int queueSize;
  private final AtomicBoolean open = new AtomicBoolean(true);
  private final Map<Path, WatchKey> registered = new HashMap<>();
  private final LinkedBlockingQueue<MacOSXWatchKey> readyKeys = new LinkedBlockingQueue<>();
  private Executor internalExecutor;

  private final Consumer<String> dropEvent =
      new Consumer<String>() {
        @Override
        public void accept(final String s) {
          internalExecutor.run(
              new Consumer<Executor.Thread>() {
                @Override
                public void accept(Executor.Thread thread) {
                  final Path path = Paths.get(s);
                  final WatchKey watchKey = registered.get(path);
                  if (watchKey != null) {
                    watchKey.handle = Handles.INVALID;
                  }
                }
              });
        }
      };
  private final Consumer<FileEvent> onFileEvent =
      new Consumer<FileEvent>() {
        @Override
        public void accept(final FileEvent fileEvent) {
          internalExecutor.run(
              new Consumer<Executor.Thread>() {
                @Override
                public void accept(final Executor.Thread thread) {
                  final Path path = Paths.get(fileEvent.fileName);
                  final WatchKey childKeys = registered.get(path);
                  final WatchKey watchKey =
                      childKeys == null ? registered.get(path.getParent()) : childKeys;
                  final boolean exists = Files.exists(path, LinkOption.NOFOLLOW_LINKS);
                  if (watchKey != null) {
                    if (fileEvent.mustScanSubDirs()) {
                      final Iterator<MacOSXWatchKey> it = watchKey.keys.iterator();
                      while (it.hasNext()) {
                        final MacOSXWatchKey key = it.next();
                        key.addOverflow();
                      }
                    } else {
                      final Iterator<MacOSXWatchKey> it = watchKey.keys.iterator();
                      while (it.hasNext()) {
                        final MacOSXWatchKey key = it.next();
                        if (exists && key.reportModifyEvents()) key.createEvent(ENTRY_MODIFY, path);
                        else if (!exists && key.reportDeleteEvents())
                          key.createEvent(ENTRY_DELETE, path);
                      }
                    }
                  }
                }
              });
        }
      };
  private FileEventMonitor fileEventMonitor;

  /**
   * Creates a new MacOSXWatchService.
   *
   * @param watchLatency the minimum latency between watch events specified in units of <code>
   *     timeUnit</code>
   * @param timeUnit the time unit the latency is specified with
   * @param queueSize the maximum number of events to queue per watch key
   * @throws InterruptedException if the native file events api initialization is interrupted.
   */
  public MacOSXWatchService(final int watchLatency, final TimeUnit timeUnit, final int queueSize)
      throws InterruptedException {
    this.watchLatency = watchLatency;
    this.queueSize = queueSize;
    this.watchTimeUnit = timeUnit;
    this.fileEventMonitor = FileEventMonitors.get(onFileEvent, dropEvent);
    this.internalExecutor = Executor.make("com.swoval.files.MacOSXWatchService.executor.internal");
  }

  /**
   * Create a new MacOSXWatchService with a minimum latency of 10 milliseconds and a maximum queue
   * size of <code>1024</code> per watch key.
   *
   * @throws InterruptedException if the native file events api initialization is interrupted.
   */
  public MacOSXWatchService() throws InterruptedException {
    // The FsEvents api doesn't seem to report events at lower than 10 millisecond intervals.
    this(10, TimeUnit.MILLISECONDS, 1024);
  }

  @Override
  @SuppressWarnings("EmptyCatchBlock")
  public void close() {
    if (Either.getOrElse(
        internalExecutor.block(
            new Function<Executor.Thread, Boolean>() {
              @Override
              public Boolean apply(final Executor.Thread thread) {
                if (open.compareAndSet(true, false)) {
                  final Iterator<WatchKey> it = new ArrayList<>(registered.values()).iterator();
                  while (it.hasNext()) {
                    final WatchKey watchKey = it.next();
                    if (watchKey.handle != Handles.INVALID) {
                      try {
                        fileEventMonitor.stopStream(watchKey.handle);
                      } catch (final ClosedFileEventMonitorException e) {
                        e.printStackTrace(System.err);
                      }
                    }
                    final Iterator<MacOSXWatchKey> keys = watchKey.keys.iterator();
                    while (keys.hasNext()) {
                      keys.next().cancel();
                    }
                    watchKey.keys.clear();
                  }
                  registered.clear();
                  fileEventMonitor.close();
                  return true;
                } else {
                  return false;
                }
              }
            }),
        false)) {
      internalExecutor.close();
    }
  }

  @Override
  public java.nio.file.WatchKey poll() {
    if (isOpen()) {
      return readyKeys.poll();
    } else {
      throw new ClosedWatchServiceException();
    }
  }

  @Override
  public java.nio.file.WatchKey poll(long timeout, TimeUnit unit) throws InterruptedException {
    if (isOpen()) {
      return readyKeys.poll(timeout, unit);
    } else {
      throw new ClosedWatchServiceException();
    }
  }

  @Override
  public java.nio.file.WatchKey take() throws InterruptedException {
    if (isOpen()) {
      return readyKeys.take();
    } else {
      throw new ClosedWatchServiceException();
    }
  }

  private boolean isOpen() {
    return open.get();
  }

  @Override
  public java.nio.file.WatchKey register(final Path path, final Kind<?>... kinds)
      throws IOException {
    Either<Exception, java.nio.file.WatchKey> result =
        internalExecutor.block(
            new Function<Executor.Thread, java.nio.file.WatchKey>() {
              @Override
              public java.nio.file.WatchKey apply(final Executor.Thread thread) throws IOException {
                if (isOpen()) {
                  final Path realPath = path.toRealPath();
                  if (!Files.isDirectory(realPath))
                    throw new NotDirectoryException(realPath.toString());
                  final WatchKey watchKey = registered.get(realPath);
                  final MacOSXWatchKey result;
                  if (watchKey == null) {
                    final Create flags = new Create().setNoDefer().setFileEvents();
                    Handle handle = null;
                    final Iterator<Path> it = registered.keySet().iterator();
                    while (it.hasNext() && handle != Handles.INVALID) {
                      if (realPath.startsWith(it.next())) {
                        handle = Handles.INVALID;
                      }
                    }
                    if (handle != Handles.INVALID) {
                      try {
                        handle =
                            fileEventMonitor.createStream(
                                realPath, watchLatency, watchTimeUnit, flags);
                      } catch (final ClosedFileEventMonitorException e) {
                        MacOSXWatchService.this.close();
                        throw e;
                      }
                    }
                    result = new MacOSXWatchKey(realPath, queueSize, handle, kinds);
                    registered.put(realPath, new WatchKey(handle, result));
                  } else {
                    result = new MacOSXWatchKey(realPath, queueSize, Handles.INVALID, kinds);
                    watchKey.keys.add(result);
                  }
                  return result;
                } else {
                  throw new ClosedWatchServiceException();
                }
              }
            });
    if (result.isRight()) {
      return result.get();
    } else {
      final Exception e = Either.leftProjection(result).getValue();
      if (e instanceof IOException) {
        throw (IOException) e;
      } else {
        e.printStackTrace();
        return null;
      }
    }
  }

  private static class Event<T> implements WatchEvent<T> {
    private final WatchEvent.Kind<T> _kind;
    private final int _count;
    private final T _context;

    Event(final WatchEvent.Kind<T> kind, final int count, final T context) {
      _kind = kind;
      _count = count;
      _context = context;
    }

    @Override
    public Kind<T> kind() {
      return _kind;
    }

    @Override
    public int count() {
      return _count;
    }

    @Override
    public T context() {
      return _context;
    }

    @Override
    public String toString() {
      return "Event(" + _context + ", " + _kind + ")";
    }
  }

  class MacOSXWatchKey implements java.nio.file.WatchKey {
    private final ArrayBlockingQueue<WatchEvent<?>> events;
    private final AtomicInteger overflow = new AtomicInteger(0);
    private final AtomicBoolean valid = new AtomicBoolean(true);

    public Handle getHandle() {
      return handle;
    }

    public void setHandle(final Handle handle) {
      this.handle = handle;
    }

    private Handle handle;
    private final boolean reportCreateEvents;
    private final boolean reportModifyEvents;

    @SuppressWarnings("unused")
    public boolean reportCreateEvents() {
      return reportCreateEvents;
    }

    boolean reportModifyEvents() {
      return reportModifyEvents;
    }

    boolean reportDeleteEvents() {
      return reportDeleteEvents;
    }

    private final boolean reportDeleteEvents;
    private final Path watchable;

    MacOSXWatchKey(
        final Path watchable,
        final int queueSize,
        final Handle handle,
        final WatchEvent.Kind<?>... kinds) {
      events = new ArrayBlockingQueue<>(queueSize);
      this.handle = handle;
      this.watchable = watchable;
      final Set<WatchEvent.Kind<?>> kindSet = new HashSet<>();
      int i = 0;
      while (i < kinds.length) {
        kindSet.add(kinds[i]);
        i += 1;
      }
      this.reportCreateEvents = kindSet.contains(ENTRY_CREATE);
      this.reportModifyEvents = kindSet.contains(ENTRY_MODIFY);
      this.reportDeleteEvents = kindSet.contains(ENTRY_DELETE);
    }

    @Override
    public void cancel() {
      valid.set(false);
    }

    @Override
    public Watchable watchable() {
      return watchable;
    }

    @Override
    public boolean isValid() {
      return valid.get();
    }

    @Override
    public List<WatchEvent<?>> pollEvents() {
      synchronized (MacOSXWatchKey.this) {
        final int overflowCount = overflow.getAndSet(0);
        final List<WatchEvent<?>> result =
            new ArrayList<>(events.size() + overflowCount > 0 ? 1 : 0);
        events.drainTo(result);
        if (overflowCount != 0) {
          result.add(new Event<>(OVERFLOW, overflowCount, watchable));
        }
        return Collections.unmodifiableList(result);
      }
    }

    @Override
    public boolean reset() {
      return true;
    }

    @Override
    public String toString() {
      return "MacOSXWatchKey(" + watchable + ")";
    }

    void addOverflow() {
      events.add(new Event<>(OVERFLOW, 1, null));
      overflow.incrementAndGet();
      if (!readyKeys.contains(this)) {
        readyKeys.offer(this);
      }
    }

    void addEvent(Event<Path> event) {
      synchronized (MacOSXWatchKey.this) {
        if (!events.offer(event)) {
          overflow.incrementAndGet();
        } else {
          if (!readyKeys.contains(this)) {
            readyKeys.add(this);
          }
        }
      }
    }

    void createEvent(final WatchEvent.Kind<Path> kind, final Path file) {
      Event<Path> event = new Event<>(kind, 1, watchable.relativize(file));
      addEvent(event);
    }
  }

  private class WatchKey {
    private Handle handle;
    private final Set<MacOSXWatchKey> keys = new HashSet<>();

    WatchKey(final Handle handle, final MacOSXWatchKey key) {
      this.handle = handle;
      keys.add(key);
    }

    void remove(final MacOSXWatchKey key) {
      internalExecutor.block(
          new Consumer<Executor.Thread>() {
            @Override
            public void accept(Executor.Thread thread) {
              keys.remove(key);
              if (keys.isEmpty()) {
                if (handle != Handles.INVALID) {
                  try {
                    fileEventMonitor.stopStream(handle);
                  } catch (final ClosedFileEventMonitorException e) {
                    e.printStackTrace(System.err);
                  }
                }
                registered.remove(key.watchable);
              }
            }
          });
    }
  }
}
