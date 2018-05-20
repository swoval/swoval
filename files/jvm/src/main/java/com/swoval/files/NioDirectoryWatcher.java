package com.swoval.files;

import static com.swoval.files.DirectoryWatcher.Event.Create;
import static com.swoval.files.DirectoryWatcher.Event.Delete;
import static com.swoval.files.DirectoryWatcher.Event.Modify;
import static com.swoval.files.DirectoryWatcher.Event.Overflow;
import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.Watchable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public class NioDirectoryWatcher extends DirectoryWatcher {
  private final ReentrantLock lock = new ReentrantLock();
  private final Map<Watchable, WatchedDir> watchedDirs = new HashMap<>();
  private final Registerable watchService;
  private final Thread loopThread;
  private final AtomicBoolean isStopped = new AtomicBoolean(false);
  private final CountDownLatch shutdownLatch = new CountDownLatch(1);
  private static final AtomicInteger threadId = new AtomicInteger(0);

  public NioDirectoryWatcher(final Callback callback) throws IOException, InterruptedException {
    this(
        callback,
        Platform.isMac() ? new MacOSXWatchService() : new RegisterableWatchService());
  }

  public NioDirectoryWatcher(final Callback callback, final Registerable watchService)
      throws IOException {
    this.watchService = watchService;
    loopThread =
        new Thread("NioDirectoryWatcher-loop-thread-" + threadId.incrementAndGet()) {
          @Override
          public void run() {
            while (!isStopped.get()) {
              try {
                final WatchKey key = watchService.take();
                final Iterator<WatchEvent<?>> it = key.pollEvents().iterator();
                while (it.hasNext()) {
                  final WatchEvent<?> e = it.next();
                  final WatchEvent.Kind<?> k = e.kind();
                  final Event.Kind kind =
                      k.equals(ENTRY_DELETE)
                          ? Delete
                          : k.equals(ENTRY_CREATE)
                              ? Create
                              : k.equals(OVERFLOW) ? Overflow : Modify;
                  synchronized (lock) {
                    if (!kind.equals(Overflow)) {
                      final Path path = ((Path) key.watchable()).resolve((Path) e.context());
                      handleEvent(callback, path, key, kind);
                    } else {
                      handleOverflow(callback, key);
                    }
                  }
                }
              } catch (ClosedWatchServiceException | InterruptedException e) {
                isStopped.set(true);
              } catch (Exception e) {
                StackTraceElement[] elements = e.getStackTrace();
                int i = 0;
                while (i < elements.length) {
                  i += 1;
                }
              }
            }
            shutdownLatch.countDown();
          }
        };
    loopThread.setDaemon(true);
    loopThread.start();
  }

  @SuppressWarnings("EmptyCatchBlock")
  @Override
  public void close() {
    if (isStopped.compareAndSet(false, true)) {
      super.close();
      try {
        loopThread.interrupt();
        try {
          watchService.close();
        } catch (IOException e) {
        }
        shutdownLatch.await(5, TimeUnit.SECONDS);
        loopThread.join(5000);
      } catch (InterruptedException e) {
      }
      synchronized (lock) {
        final Iterator<WatchedDir> it = watchedDirs.values().iterator();
        while (it.hasNext()) {
          WatchKey key = it.next().key;
          key.cancel();
          key.reset();
        }
      }
    }
  }

  private class WatchedDir {
    final WatchKey key;
    final boolean recursive;

    WatchedDir(final WatchKey key, final boolean recursive) {
      this.key = key;
      this.recursive = recursive;
    }
  }

  private boolean registerImpl(final Path realPath, final boolean recursive) {
    synchronized (lock) {
      boolean result = true;
      if (watchedDirs.containsKey(realPath)) {
        result = false;
      } else {
        try {
          WatchKey key = watchService.register(realPath, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
          watchedDirs.put(realPath, new WatchedDir(key, recursive));
        } catch (IOException e) {
          result = false;
        }
      }
      return result && (!recursive || recursiveRegister(realPath));
    }
  }

  private boolean recursiveRegister(final Path path) {
    final Iterator<Path> it = FileOps.list(path, false).iterator();
    while (it.hasNext()) {
      final Path toRegister = it.next();
      if (Files.isDirectory(toRegister)) register(toRegister, true);
    }
    return true;
  }

  private void handleEvent(
      final Callback callback, final Path path, final WatchKey key, final Event.Kind kind) {
    final Path keyPath = (Path) key.watchable();
    if (Files.isDirectory(path)) {
      WatchedDir watchedDir = watchedDirs.get(keyPath);
      if (watchedDir != null && watchedDir.recursive) {
        if (register(path, true)) {
          final Iterator<Path> it = FileOps.list(path, false).iterator();
          while (it.hasNext()) {
            final Path toRegister = it.next();
            if (Files.isDirectory(toRegister)) register(toRegister, true);
          }
        }
      }
    }
    callback.apply(new DirectoryWatcher.Event(path, kind));
    if (!key.reset()) {
      synchronized (lock) {
        watchedDirs.remove(keyPath);
      }
    }
  }

  private void handleOverflow(final Callback callback, final WatchKey key) {
    synchronized (lock) {
      if (!key.reset()) {
        key.cancel();
        watchedDirs.remove(key.watchable());
      }
      final WatchedDir watchedDir = watchedDirs.get(key.watchable());
      boolean stop = false;
      while ((watchedDir != null && watchedDir.recursive) && !stop) {
        final Iterator<Path> pathIterator = FileOps.list((Path) key.watchable(), true).iterator();
        boolean registered = false;
        while (pathIterator.hasNext()) {
          final Path path = pathIterator.next();
          if (Files.isDirectory(path) && register(path, true)) registered = true;
        }
        stop = !registered;
      }
      final Iterator<Watchable> pathIterator = watchedDirs.keySet().iterator();
      final List<Watchable> toRemove = new ArrayList<>();
      while (pathIterator.hasNext()) {
        final Path p = (Path) pathIterator.next();
        if (!Files.exists(p)) {
          toRemove.add(p);
        }
      }
      final Iterator<Watchable> toRemoveIterator = toRemove.iterator();
      while (toRemoveIterator.hasNext()) {
        unregister((Path) toRemoveIterator.next());
      }
      callback.apply(new DirectoryWatcher.Event((Path) key.watchable(), Overflow));
    }
  }

  @Override
  public boolean register(final Path path, final boolean recursive) {
    try {
      return registerImpl(path.toRealPath(), recursive);
    } catch (IOException e) {
      return false;
    }
  }

  @Override
  public void unregister(final Path path) {
    final WatchedDir watchedDir = watchedDirs.remove(path);
    if (watchedDir != null) {
      WatchKey key = watchedDir.key;
      key.cancel();
      key.reset();
    }
  }
}
