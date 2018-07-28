package com.swoval.files;

import static com.swoval.files.PathWatchers.Event.Kind.Create;
import static com.swoval.files.PathWatchers.Event.Kind.Delete;
import static com.swoval.files.PathWatchers.Event.Kind.Modify;
import static com.swoval.functional.Filters.AllPass;
import static java.util.Map.Entry;

import com.swoval.files.Executor.ThreadHandle;
import com.swoval.files.FileTreeDataViews.Converter;
import com.swoval.files.FileTreeViews.Observer;
import com.swoval.files.PathWatchers.Event;
import com.swoval.files.PathWatchers.Event.Kind;
import com.swoval.files.PathWatchers.Overflow;
import com.swoval.functional.Consumer;
import com.swoval.functional.Either;
import com.swoval.functional.Filter;
import com.swoval.runtime.Platform;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Provides a PathWatcher that is backed by a {@link java.nio.file.WatchService}. */
class NioPathWatcher implements PathWatcher<PathWatchers.Event>, AutoCloseable {
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final Executor internalExecutor;
  private final Observers<PathWatchers.Event> observers = new Observers<>();
  private final Map<Path, CachedDirectory<WatchedDirectory>> rootDirectories =
      new LinkedHashMap<>();
  private final DirectoryRegistry directoryRegistry;
  private final Converter<WatchedDirectory> converter;
  private final FileTreeViews.CacheObserver<WatchedDirectory> updateCacheObserver =
      new FileTreeViews.CacheObserver<WatchedDirectory>() {
        @Override
        @SuppressWarnings("EmptyCatchBlock")
        public void onCreate(final FileTreeDataViews.Entry<WatchedDirectory> newEntry) {
          maybeRunCallback(new Event(newEntry, Create));
          try {
            final Iterator<TypedPath> it =
                FileTreeViews.list(
                        newEntry.getPath(),
                        0,
                        new Filter<TypedPath>() {
                          @Override
                          public boolean accept(final TypedPath typedPath) {
                            return directoryRegistry.accept(typedPath.getPath());
                          }
                        })
                    .iterator();
            while (it.hasNext()) {
              final TypedPath tp = it.next();
              maybeRunCallback(new Event(tp, Create));
            }
          } catch (final IOException e) {
            // This likely means the directory was deleted, which should be handle by the downstream
            // NioPathWatcherService.
          }
        }

        @Override
        public void onDelete(final FileTreeDataViews.Entry<WatchedDirectory> oldEntry) {
          if (oldEntry.getValue().isRight()) oldEntry.getValue().get().close();
          maybeRunCallback(new Event(oldEntry, Delete));
        }

        @Override
        public void onUpdate(
            final FileTreeDataViews.Entry<WatchedDirectory> oldEntry,
            final FileTreeDataViews.Entry<WatchedDirectory> newEntry) {}

        @Override
        public void onError(final IOException exception) {}
      };
  private final NioPathWatcherService service;

