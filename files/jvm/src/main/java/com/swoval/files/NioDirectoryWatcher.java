package com.swoval.files;

import static com.swoval.files.DirectoryWatcher.Event.Create;
import static com.swoval.files.DirectoryWatcher.Event.Delete;
import static com.swoval.files.DirectoryWatcher.Event.Modify;
import static com.swoval.files.DirectoryWatcher.Event.Overflow;
import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import com.swoval.files.apple.MacOSXWatchService;
import com.swoval.functional.Consumer;
import com.swoval.functional.Either;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystemLoopException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.Watchable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Provides a DirectoryWatcher that is backed by a {@link java.nio.file.WatchService}. */
public class NioDirectoryWatcher extends DirectoryWatcher {
  private final Map<Watchable, WatchedDir> watchedDirs = new HashMap<>();
  private final Registerable watchService;
  private final Thread loopThread;
  private final AtomicBoolean isStopped = new AtomicBoolean(false);
  private final CountDownLatch shutdownLatch = new CountDownLatch(1);
  private static final AtomicInteger threadId = new AtomicInteger(0);
  private final Executor callbackExecutor;
  private final Executor executor;

  /**
   * Instantiate a NioDirectoryWatch using the default {@link Registerable}.
   *
   * @param callback The callback to invoke on a created/deleted/modified path
   * @param executor Internal executor to manage state
   * @throws IOException when the WatchService can't be started
   * @throws InterruptedException when the WatchService initialization is interrupted
   */
  NioDirectoryWatcher(final Consumer<Event> callback, final Executor executor)
      throws IOException, InterruptedException {
    this(
        callback,
        Platform.isMac() ? new MacOSXWatchService() : new RegisterableWatchService(),
        Executor.make("NioDirectoryWatcher-callback-thread"),
        executor);
  }
  /**
   * Instantiate a NioDirectoryWatch using the default {@link Registerable}.
   *
   * @param callback The callback to invoke on a created/deleted/modified path
   * @throws IOException when the WatchService can't be started
   * @throws InterruptedException when the WatchService initialization is interrupted
   */
  public NioDirectoryWatcher(final Consumer<Event> callback) throws IOException, InterruptedException {
    this(callback, Platform.isMac() ? new MacOSXWatchService() : new RegisterableWatchService());
  }

  /**
   * Instantiate a NioDirectoryWatch with a particular {@link Registerable}.
   *
   * @param callback The callback to invoke on a created/deleted/modified path
   * @param watchService The underlying watchservice that is used to monitor directories for events
   */
  public NioDirectoryWatcher(final Consumer<Event> callback, final Registerable watchService) {
    this(callback, watchService, Executor.make("NioDirectoryWatcher-callback-thread"), null);
  }

