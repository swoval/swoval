package com.swoval.files.apple;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import com.swoval.files.RegisterableWatchService;
import com.swoval.files.apple.FileEventMonitors.Handle;
import com.swoval.files.apple.FileEventMonitors.Handles;
import com.swoval.files.apple.MacOSXWatchService.DelegateMacOSXWatchService.MacOSXWatchKey;
import com.swoval.functional.Consumer;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.WatchEvent;
import java.nio.file.WatchEvent.Kind;
import java.nio.file.WatchKey;
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
public class MacOSXWatchService implements AutoCloseable {
  private final int watchLatency;
  private final TimeUnit watchTimeUnit;
  private final int queueSize;
  private final AtomicBoolean open = new AtomicBoolean(true);
  private final Map<Path, WatchKeys> registered = new HashMap<>();

  private final Set<Path> streams = new HashSet<>();
  private final Consumer<String> dropEvent =
      new Consumer<String>() {
        @Override
        public void accept(String s) {
          synchronized (registered) {
            final Path path = Paths.get(s);
            streams.remove(path);
            final WatchKeys watchKeys = registered.get(path);
            if (watchKeys != null) {
              watchKeys.handle = Handles.INVALID;
            }
          }
        }
      };
  private final Consumer<FileEvent> onFileEvent =
      new Consumer<FileEvent>() {
        @Override
        public void accept(final FileEvent fileEvent) {
          final Path path = Paths.get(fileEvent.fileName);
          synchronized (registered) {
            final WatchKeys childKeys = registered.get(path);
            final WatchKeys watchKeys =
                childKeys == null ? registered.get(path.getParent()) : childKeys;
            final boolean exists = Files.exists(path, LinkOption.NOFOLLOW_LINKS);
            if (watchKeys != null) {
              if (fileEvent.mustScanSubDirs()) {
                final Iterator<MacOSXWatchKey> it = watchKeys.keys.iterator();
                while (it.hasNext()) {
                  final MacOSXWatchKey key = it.next();
                  key.addOverflow();
                }
              } else {
                final Iterator<MacOSXWatchKey> it = watchKeys.keys.iterator();
                while (it.hasNext()) {
                  final MacOSXWatchKey key = it.next();
                  if (exists && key.reportModifyEvents()) key.createEvent(ENTRY_MODIFY, path);
                  else if (!exists && key.reportDeleteEvents()) key.createEvent(ENTRY_DELETE, path);
                }
              }
            }
          }
        }
      };
  private final FileEventMonitor fileEventMonitor = FileEventMonitors.get(onFileEvent, dropEvent);

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
  }

  /**
   * Create a new MacOSXWatchService with a minimum latency of 10 milliseconds and a maximum queue
   * size of <code>1024</code> per watch key.
   *
   * @throws InterruptedException if the native file events api initialization is interrupted.
   */
  @SuppressWarnings("unused")
  public MacOSXWatchService() throws InterruptedException {
    // The FsEvents api doesn't seem to report events at lower than 10 millisecond intervals.
    this(10, TimeUnit.MILLISECONDS, 1024);
  }

  public RegisterableWatchService newDelegate() {
    return new DelegateMacOSXWatchService();
  }

  @Override
  @SuppressWarnings("EmptyCatchBlock")
  public void close() {
    synchronized (fileEventMonitor) {
      if (open.compareAndSet(true, false)) {
        final Iterator<WatchKeys> it = new ArrayList<>(registered.values()).iterator();
        while (it.hasNext()) {
          final WatchKeys watchKeys = it.next();
          if (watchKeys.handle != Handles.INVALID) {
            try {
              fileEventMonitor.stopStream(watchKeys.handle);
            } catch (final ClosedFileEventMonitorException e) {
              e.printStackTrace(System.err);
            }
          }
          final Iterator<MacOSXWatchKey> keys = watchKeys.keys.iterator();
          while (keys.hasNext()) {
            keys.next().cancel();
          }
          watchKeys.keys.clear();
        }
        registered.clear();
        fileEventMonitor.close();
      }
    }
  }

  private boolean isOpen() {
    return open.get();
  }

