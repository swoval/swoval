package com.swoval.files;

import static com.swoval.files.PathWatchers.Event.Kind.Create;
import static com.swoval.files.PathWatchers.Event.Kind.Delete;
import static com.swoval.files.PathWatchers.Event.Kind.Overflow;
import static com.swoval.functional.Filters.AllPass;
import static java.util.Map.Entry;

import com.swoval.files.FileTreeDataViews.CacheObserver;
import com.swoval.files.FileTreeDataViews.Converter;
import com.swoval.files.FileTreeViews.Observer;
import com.swoval.files.PathWatchers.Event;
import com.swoval.files.PathWatchers.Event.Kind;
import com.swoval.files.PathWatchers.Overflow;
import com.swoval.functional.Consumer;
import com.swoval.functional.Either;
import com.swoval.functional.Filter;
import com.swoval.functional.Filters;
import com.swoval.logging.Logger;
import com.swoval.logging.Loggers;
import com.swoval.logging.Loggers.Level;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

class RootDirectories extends LockableMap<Path, CachedDirectory<WatchedDirectory>> {}
/** Provides a PathWatcher that is backed by a {@link java.nio.file.WatchService}. */
class NioPathWatcher implements PathWatcher<PathWatchers.Event>, AutoCloseable {
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final Observers<PathWatchers.Event> observers = new Observers<>();
  private final RootDirectories rootDirectories = new RootDirectories();
  private final DirectoryRegistry directoryRegistry;
  private final Converter<WatchedDirectory> converter;
  private final Logger logger;

  private CacheObserver<WatchedDirectory> updateCacheObserver(final List<Event> events) {
    return new CacheObserver<WatchedDirectory>() {
      @Override
      @SuppressWarnings("EmptyCatchBlock")
      public void onCreate(final FileTreeDataViews.Entry<WatchedDirectory> newEntry) {
        events.add(new Event(newEntry.getTypedPath(), Create));
        try {
          final Iterator<TypedPath> it =
              FileTreeViews.list(
                      newEntry.getTypedPath().getPath(),
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
            events.add(new Event(tp, Create));
          }
        } catch (final IOException e) {
          // This likely means the directory was deleted, which should be handle by the downstream
          // NioPathWatcherService.
        }
      }

      @Override
      public void onDelete(final FileTreeDataViews.Entry<WatchedDirectory> oldEntry) {
        if (oldEntry.getValue().isRight()) {
          if (Loggers.shouldLog(logger, Level.DEBUG))
            logger.debug(this + " closing key for " + oldEntry.getTypedPath().getPath());
          oldEntry.getValue().get().close();
        }
        events.add(new Event(oldEntry.getTypedPath(), Delete));
      }

      @Override
      public void onUpdate(
          final FileTreeDataViews.Entry<WatchedDirectory> oldEntry,
          final FileTreeDataViews.Entry<WatchedDirectory> newEntry) {}

      @Override
      public void onError(final IOException exception) {}
    };
  }

  private final NioPathWatcherService service;

