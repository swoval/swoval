package com.swoval.files;

import static com.swoval.files.Entries.DIRECTORY;
import static com.swoval.files.EntryFilters.AllPass;
import static com.swoval.files.PathWatchers.Event.Kind.Create;
import static com.swoval.files.PathWatchers.Event.Kind.Overflow;

import com.swoval.files.Directory.Converter;
import com.swoval.files.Directory.Entry;
import com.swoval.files.Directory.EntryFilter;
import com.swoval.files.Directory.Observer;
import com.swoval.files.PathWatchers.Event;
import com.swoval.functional.Consumer;
import com.swoval.functional.Either;
import com.swoval.functional.Filter;
import java.io.IOException;
import java.nio.file.FileSystemLoopException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

/** Provides a PathWatcher that is backed by a {@link java.nio.file.WatchService}. */
class NioPathWatcher implements PathWatcher {
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final Executor callbackExecutor;
  private final Executor internalExecutor;
  private final Map<Path, Directory<WatchedDirectory>> rootDirectories = new HashMap<>();
  private final boolean managed;
  private final DirectoryRegistry directoryRegistry;
  private final Converter<WatchedDirectory> converter;
  private final NioPathWatcherService nioPathWatcherService;
  private final Directory.Observer<WatchedDirectory> updateObserver =
      new Observer<WatchedDirectory>() {
        @Override
        public void onCreate(final Directory.Entry<WatchedDirectory> newEntry) {}

        @Override
        public void onDelete(final Directory.Entry<WatchedDirectory> oldEntry) {
          close(oldEntry.getValue());
        }

        @Override
        public void onUpdate(
            Directory.Entry<WatchedDirectory> oldEntry,
            Directory.Entry<WatchedDirectory> newEntry) {}

        @Override
        public void onError(Path path, IOException exception) {}
      };

  /**
   * Instantiate a NioPathWatcher.
   *
   * @param callback The callback to run for updates to monitored paths
   * @param registerableWatchService The underlying watchservice
   * @param callbackExecutor The Executor to invoke callbacks on
   * @param internalExecutor The Executor to internally manage the watcher
   * @param managedDirectoryRegistry The nullable registry of directories to monitor. If this is
   *     non-null, then registrations are handled by an outer class and this watcher should not call
   *     add or remove directory.
   */
  NioPathWatcher(
      final Consumer<Event> callback,
      final RegisterableWatchService registerableWatchService,
      final Executor callbackExecutor,
      final Executor internalExecutor,
      final DirectoryRegistry managedDirectoryRegistry)
      throws InterruptedException {
    this.directoryRegistry =
        managedDirectoryRegistry == null ? new DirectoryRegistry() : managedDirectoryRegistry;
    this.managed = managedDirectoryRegistry != null;
    this.callbackExecutor = callbackExecutor;
    this.internalExecutor = internalExecutor;
    this.nioPathWatcherService =
        new NioPathWatcherService(
            new Consumer<Event>() {
              @Override
              public void accept(Event event) {
                handleEvent(callback, event);
              }
            },
            new Consumer<Path>() {
              @Override
              public void accept(Path path) {
                handleOverflow(callback, path);
              }
            },
            registerableWatchService,
            internalExecutor);
    this.converter =
        new Converter<WatchedDirectory>() {
          @Override
          public WatchedDirectory apply(final Path path) {
            return Either.getOrElse(
                nioPathWatcherService.register(path), WatchedDirectories.INVALID);
          }
        };
  }

  /**
   * Register a path to monitor for file events
   *
   * @param path The directory to watch for file events
   * @param maxDepth The maximum maxDepth of subdirectories to watch
   * @return an {@link com.swoval.functional.Either} containing the result of the registration or an
   *     IOException if registration fails. This method should be idempotent and return true the
   *     first time the directory is registered or when the depth is changed. Otherwise it should
   *     return false.
   */
  @Override
  public com.swoval.functional.Either<IOException, Boolean> register(
      final Path path, final int maxDepth) {
    return internalExecutor
        .block(
            new Callable<Boolean>() {
              @Override
              public Boolean call() throws IOException {
                return registerImpl(path, maxDepth);
              }
            })
        .castLeft(IOException.class);
  }