  /**
   * Instantiate a NioDirectoryWatch with provided {@link Registerable} and a queue size limit.
   *
   * @param callback The callback to invoke on a created/deleted/modified path
   * @param watchService The underlying watchservice that is used to monitor directories for events
   * @param callbackExecutor The Executor to invoke callbacks on
   * @param executor The Executor to internally manage the watcher
   */
  NioDirectoryWatcher(
      final Consumer<Event> callback,
      final Registerable watchService,
      final Executor callbackExecutor,
      final Executor executor) {
    this.watchService = watchService;
    this.callbackExecutor = callbackExecutor;
    this.executor =
        executor == null
            ? Executor.make("com.swoval.files.NioDirectoryWatcher-internal-executor")
            : executor;
    loopThread =
        new Thread("NioDirectoryWatcher-loop-thread-" + threadId.incrementAndGet()) {
          @Override
          public void run() {
            while (!isStopped.get() && !Thread.currentThread().isInterrupted()) {
              try {
                final WatchKey key = watchService.take();
                NioDirectoryWatcher.this.executor.run(
                    new Runnable() {
                      @Override
                      public void run() {
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
                          if (!key.reset()) {
                            key.cancel();
                            watchedDirs.remove(key.watchable());
                          }
                          try {
                            if (!kind.equals(Overflow)) {
                              final Path path =
                                  ((Path) key.watchable()).resolve((Path) e.context());
                              handleEvent(callback, path, key, kind);
                            } else {
                              handleOverflow(callback, key);
                            }
                          } catch (RejectedExecutionException ex2) {
                            boolean result = false;
                            while (!result && !callbackExecutor.isClosed()) {
                              final Iterator<WatchedDir> itx = watchedDirs.values().iterator();
                              final List<Runnable> runnables = new ArrayList<>();
                              while (itx.hasNext()) {
                                final WatchedDir watchedDir = itx.next();
                                runnables.add(
                                    new Runnable() {
                                      @Override
                                      public void run() {
                                        handleOverflow(callback, watchedDir.key);
                                      }
                                    });
                              }
                              while (!result) {
                                try {
                                  try {
                                    callbackExecutor.run(
                                        new Runnable() {
                                          @Override
                                          public void run() {
                                            final Iterator<Runnable> it = runnables.iterator();
                                            while (it.hasNext()) {
                                              it.next().run();
                                            }
                                          }
                                        });
                                    result = true;
                                  } catch (RejectedExecutionException ex) {
                                    Thread.sleep(5);
                                  }
                                } catch (InterruptedException ie) {
                                  result = true;
                                }
                              }
                            }
                          }
                        }
                      }
                    });
              } catch (RejectedExecutionException e) {
                if (!isStopped.get()) throw e;
              } catch (ClosedWatchServiceException | InterruptedException e) {
                isStopped.set(true);
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
      executor.block(
          new Runnable() {
            @Override
            public void run() {
              try {
                callbackExecutor.close();
                loopThread.interrupt();
                try {
                  watchService.close();
                } catch (IOException e) {
                }
                shutdownLatch.await(5, TimeUnit.SECONDS);
                loopThread.join(5000);
              } catch (InterruptedException e) {
              }
              final Iterator<WatchedDir> it = watchedDirs.values().iterator();
              while (it.hasNext()) {
                WatchKey key = it.next().key;
                key.cancel();
                key.reset();
              }
            }
          });
      executor.close();
    }
  }

  private class WatchedDir {
    final WatchKey key;
    final int maxDepth;
    final int compMaxDepth;

    WatchedDir(final WatchKey key, final int maxDepth) {
      this.key = key;
      this.maxDepth = maxDepth;
      compMaxDepth = maxDepth == Integer.MAX_VALUE ? maxDepth : maxDepth + 1;
    }

    public boolean accept(final Path path) {
      return path.startsWith((Path) key.watchable()) && path.equals(key.watchable())
          || ((Path) key.watchable()).relativize(path).getNameCount() <= compMaxDepth;
    }
  }

  private void runCallback(final Consumer<Event> callback, final DirectoryWatcher.Event event) {
    callbackExecutor.run(
        new Runnable() {
          @Override
          public void run() {
            callback.accept(event);
          }
        });
  }

  private void handleEvent(
      final Consumer<Event> callback, final Path path, final WatchKey key, final Event.Kind kind) {
    final Path keyPath = (Path) key.watchable();
    final Set<Path> newFiles = new HashSet<>();
    if (Files.isDirectory(path)) {
      WatchedDir watchedDir = watchedDirs.get(keyPath);
      if (watchedDir != null
          && watchedDir.accept(path)
          && !watchedDirs.containsKey(path)
          && watchedDir.maxDepth > 0) {
        add(
            path,
            watchedDir.maxDepth - (watchedDir.maxDepth == Integer.MAX_VALUE ? 0 : 1),
            newFiles);
      }
    }
    runCallback(callback, new DirectoryWatcher.Event(path, kind));
    final Iterator<Path> it = newFiles.iterator();
    while (it.hasNext()) {
      runCallback(callback, new DirectoryWatcher.Event(it.next(), Event.Create));
    }
  }

  private void handleOverflow(final Consumer<Event> callback, final WatchKey key) {
    if (!key.reset()) {
      key.cancel();
      watchedDirs.remove(key.watchable());
    }
    final Path path = (Path) key.watchable();
    final WatchedDir watchedDir = watchedDirs.get(path);
    boolean stop = false;
    while (!stop && watchedDir != null && watchedDir.maxDepth > 0) {
      final Set<Watchable> initialKeys = new HashSet<>(watchedDirs.keySet());
      try {
        boolean registered = false;
        final Iterator<QuickFile> it = QuickList.list(path, 0).iterator();
        while (it.hasNext()) {
          final QuickFile file = it.next();
          if (file.isDirectory()) {
            registered = registered || registerImpl(file.toPath(), watchedDir.maxDepth - 1);
          }
        }
        stop = !registered;
      } catch (NoSuchFileException e) {
        stop = false;
      } catch (IOException e) {
        stop = true;
      }
      final Set<Watchable> newKeys = new HashSet<>(watchedDirs.keySet());
      stop = stop || !different(initialKeys, newKeys);
    }
    final Iterator<Watchable> pathIterator = watchedDirs.keySet().iterator();
    final List<Watchable> toRemove = new ArrayList<>();
    while (pathIterator.hasNext()) {
      final Path p = (Path) pathIterator.next();
      if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
        toRemove.add(p);
      }
    }
    final Iterator<Watchable> toRemoveIterator = toRemove.iterator();
    while (toRemoveIterator.hasNext()) {
      unregister((Path) toRemoveIterator.next());
    }
    callbackExecutor.run(
        new Runnable() {
          @Override
          public void run() {
            callback.accept(new DirectoryWatcher.Event((Path) key.watchable(), Overflow));
          }
        });
  }

  private <T> boolean differentElements(final Set<T> left, final Set right) {
    boolean result = false;
    final Iterator<T> it = left.iterator();
    while (!result && it.hasNext()) {
      result = !right.contains(it.next());
    }
    return result;
  }

  private <T> boolean different(final Set<T> left, final Set<T> right) {
    return left.size() != right.size() || differentElements(left, right);
  }

  /**
   * Similar to register, but tracks all of the new files found in the directory. It polls the
   * directory until the contents stop changing to ensure that a callback is fired for each path in
   * the newly created directory (up to the maxDepth). The assumption is that once the callback is
   * fired for the path, it is safe to assume that no event for a new file in the directory is
   * missed. Without the polling, it would be possible that a new file was created in the directory
   * before we registered it with the watch service. If this happened, then no callback would be
   * invoked for that file.
   *
   * @param path The newly created directory to add
   * @param maxDepth The maximum depth of the subdirectory traversal
   * @param newFiles The set of files that are found for the newly created directory
   * @return
   */
  private boolean add(final Path path, final int maxDepth, final Set<Path> newFiles) {
    boolean result = true;
    final Set<Path> files = new HashSet<>();
    try {
      final WatchKey key = watchService.register(path, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
      final WatchedDir watchedDir = new WatchedDir(key, maxDepth);
      final WatchedDir previous = watchedDirs.put(path, watchedDir);
      if (previous != null && !Platform.isMac()) previous.key.cancel();
      newFiles.add(path);
      do {
        files.addAll(newFiles);
        final Iterator<QuickFile> it = QuickList.list(path, 0, false).iterator();
        while (result && it.hasNext()) {
          final QuickFile quickFile = it.next();
          final Path file = quickFile.toPath();
          if (quickFile.isDirectory() && maxDepth > 0 && newFiles.add(file)) {
            result = add(file, maxDepth == Integer.MAX_VALUE ? maxDepth : maxDepth - 1, newFiles);
          } else {
            newFiles.add(file);
          }
        }
      } while (result && different(files, newFiles));
    } catch (IOException e) {
      result = false;
    }
    return result;
  }

  private boolean registerImpl(final Path path, final int maxDepth, final boolean doReg) {
    boolean result = true;
    try {
      if (doReg) {
        final WatchKey key = watchService.register(path, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
        final WatchedDir watchedDir = new WatchedDir(key, maxDepth);
        final WatchedDir previous = watchedDirs.put(path, watchedDir);
        if (previous != null && !Platform.isMac()) previous.key.cancel();
      }
      final Set<QuickFile> files = new HashSet<>();
      final Set<QuickFile> newFiles = new HashSet<>();
      do {
        files.addAll(newFiles);
        final Iterator<QuickFile> it = QuickList.list(path, 0, false).iterator();
        while (result && it.hasNext()) {
          final QuickFile quickFile = it.next();
          if (quickFile.isDirectory() && maxDepth > 0 && newFiles.add(quickFile)) {
            result =
                registerImpl(
                    quickFile.toPath(), maxDepth == Integer.MAX_VALUE ? maxDepth : maxDepth - 1);
          } else {
            newFiles.add(quickFile);
          }
        }
      } while (result && different(files, newFiles));
    } catch (IOException e) {
      result = false;
    }
    return result;
  }

  private boolean registerImpl(final Path path, final int maxDepth) throws IOException {
    boolean result = true;
    if (Files.exists(path)) {
      final Path realPath = path.toRealPath();
      final WatchedDir watchedDir = watchedDirs.get(realPath);
      if (watchedDir == null) {
        result = registerImpl(realPath, maxDepth, true);
      } else if (!path.equals(realPath)) {
        // Note that watchedDir is not null, which means that this path has been
        // registered
        // with a different alias.
        throw new FileSystemLoopException(path.toString());
      } else if (watchedDir.maxDepth < maxDepth) {
        result = registerImpl(realPath, maxDepth, false);
      } else {
        result = false;
      }
    }
    return result;
  }

  /**
   * Register a path to monitor for file events
   *
   * @param path The directory to watch for file events
   * @param maxDepth The maximum maxDepth of subdirectories to watch
   * @return true if the registration is successful
   */
  @Override
  public boolean register(final Path path, final int maxDepth) {
    final Either<Boolean, Exception> either =
        executor.block(
            new Callable<Boolean>() {
              @Override
              public Boolean call() throws IOException {
                return registerImpl(path, maxDepth);
              }
            });
    return either.isLeft() && either.left();
  }
  /**
   * Stop watching a directory
   *
   * @param path The directory to remove from monitoring
   */
  @Override
  @SuppressWarnings("EmptyCatchBlock")
  public void unregister(final Path path) {
    Path rawRealPath = null;
    try {
      rawRealPath = path.toRealPath();
    } catch (IOException e) {
    }
    final Path realPath = rawRealPath;
    executor.block(
        new Runnable() {
          @Override
          public void run() {
            final WatchedDir watchedDir = watchedDirs.remove(realPath == null ? path : realPath);
            if (watchedDir != null) {
              WatchKey key = watchedDir.key;
              key.cancel();
              key.reset();
            }
          }
        });
  }
}