  /**
   * Instantiate a NioPathWatcher.
   *
   * @param internalExecutor The Executor to internally manage the watcher
   */
  NioPathWatcher(
      final DirectoryRegistry directoryRegistry,
      final RegisterableWatchService watchService,
      final Executor internalExecutor)
      throws InterruptedException {
    this.directoryRegistry = directoryRegistry;
    this.internalExecutor = internalExecutor;
    this.service =
        new NioPathWatcherService(
            new Consumer<Either<Overflow, Event>>() {
              @Override
              public void accept(final Either<Overflow, Event> either) {
                if (!closed.get()) {
                  internalExecutor.run(
                      new Consumer<ThreadHandle>() {
                        @Override
                        public void accept(final ThreadHandle threadHandle) {
                          if (either.isRight()) {
                            final Event event = either.get();
                            handleEvent(
                                new Event(TypedPaths.get(event.getPath()), event.getKind()),
                                threadHandle);
                          } else {
                            handleOverflow(Either.leftProjection(either).getValue(), threadHandle);
                          }
                        }
                      });
                }
              }
            },
            watchService,
            internalExecutor);
    this.converter =
        new Converter<WatchedDirectory>() {
          @Override
          public WatchedDirectory apply(final TypedPath typedPath) {
            return typedPath.isDirectory()
                ? Either.getOrElse(
                    service.register(typedPath.getPath()), WatchedDirectories.INVALID)
                : WatchedDirectories.INVALID;
          }
        };
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
   * @param typedPath The newly created directory to add
   */
  void add(final TypedPath typedPath, final ThreadHandle threadHandle) {
    if (directoryRegistry.maxDepthFor(typedPath.getPath()) >= 0) {
      final CachedDirectory<WatchedDirectory> dir = getOrAdd(typedPath.getPath(), threadHandle);
      if (dir != null) {
        update(dir, typedPath, threadHandle);
      }
    }
  }

  @Override
  public Either<IOException, Boolean> register(final Path path, final int maxDepth) {
    Either<IOException, Boolean> either = Either.right(false);
    try {
      final ThreadHandle threadHandle = internalExecutor.getThreadHandle();
      try {
        final int existingMaxDepth = directoryRegistry.maxDepthFor(path);
        boolean result = existingMaxDepth < maxDepth;
        either = Either.right(result);
        final TypedPath typedPath = TypedPaths.get(path);
        final Path realPath = typedPath.toRealPath();
        if (result) {
          directoryRegistry.addDirectory(typedPath.getPath(), maxDepth);
        }
        final CachedDirectory<WatchedDirectory> dir = getOrAdd(realPath);
        if (dir != null) {
          final List<FileTreeDataViews.Entry<WatchedDirectory>> directories =
              dir.listEntries(typedPath.getPath(), -1, AllPass);
          if (result || directories.isEmpty() || directories.get(0).getValue().isRight()) {
            Path toUpdate = typedPath.getPath();
            if (toUpdate != null) update(dir, typedPath, threadHandle);
          }
        }
      } finally {
        threadHandle.release();
      }
    } catch (final InterruptedException e) {
      either = Either.right(false);
    }
    return either;
  }

  private CachedDirectory<WatchedDirectory> findOrAddRoot(final Path rawPath) {
    final Path parent = Platform.isMac() ? rawPath : rawPath.getRoot();
    final Path path = parent == null ? rawPath.getRoot() : parent;
    assert (path != null);
    final Iterator<Entry<Path, CachedDirectory<WatchedDirectory>>> it =
        rootDirectories.entrySet().iterator();
    CachedDirectory<WatchedDirectory> result = null;
    final List<Path> toRemove = new ArrayList<>();
    while (result == null && it.hasNext()) {
      final Entry<Path, CachedDirectory<WatchedDirectory>> entry = it.next();
      final Path root = entry.getKey();
      if (path.startsWith(root)) {
        result = entry.getValue();
      } else if (root.startsWith(path) && !path.equals(root)) {
        toRemove.add(root);
      }
    }
    if (result == null) {
      Path toAdd = path;
      boolean init = false;
      while (!init && toAdd != null) {
        try {
          result =
              new CachedDirectoryImpl<>(
                      toAdd,
                      toAdd,
                      converter,
                      Integer.MAX_VALUE,
                      new Filter<TypedPath>() {
                        @Override
                        public boolean accept(final TypedPath typedPath) {
                          return typedPath.isDirectory()
                              && directoryRegistry.acceptPrefix(typedPath.getPath());
                        }
                      },
                      FileTreeViews.getDefault(false))
                  .init();
          init = true;
          rootDirectories.put(toAdd, result);
        } catch (final IOException e) {
          toAdd = toAdd.getParent();
        }
      }
    }
    final Iterator<Path> toRemoveIterator = toRemove.iterator();
    while (toRemoveIterator.hasNext()) {
      rootDirectories.remove(toRemoveIterator.next());
    }
    return result;
  }

  private CachedDirectory<WatchedDirectory> getOrAdd(final Path path) throws InterruptedException {
    final ThreadHandle threadHandle = internalExecutor.getThreadHandle();
    try {
      return getOrAdd(path, threadHandle);
    } finally {
      threadHandle.release();
    }
  }

  private CachedDirectory<WatchedDirectory> getOrAdd(
      final Path path, final ThreadHandle threadHandle) {
    CachedDirectory<WatchedDirectory> result = null;
    if (!closed.get()) {
      result = findOrAddRoot(path);
    }
    return result;
  }

  @Override
  @SuppressWarnings("EmptyCatchBlock")
  public void unregister(final Path path) {
    try {
      final ThreadHandle threadHandle = internalExecutor.getThreadHandle();
      try {
        directoryRegistry.removeDirectory(path);
        final CachedDirectory<WatchedDirectory> dir = rootDirectories.get(path.getRoot());
        if (dir != null) {
          final int depth = dir.getPath().relativize(path).getNameCount();
          List<FileTreeDataViews.Entry<WatchedDirectory>> toRemove =
              dir.listEntries(
                  depth,
                  new Filter<FileTreeDataViews.Entry<WatchedDirectory>>() {
                    @Override
                    public boolean accept(final FileTreeDataViews.Entry<WatchedDirectory> entry) {
                      return !directoryRegistry.acceptPrefix(entry.getPath());
                    }
                  });
          final Iterator<FileTreeDataViews.Entry<WatchedDirectory>> it = toRemove.iterator();
          while (it.hasNext()) {
            final FileTreeDataViews.Entry<WatchedDirectory> entry = it.next();
            if (!directoryRegistry.acceptPrefix(entry.getPath())) {
              final Iterator<FileTreeDataViews.Entry<WatchedDirectory>> toCancel =
                  dir.remove(entry.getPath(), threadHandle).iterator();
              while (toCancel.hasNext()) {
                final Either<IOException, WatchedDirectory> either = toCancel.next().getValue();
                if (either.isRight()) either.get().close();
              }
            }
          }
        }
      } finally {
        threadHandle.release();
      }
    } catch (final InterruptedException e) {
    }
  }

  @Override
  @SuppressWarnings("EmptyCatchBlock")
  public void close() {
    try {
      final ThreadHandle threadHandle = internalExecutor.getThreadHandle();
      try {
        if (closed.compareAndSet(false, true)) {
          service.close();
          final Iterator<CachedDirectory<WatchedDirectory>> it =
              rootDirectories.values().iterator();
          while (it.hasNext()) {
            final Either<IOException, WatchedDirectory> either = it.next().getEntry().getValue();
            if (either.isRight()) either.get().close();
          }
          rootDirectories.clear();
        }
      } finally {
        threadHandle.release();
      }
    } catch (final InterruptedException e) {
    }
    internalExecutor.close();
  }

  private void update(
      final CachedDirectory<WatchedDirectory> dir,
      final TypedPath typedPath,
      final ThreadHandle threadHandle) {
    dir.update(typedPath, threadHandle).observe(updateCacheObserver);
  }

  private void handleOverflow(final Overflow overflow, final ThreadHandle threadHandle) {
    final Path path = overflow.getPath();
    final CachedDirectory<WatchedDirectory> root = getOrAdd(path, threadHandle);
    if (root != null) {
      try {
        final Iterator<TypedPath> it =
            FileTreeViews.list(
                    path,
                    0,
                    new Filter<TypedPath>() {
                      @Override
                      public boolean accept(TypedPath typedPath) {
                        return typedPath.isDirectory()
                            && directoryRegistry.acceptPrefix(typedPath.getPath());
                      }
                    })
                .iterator();
        while (it.hasNext()) {
          final TypedPath file = it.next();
          add(file, threadHandle);
        }
      } catch (final IOException e) {
        final Iterator<FileTreeDataViews.Entry<WatchedDirectory>> removed =
            root.remove(path, threadHandle).iterator();
        while (removed.hasNext()) {
          maybeRunCallback(new Event(Entries.setExists(removed.next(), false), Delete));
        }
      }
    }
    maybeRunCallback(new Event(TypedPaths.get(path), Modify));
  }

  private void maybeRunCallback(final Event event) {
    if (directoryRegistry.accept(event.getPath())) {
      observers.onNext(event);
    }
  }

  private void handleEvent(final Event event, final ThreadHandle threadHandle) {
    if (directoryRegistry.acceptPrefix(event.getPath())) {
      final TypedPath typedPath = TypedPaths.get(event.getPath());
      if (!typedPath.exists()) {
        final CachedDirectory<WatchedDirectory> root = getOrAdd(event.getPath(), threadHandle);
        if (root != null) {
          final boolean isRoot = root.getPath().equals(event.getPath());
          final Iterator<FileTreeDataViews.Entry<WatchedDirectory>> it =
              isRoot
                  ? root.listEntries(root.getMaxDepth(), AllPass).iterator()
                  : root.remove(event.getPath(), threadHandle).iterator();
          while (it.hasNext()) {
            final FileTreeDataViews.Entry<WatchedDirectory> entry = it.next();
            final Either<IOException, WatchedDirectory> either = entry.getValue();
            if (either.isRight()) {
              either.get().close();
            }
            maybeRunCallback(new Event(entry, Kind.Delete));
          }
          if (isRoot) {
            rootDirectories.remove(root.getPath());
            getOrAdd(event.getPath(), threadHandle);
          }
        }
      }
      maybeRunCallback(event);
      if (typedPath.isDirectory()) {
        add(typedPath, threadHandle);
      }
    }
  }

  @Override
  public int addObserver(final Observer<Event> observer) {
    return observers.addObserver(observer);
  }

  @Override
  public void removeObserver(final int handle) {
    observers.removeObserver(handle);
  }
}