  /**
   * Stop watching a directory
   *
   * @param path The directory to remove from monitoring
   */
  @Override
  @SuppressWarnings("EmptyCatchBlock")
  public void unregister(final Path path) {
    internalExecutor.block(
        new Runnable() {
          @Override
          public void run() {
            if (!managed) directoryRegistry.removeDirectory(path);
            final Directory<WatchedDirectory> dir = getRoot(path.getRoot());
            if (dir != null) {
              List<Directory.Entry<WatchedDirectory>> toRemove =
                  dir.list(
                      dir.getMaxDepth(),
                      new EntryFilter<WatchedDirectory>() {
                        @Override
                        public boolean accept(final Entry<? extends WatchedDirectory> entry) {
                          return !directoryRegistry.accept(entry.getPath());
                        }
                      });
              final Iterator<Directory.Entry<WatchedDirectory>> it = toRemove.iterator();
              while (it.hasNext()) {
                final Directory.Entry<WatchedDirectory> entry = it.next();
                if (!directoryRegistry.accept(entry.getPath())) {
                  final Iterator<Directory.Entry<WatchedDirectory>> toCancel =
                      dir.remove(entry.getPath()).iterator();
                  while (toCancel.hasNext()) {
                    close(toCancel.next().getValue());
                  }
                }
              }
            }
          }
        });
  }