//  private void unregisterImpl(final MacOSXWatchKey key) {
//    synchronized (registered) {
//      final WatchKeys watchKeys = registered.get(key.watchable);
//      if (watchKeys != null) {
//        watchKeys.keys.remove(key);
//        if (watchKeys.keys.isEmpty()) {
//          if (key.getHandle() != Handles.INVALID) {
//            try {
//              fileEventMonitor.stopStream(key.getHandle());
//            } catch (final ClosedFileEventMonitorException e) {
//              e.printStackTrace(System.err);
//            }
//          }
//          key.setHandle(Handles.INVALID);
//          registered.remove(key.watchable);
//        }
//      }
//    }
//  }

  class DelegateMacOSXWatchService implements RegisterableWatchService {
    private final Set<MacOSXWatchKey> keys = new HashSet<>();
    private final LinkedBlockingQueue<MacOSXWatchKey> readyKeys = new LinkedBlockingQueue<>();

    @Override
    public WatchKey register(final Path path, final Kind<?>... kinds) throws IOException {
      synchronized (fileEventMonitor) {
        if (isOpen()) {
          synchronized (registered) {
            final Path realPath = path.toRealPath();
            if (!Files.isDirectory(realPath)) throw new NotDirectoryException(realPath.toString());
            final WatchKeys keys = registered.get(realPath);
            final MacOSXWatchKey result;
            if (keys == null) {
              final Flags.Create flags = new Flags.Create().setNoDefer().setFileEvents();
              Handle handle = null;
              final Iterator<Path> it = streams.iterator();
              while (it.hasNext() && handle != Handles.INVALID) {
                if (realPath.startsWith(it.next())) handle = Handles.INVALID;
              }
              if (handle != Handles.INVALID) {
                streams.add(realPath);
                try {
                  handle =
                      fileEventMonitor.createStream(realPath, watchLatency, watchTimeUnit, flags);
                } catch (final ClosedFileEventMonitorException e) {
                  close();
                  throw e;
                }
              }
              result = new MacOSXWatchKey(realPath, queueSize, handle, kinds);
              registered.put(realPath, new WatchKeys(handle, result));
            } else {
              result = new MacOSXWatchKey(realPath, queueSize, Handles.INVALID, kinds);
              keys.keys.add(result);
            }
            return result;
          }
        } else {
          throw new ClosedWatchServiceException();
        }
      }
    }

    @Override
    public void close() {
      final Iterator<MacOSXWatchKey> it = keys.iterator();
      while (it.hasNext()) {
        final MacOSXWatchKey key = it.next();
        keys.remove(it.next());
        final WatchKeys watchKeys = registered.get(key.watchable);
        if (watchKeys != null) {
          watchKeys.remove(key);
        }
      }
    }

    @Override
    public WatchKey poll() {
      if (isOpen()) {
        return readyKeys.poll();
      } else {
        throw new ClosedWatchServiceException();
      }
    }

    @Override
    public WatchKey poll(long timeout, TimeUnit unit) throws InterruptedException {
      if (isOpen()) {
        return readyKeys.poll(timeout, unit);
      } else {
        throw new ClosedWatchServiceException();
      }
    }

    @Override
    public WatchKey take() throws InterruptedException {
      if (isOpen()) {
        return readyKeys.take();
      } else {
        throw new ClosedWatchServiceException();
      }
    }

    class MacOSXWatchKey implements WatchKey {
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

      void add(final Event<?> event) {
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
        synchronized (this) {
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
        synchronized (this) {
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

  private class WatchKeys {
    private Handle handle;
    private final Set<MacOSXWatchKey> keys = new HashSet<>();

    WatchKeys(final Handle handle, final MacOSXWatchKey key) {
      this.handle = handle;
      keys.add(key);
    }

    void remove(final MacOSXWatchKey key) {
      synchronized (registered) {
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
    }
  }
}