  NioPathWatcher(
      final DirectoryRegistry directoryRegistry,
      final RegisterableWatchService watchService,
      final Logger logger)
      throws InterruptedException {
    this.directoryRegistry = directoryRegistry;
    this.logger = logger;
    this.service =
        new NioPathWatcherService(
            new Consumer<Either<Overflow, Event>>() {
              @Override
              public void accept(final Either<Overflow, Event> either) {
                if (!closed.get()) {
                  if (either.isRight()) {
                    final Event event = either.get();
                    handleEvent(event);
                  } else {
                    handleOverflow(Either.leftProjection(either).getValue());
                  }
                }
              }
            },
            watchService,
            logger);
    this.converter =
        new Converter<WatchedDirectory>() {
          @Override
          public WatchedDirectory apply(final TypedPath typedPath) {
            return typedPath.isDirectory() && !typedPath.isSymbolicLink()
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
  void add(final TypedPath typedPath, final List<Event> events) {
    if (directoryRegistry.maxDepthFor(typedPath.getPath()) >= 0
        || directoryRegistry.acceptPrefix(typedPath.getPath())) {
      final CachedDirectory<WatchedDirectory> dir = getOrAdd(typedPath.getPath());
      if (dir != null) {
        update(dir, typedPath, events, true);
      }
    }
  }

  private void remove(final Path path, List<Event> events) {
    final CachedDirectory<WatchedDirectory> root = rootDirectories.remove(path);
    final CachedDirectory<WatchedDirectory> dir = root == null ? find(path) : root;
    if (dir != null) remove(dir, path, events);
  }

  private void remove(
      final CachedDirectory<WatchedDirectory> cachedDirectory,
      final Path path,
      final List<Event> events) {
    final List<FileTreeDataViews.Entry<WatchedDirectory>> toCancel = cachedDirectory.remove(path);
    if (path == null || path == cachedDirectory.getPath()) toCancel.add(cachedDirectory.getEntry());
    final Iterator<FileTreeDataViews.Entry<WatchedDirectory>> it = toCancel.iterator();
    while (it.hasNext()) {
      final FileTreeDataViews.Entry<WatchedDirectory> entry = it.next();
      final Either<IOException, WatchedDirectory> either = entry.getValue();
      if (either.isRight()) {
        if (events != null) {
          final TypedPath typedPath =
              TypedPaths.get(
                  entry.getTypedPath().getPath(),
                  TypedPaths.getKind(entry.getTypedPath()) | Entries.NONEXISTENT);
          events.add(new Event(typedPath, Delete));
        }
        either.get().close();
      }
    }
  }

  @Override
  public Either<IOException, Boolean> register(final Path path, final int maxDepth) {
    final Path absolutePath = path.isAbsolute() ? path : path.toAbsolutePath();
    final int existingMaxDepth = directoryRegistry.maxDepthFor(absolutePath);
    boolean result = existingMaxDepth < maxDepth;
    final TypedPath typedPath = TypedPaths.get(absolutePath);
    if (result) {
      directoryRegistry.addDirectory(absolutePath, maxDepth);
    }
    final CachedDirectory<WatchedDirectory> dir = getOrAdd(absolutePath);
    final List<Event> events = new ArrayList<>();
    if (dir != null) {
      Path current = typedPath.getPath();
      Path root = dir.getEntry().getTypedPath().getPath();
      Path relative = root.relativize(current);
      Path lastPath = root;
      final List<FileTreeDataViews.Entry<WatchedDirectory>> entries =
          dir.listEntries(root, relative.getNameCount() + 1, AllPass);
      Iterator<FileTreeDataViews.Entry<WatchedDirectory>> it = entries.iterator();
      while (it.hasNext()) {
        FileTreeDataViews.Entry<WatchedDirectory> entry = it.next();
        Path next = entry.getTypedPath().getPath();
        if (current.startsWith(next) && (next.toString().length() > lastPath.toString().length())) {
          lastPath = next;
        }
      }
      update(dir, TypedPaths.get(lastPath), events, true);
    }
    runCallbacks(events);
    if (Loggers.shouldLog(logger, Level.DEBUG))
      logger.debug(this + " registered " + path + " with max depth " + maxDepth);
    return Either.right(result);
  }

  private CachedDirectory<WatchedDirectory> find(final Path rawPath) {
    return find(rawPath, null);
  }

  private CachedDirectory<WatchedDirectory> find(final Path rawPath, final List<Path> toRemove) {
    final Path path = rawPath == null ? getRoot() : rawPath;
    assert (path != null);
    if (rootDirectories.lock()) {
      try {
        final Iterator<Entry<Path, CachedDirectory<WatchedDirectory>>> it =
            rootDirectories.iterator();
        CachedDirectory<WatchedDirectory> result = null;
        while (result == null && it.hasNext()) {
          final Entry<Path, CachedDirectory<WatchedDirectory>> entry = it.next();
          final Path root = entry.getKey();
          if (path.startsWith(root)) {
            result = entry.getValue();
          } else if (toRemove != null && root.startsWith(path) && !path.equals(root)) {
            toRemove.add(root);
          }
        }
        return result;
      } finally {
        rootDirectories.unlock();
      }
    } else {
      return null;
    }
  }

  private Path getRoot() {
    /* This may not make sense on windows which has multiple root directories, but at least it
     * will return something.
     */
    final Iterator<Path> it = FileSystems.getDefault().getRootDirectories().iterator();
    return it.next();
  }

  private CachedDirectory<WatchedDirectory> findOrAddRoot(final Path rawPath) {
    final List<Path> toRemove = new ArrayList<>();
    CachedDirectory<WatchedDirectory> result = find(rawPath, toRemove);
    if (result == null) {
      /*
       * We want to monitor the parent in case the file is deleted.
       */
      final Path parent = rawPath.getParent();
      Path path = parent == null ? getRoot() : parent;
      assert (path != null);
      boolean init = false;
      while (!init && path != null) {
        try {
          result =
              new CachedDirectoryImpl<>(
                      TypedPaths.get(path),
                      converter,
                      Integer.MAX_VALUE,
                      new Filter<TypedPath>() {
                        @Override
                        public boolean accept(final TypedPath typedPath) {
                          return typedPath.isDirectory()
                              && !typedPath.isSymbolicLink()
                              && directoryRegistry.acceptPrefix(typedPath.getPath());
                        }
                      },
                      false)
                  .init();
          init = true;
          rootDirectories.put(path, result);
        } catch (final IOException e) {
          path = path.getParent();
        }
      }
    }
    final Iterator<Path> toRemoveIterator = toRemove.iterator();
    while (toRemoveIterator.hasNext()) {
      rootDirectories.remove(toRemoveIterator.next());
    }
    return result;
  }

  private CachedDirectory<WatchedDirectory> getOrAdd(final Path path) {
    CachedDirectory<WatchedDirectory> result = null;
    if (rootDirectories.lock()) {
      try {
        if (!closed.get()) {
          result = findOrAddRoot(path);
        }
      } finally {
        rootDirectories.unlock();
      }
    }
    return result;
  }

  @Override
  @SuppressWarnings("EmptyCatchBlock")
  public void unregister(final Path path) {
    final Path absolutePath = path.isAbsolute() ? path : path.toAbsolutePath();
    directoryRegistry.removeDirectory(absolutePath);
    if (rootDirectories.lock()) {
      try {
        final CachedDirectory<WatchedDirectory> dir = find(absolutePath);
        if (dir != null) {
          final int depth = dir.getPath().relativize(absolutePath).getNameCount();
          List<FileTreeDataViews.Entry<WatchedDirectory>> toRemove =
              dir.listEntries(
                  depth,
                  new Filter<FileTreeDataViews.Entry<WatchedDirectory>>() {
                    @Override
                    public boolean accept(final FileTreeDataViews.Entry<WatchedDirectory> entry) {
                      return !directoryRegistry.acceptPrefix(entry.getTypedPath().getPath());
                    }
                  });
          final Iterator<FileTreeDataViews.Entry<WatchedDirectory>> it = toRemove.iterator();
          while (it.hasNext()) {
            final FileTreeDataViews.Entry<WatchedDirectory> entry = it.next();
            if (!directoryRegistry.acceptPrefix(entry.getTypedPath().getPath())) {
              remove(dir, entry.getTypedPath().getPath(), null);
            }
          }
          rootDirectories.remove(dir.getPath());
        }
      } finally {
        rootDirectories.unlock();
      }
    }
    if (Loggers.shouldLog(logger, Level.DEBUG)) logger.debug(this + " unregistered " + path);
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      service.close();
      rootDirectories.clear();
    }
  }

  @SuppressWarnings("EmptyCatchBlock")
  private void update(
      final CachedDirectory<WatchedDirectory> dir,
      final TypedPath typedPath,
      final List<Event> events,
      final boolean rescanDirectories) {
    try {
      dir.update(typedPath, rescanDirectories).observe(updateCacheObserver(events));
    } catch (final NoSuchFileException e) {
      remove(dir, typedPath.getPath(), events);
      final TypedPath newTypedPath = TypedPaths.get(typedPath.getPath());
      events.add(new Event(newTypedPath, newTypedPath.exists() ? Kind.Modify : Kind.Delete));
      final CachedDirectory<WatchedDirectory> root = rootDirectories.remove(typedPath.getPath());
      if (root != null) {
        final Iterator<FileTreeDataViews.Entry<WatchedDirectory>> it =
            root.listEntries(Integer.MAX_VALUE, AllPass).iterator();
        while (it.hasNext()) {
          it.next().getValue().get().close();
        }
      }
    } catch (final IOException e) {
    }
  }

  private void handleOverflow(final Overflow overflow) {
    final Path path = overflow.getPath();
    if (Loggers.shouldLog(logger, Level.DEBUG))
      logger.debug(this + " received overflow for " + path);
    final List<Event> events = new ArrayList<>();
    if (rootDirectories.lock()) {
      try {
        final CachedDirectory<WatchedDirectory> root = find(path);
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
              add(file, events);
            }
          } catch (final IOException e) {
            final List<FileTreeDataViews.Entry<WatchedDirectory>> removed = root.remove(path);
            final Iterator<FileTreeDataViews.Entry<WatchedDirectory>> removedIt =
                removed.iterator();
            while (removedIt.hasNext()) {
              events.add(
                  new Event(Entries.setExists(removedIt.next(), false).getTypedPath(), Delete));
            }
          }
        }
      } finally {
        rootDirectories.unlock();
      }
    }
    final TypedPath tp = TypedPaths.get(path);
    events.add(new Event(tp, tp.exists() ? Overflow : Delete));
    runCallbacks(events);
  }

  private void runCallbacks(final List<Event> events) {
    final Iterator<Event> it = events.iterator();
    final Set<Path> handled = new HashSet<>();
    while (it.hasNext()) {
      final Event event = it.next();
      final Path path = event.getTypedPath().getPath();
      if (directoryRegistry.accept(path) && handled.add(path)) {
        observers.onNext(new Event(TypedPaths.get(path), event.getKind()));
      }
    }
  }

  private void handleEvent(final Event event) {
    if (Loggers.shouldLog(logger, Level.DEBUG)) logger.debug(this + " received event " + event);
    final List<Event> events = new ArrayList<>();
    if (!closed.get() && rootDirectories.lock()) {
      try {
        if (directoryRegistry.acceptPrefix(event.getTypedPath().getPath())) {
          final boolean isDelete = event.getKind() == Delete;
          final TypedPath typedPath = TypedPaths.get(event.getTypedPath().getPath());
          if (isDelete) remove(typedPath.getPath(), events);
          if (typedPath.exists()) {
            if (typedPath.isDirectory() && !typedPath.isSymbolicLink()) add(typedPath, events);
            events.add(event);
          } else if (!isDelete) remove(typedPath.getPath(), events);
          else events.add(event);
        }
      } finally {
        rootDirectories.unlock();
      }
    }
    runCallbacks(events);
  }

  @Override
  public int addObserver(final Observer<? super Event> observer) {
    return observers.addObserver(observer);
  }

  @Override
  public void removeObserver(final int handle) {
    observers.removeObserver(handle);
  }
}