  @SuppressWarnings("EmptyCatchBlock")
  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      internalExecutor.block(
          new Runnable() {
            @Override
            public void run() {
              callbackExecutor.close();
              final Iterator<Directory<WatchedDirectory>> it = rootDirectories.values().iterator();
              while (it.hasNext()) {
                final Directory<WatchedDirectory> dir = it.next();
                close(dir.entry().getValue());
                final Iterator<Directory.Entry<WatchedDirectory>> entries =
                    dir.list(dir.getMaxDepth(), AllPass).iterator();
                while (entries.hasNext()) {
                  close(entries.next().getValue());
                }
              }
              nioPathWatcherService.close();
            }
          });
      internalExecutor.close();
    }
  }

  private void maybeRunCallback(final Consumer<Event> callback, final Event event) {
    if (directoryRegistry.accept(event.getPath())) {
      callbackExecutor.run(
          new Runnable() {
            @Override
            public void run() {
              callback.accept(event);
            }
          });
    }
  }

  private void processPath(
      final Consumer<Event> callback,
      final Path path,
      final Event.Kind kind,
      HashSet<QuickFile> processedDirs,
      HashSet<Path> processedFiles) {
    final Set<QuickFile> newFiles = new HashSet<>();
    add(path, newFiles);
    if (processedFiles.add(path)) {
      maybeRunCallback(callback, new Event(path, kind));
      final Iterator<QuickFile> it = newFiles.iterator();
      while (it.hasNext()) {
        final QuickFile file = it.next();
        if (file.isDirectory() && processedDirs.add(file)) {
          processPath(callback, file.toPath(), Create, processedDirs, processedFiles);
        } else if (processedFiles.add(file.toPath())) {
          maybeRunCallback(callback, new Event(file.toPath(), Create));
        }
      }
    }
  }

  private void handleEvent(final Consumer<Event> callback, final Event event) {
    if (directoryRegistry.accept(event.getPath())) {
      if (!Files.exists(event.getPath())) {
        final Directory<WatchedDirectory> root = rootDirectories.get(event.getPath().getRoot());
        if (root != null) {
          final Iterator<Directory.Entry<WatchedDirectory>> it =
              root.remove(event.getPath()).iterator();
          while (it.hasNext()) {
            close(it.next().getValue());
          }
        }
      }
      if (Files.isDirectory(event.getPath())) {
        processPath(
            callback,
            event.getPath(),
            event.getKind(),
            new HashSet<QuickFile>(),
            new HashSet<Path>());
      } else {
        maybeRunCallback(callback, event);
      }
    }
  }

  private void handleOverflow(final Consumer<Event> callback, final Path path) {
    final int maxDepth = directoryRegistry.maxDepthFor(path);
    boolean stop = false;
    while (!stop && maxDepth > 0) {
      try {
        boolean registered = false;
        final Set<QuickFile> files = new HashSet<>();
        final Iterator<Path> directoryIterator =
            directoryRegistry.registeredDirectories().iterator();
        while (directoryIterator.hasNext()) {
          files.add(new QuickFileImpl(directoryIterator.next().toString(), DIRECTORY));
        }
        maybePoll(path, files);
        final Iterator<QuickFile> it = files.iterator();
        while (it.hasNext()) {
          final QuickFile file = it.next();
          if (file.isDirectory()) {
            boolean regResult =
                registerImpl(
                    file.toPath(),
                    maxDepth == Integer.MAX_VALUE ? Integer.MAX_VALUE : maxDepth - 1);
            registered = registered || regResult;
            if (regResult)
              callbackExecutor.run(
                  new Runnable() {
                    @Override
                    public void run() {
                      callback.accept(new Event(file.toPath(), Create));
                    }
                  });
          }
        }
        stop = !registered;
      } catch (NoSuchFileException e) {
        stop = false;
      } catch (IOException e) {
        stop = true;
      }
    }
    callbackExecutor.run(
        new Runnable() {
          @Override
          public void run() {
            callback.accept(new Event(path, Overflow));
          }
        });
  }

  private void maybePoll(final Path path, final Set<QuickFile> files) throws IOException {
    if (!managed) {
      boolean result;
      do {
        result = false;
        final Iterator<QuickFile> it =
            QuickList.list(
                    path,
                    0,
                    false,
                    new Filter<QuickFile>() {
                      @Override
                      public boolean accept(QuickFile quickFile) {
                        return !quickFile.isDirectory()
                            || directoryRegistry.accept(quickFile.toPath());
                      }
                    })
                .iterator();
        while (it.hasNext()) {
          result = files.add(it.next()) || result;
        }
      } while (!Thread.currentThread().isInterrupted() && result);
    }
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
   * @param newFiles The set of files that are found for the newly created directory
   * @return true if no exception is thrown
   */
  private boolean add(final Path path, final Set<QuickFile> newFiles) {
    boolean result = true;
    try {
      if (directoryRegistry.maxDepthFor(path) >= 0) {
        final Directory<WatchedDirectory> dir = getRoot(path.getRoot());
        if (dir != null) {
          update(dir, path);
        }
      }
      maybePoll(path, newFiles);
    } catch (IOException e) {
      result = false;
    }
    return result;
  }

  private Directory<WatchedDirectory> getRoot(final Path root) {
    Directory<WatchedDirectory> result = rootDirectories.get(root);
    if (result == null) {
      try {
        result =
            new Directory<>(
                    root,
                    root,
                    converter,
                    Integer.MAX_VALUE,
                    new Filter<QuickFile>() {
                      @Override
                      public boolean accept(QuickFile quickFile) {
                        return directoryRegistry.accept(quickFile.toPath());
                      }
                    })
                .init();
        rootDirectories.put(root, result);
      } catch (final IOException e) {
      }
    }
    return result;
  }

  private boolean registerImpl(final Path path, final int maxDepth) throws IOException {
    final int existingMaxDepth = directoryRegistry.maxDepthFor(path);
    boolean result = existingMaxDepth < maxDepth;
    Path realPath;
    try {
      realPath = path.toRealPath();
    } catch (IOException e) {
      realPath = path;
    }
    if (result && !managed) {
      directoryRegistry.addDirectory(path, maxDepth);
    } else if (!path.equals(realPath)) {
      /*
       * Note that watchedDir is not null, which means that this path has been
       * registered with a different alias.
       */
      throw new FileSystemLoopException(path.toString());
    }
    final Directory<WatchedDirectory> dir = getRoot(realPath.getRoot());
    if (dir != null) {
      final List<Directory.Entry<WatchedDirectory>> directories = dir.list(path, -1, AllPass);
      if (result || directories.isEmpty() || !isValid(directories.get(0).getValue())) {
        Path toUpdate = path;
        while (toUpdate != null && !Files.isDirectory(toUpdate)) {
          toUpdate = toUpdate.getParent();
        }
        if (toUpdate != null) update(dir, toUpdate);
      }
    }
    return result;
  }

  private void update(final Directory<WatchedDirectory> dir, final Path path) throws IOException {
    dir.update(path, DIRECTORY).observe(updateObserver);
  }

  private void close(final com.swoval.functional.Either<IOException, WatchedDirectory> either) {
    if (either.isRight()) either.get().close();
  }

  private boolean isValid(
      final com.swoval.functional.Either<IOException, WatchedDirectory> either) {
    return either.isRight() && either.get().isValid();
  }
}
