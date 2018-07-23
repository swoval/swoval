package com.swoval.files.apple;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import com.swoval.files.RegisterableWatchService;
import com.swoval.files.apple.FileEventsApi.ClosedFileEventsApiException;
import com.swoval.functional.Consumer;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
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
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Provides an alternative {@link java.nio.file.WatchService} for mac that uses native file system
 * events rather than polling for file changes.
 */
public class MacOSXWatchService implements WatchService, AutoCloseable, RegisterableWatchService {
  private final double watchLatency;
  private final int queueSize;
  private final AtomicBoolean open = new AtomicBoolean(true);
  private final PriorityBlockingQueue<MacOSXWatchKey> readyKeys = new PriorityBlockingQueue<>();
  private final Map<Path, MacOSXWatchKey> registered = new HashMap<>();
  private final LinkedBlockingQueue<String> logMessages = new LinkedBlockingQueue<>();
  private final Thread loggingThread =
      new Thread("logging thread") {
        @Override
        public void run() {
          boolean stop = false;
          while (!stop) {
            try {
              final String string = logMessages.take();
              System.err.println(string);
            } catch (final InterruptedException e) {
              stop = true;
            }
          }
        }
      };

  private final Set<Path> streams = new HashSet<>();
  private final Consumer<String> dropEvent =
      new Consumer<String>() {
        @Override
        public void accept(String s) {
          synchronized (registered) {
            final Path path = Paths.get(s);
            streams.remove(path);
            final MacOSXWatchKey key = registered.get(path);
            if (key != null) {
              key.setStreamId(-1);
            }
          }
        }
      };
  private final Consumer<FileEvent> onFileEvent =
      new Consumer<FileEvent>() {
        @Override
        public void accept(final FileEvent fileEvent) {
          final Path path = Paths.get(fileEvent.fileName);
          if (path.toString().contains("NioFile")) logMessages.offer("CB for " + path);
          synchronized (registered) {
            final MacOSXWatchKey childKey = registered.get(path);
            final MacOSXWatchKey key =
                childKey == null ? registered.get(path.getParent()) : childKey;
            final boolean exists = Files.exists(path, LinkOption.NOFOLLOW_LINKS);
            if (key != null) {
              if (exists && key.reportModifyEvents()) createEvent(key, ENTRY_MODIFY, path);
              else if (!exists && key.reportDeleteEvents()) createEvent(key, ENTRY_DELETE, path);
            }
          }
        }
      };
  private final FileEventsApi watcher = FileEventsApi.apply(onFileEvent, dropEvent);

  public MacOSXWatchService(final double watchLatency, final int queueSize)
      throws InterruptedException {
    this.watchLatency = watchLatency;
    this.queueSize = queueSize;
    loggingThread.setDaemon(true);
    loggingThread.start();
  }

  @SuppressWarnings("unused")
  public MacOSXWatchService() throws InterruptedException {
    // The FsEvents api doesn't seem to report events at lower than 10 millisecond intervals.
    this(0.01, 1024);
  }

  @Override
  @SuppressWarnings("EmptyCatchBlock")
  public void close() {
    synchronized (watcher) {
      if (open.compareAndSet(true, false)) {
        watcher.close();
        loggingThread.interrupt();
        try {
          loggingThread.join(5000);
        } catch (final InterruptedException e) {
        }
        final Iterator<Path> it = new ArrayList<>(registered.keySet()).iterator();
        while (it.hasNext()) {
          unregisterImpl(it.next());
        }
        registered.clear();
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

  private void createEvent(
      final MacOSXWatchKey key, final WatchEvent.Kind<Path> kind, final Path file) {
    Event<Path> event = new Event<>(kind, 1, key.watchable.relativize(file));
    key.addEvent(event);
    if (!readyKeys.contains(key)) {
      readyKeys.offer(key);
    }
  }

  private boolean isOpen() {
    return open.get();
  }

  public WatchKey register(final Path path, final WatchEvent.Kind<?>... kinds) throws IOException {
    synchronized (watcher) {
      if (isOpen()) {
        synchronized (registered) {
          final Path realPath = path.toRealPath();
          if (!Files.isDirectory(realPath)) throw new NotDirectoryException(realPath.toString());
          final MacOSXWatchKey key = registered.get(realPath);
          final MacOSXWatchKey result;
          if (key == null) {
            final int flags = new Flags.Create().setNoDefer().setFileEvents().getValue();
            int id = -2;
            final Iterator<Path> it = streams.iterator();
            while (it.hasNext() && id != -1) {
              if (realPath.startsWith(it.next())) id = -1;
            }
            if (id != -1) {
              streams.add(realPath);
              try {
                id = watcher.createStream(realPath.toString(), watchLatency, flags);
              } catch (ClosedFileEventsApiException e) {
                close();
                throw e;
              }
            }
            result = new MacOSXWatchKey(this, realPath, queueSize, id, kinds);
            registered.put(realPath, result);
          } else {
            result = key;
          }
          return result;
        }
      } else {
        throw new ClosedWatchServiceException();
      }
    }
  }

  private void unregisterImpl(final Path path) {
    synchronized (registered) {
      final MacOSXWatchKey key = registered.get(path);
      if (key != null) {
        registered.remove(path);
        key.setStreamId(-1);
        if (key.getStreamId() != -1) watcher.stopStream(key.getStreamId());
      }
    }
  }

  public void unregister(final Path path) {
    if (isOpen()) unregisterImpl(path);
    else {
      throw new ClosedWatchServiceException();
    }
  }

  private static class MacOSXWatchKey implements WatchKey, Comparable<MacOSXWatchKey> {
    private final ArrayBlockingQueue<WatchEvent<?>> events;
    private final AtomicInteger overflow = new AtomicInteger(0);
    private final AtomicBoolean valid = new AtomicBoolean(true);
    private final MacOSXWatchService service;

    public int getStreamId() {
      return streamId;
    }

    public void setStreamId(final int streamId) {
      this.streamId = streamId;
    }

    private int streamId;
    private final boolean reportCreateEvents;
    private final boolean reportModifyEvents;

    @SuppressWarnings("unused")
    public boolean reportCreateEvents() {
      return reportCreateEvents;
    }

    public boolean reportModifyEvents() {
      return reportModifyEvents;
    }

    public boolean reportDeleteEvents() {
      return reportDeleteEvents;
    }

    private final boolean reportDeleteEvents;
    private final Path watchable;

    MacOSXWatchKey(
        final MacOSXWatchService service,
        final Path watchable,
        final int queueSize,
        final int id,
        final WatchEvent.Kind<?>... kinds) {
      this.service = service;
      events = new ArrayBlockingQueue<>(queueSize);
      streamId = id;
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
      try {
        service.unregister(watchable);
      } catch (ClosedWatchServiceException e) {
      }
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

    void addEvent(Event<Path> event) {
      synchronized (this) {
        if (!events.offer(event)) {
          overflow.incrementAndGet();
        }
      }
    }

    @Override
    public int compareTo(MacOSXWatchKey o) {
      return this.watchable.compareTo(o.watchable);
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
}
