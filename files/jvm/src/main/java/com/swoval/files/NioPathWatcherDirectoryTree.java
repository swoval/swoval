package com.swoval.files;

import static com.swoval.files.Entries.DIRECTORY;
import static com.swoval.files.PathWatchers.Event.Kind.Create;
import static com.swoval.files.PathWatchers.Event.Kind.Delete;
import static com.swoval.files.PathWatchers.Event.Kind.Refresh;
import static com.swoval.functional.Filters.AllPass;

import com.swoval.files.DataViews.Converter;
import com.swoval.files.DataViews.Entry;
import com.swoval.files.FileTreeViews.Observer;
import com.swoval.files.PathWatchers.Event;
import com.swoval.files.PathWatchers.Event.Kind;
import com.swoval.files.PathWatchers.Overflow;
import com.swoval.functional.Consumer;
import com.swoval.functional.Either;
import com.swoval.functional.Filter;
import java.io.IOException;
import java.nio.file.FileSystemLoopException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

class NioPathWatcherDirectoryTree
    implements PathWatcher<PathWatchers.Event>, Filter<Path>, AutoCloseable {
  private final Map<Path, CachedDirectory<WatchedDirectory>> rootDirectories = new HashMap<>();
  private final DirectoryRegistry directoryRegistry;
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final Converter<WatchedDirectory> converter;
  private final Observers<PathWatchers.Event> observers;
  private final Executor executor;
  private final FileTreeViews.CacheObserver<WatchedDirectory> updateCacheObserver =
      new FileTreeViews.CacheObserver<WatchedDirectory>() {
        @Override
        @SuppressWarnings("EmptyCatchBlock")
        public void onCreate(final Entry<WatchedDirectory> newEntry) {
          maybeRunCallback(new Event(newEntry, Create));
          try {
            final Iterator<TypedPath> it =
                FileTreeViews.list(
                        newEntry.getPath(),
                        0,
                        new Filter<TypedPath>() {
                          @Override
                          public boolean accept(final TypedPath typedPath) {
                            return !typedPath.isDirectory()
                                && directoryRegistry.accept(typedPath.getPath());
                          }
                        })
                    .iterator();
            while (it.hasNext()) {
              maybeRunCallback(new Event(it.next(), Create));
            }
          } catch (final IOException e) {
            // This likely means the directory was deleted, which should be handle by the downstream
            // NioPathWatcherService.
          }
        }

        @Override
        public void onDelete(final Entry<WatchedDirectory> oldEntry) {
          if (oldEntry.getValue().isRight()) oldEntry.getValue().get().close();
          maybeRunCallback(new Event(oldEntry, Delete));
        }

        @Override
        public void onUpdate(
            final Entry<WatchedDirectory> oldEntry, final Entry<WatchedDirectory> newEntry) {}

        @Override
        public void onError(final IOException exception) {}
      };
  private final NioPathWatcherService service;
  private final Consumer<Event> callback;

  NioPathWatcherDirectoryTree(
      final Observers<PathWatchers.Event> observers,
      final DirectoryRegistry directoryRegistry,
      final RegisterableWatchService watchService,
      final Consumer<Event> callback,
      final Executor internalExecutor)
      throws InterruptedException {
    this.observers = observers;
    this.callback = callback;
    this.directoryRegistry = directoryRegistry;
    service =
        new NioPathWatcherService(
            new Consumer<Either<Overflow, Event>>() {
              @Override
              public void accept(final Either<Overflow, Event> either) {
                internalExecutor.run(
                    new Consumer<Executor.Thread>() {
                      @Override
                      public void accept(final Executor.Thread thread) {
                        if (either.isRight()) {
                          final Event event = either.get();
                          handleEvent(
                              new Event(TypedPaths.get(event.getPath()), event.getKind()), thread);
                        } else {
                          handleOverflow(Either.leftProjection(either).getValue(), thread);
                        }
                      }
                    });
              }
            },
            watchService,
            internalExecutor);
    executor = internalExecutor;

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

  @Override
  public boolean accept(final Path path) {
    return directoryRegistry.acceptPrefix(path);
  }

  Iterator<CachedDirectory<WatchedDirectory>> iterator() {
    return rootDirectories.values().iterator();
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
  void add(final TypedPath typedPath, final Executor.Thread thread) {
    if (directoryRegistry.maxDepthFor(typedPath.getPath()) >= 0) {
      final CachedDirectory<WatchedDirectory> dir =
          Either.getOrElse(getOrAdd(typedPath.getPath().getRoot()), null);
      if (dir != null) {
        update(dir, typedPath, thread);
      }
    }
  }

  @Override
  public Either<IOException, Boolean> register(final Path path, final int maxDepth) {
    return executor
        .block(
            new Function<Executor.Thread, Boolean>() {
              @Override
              public Boolean apply(Executor.Thread thread) throws IOException {
                final int existingMaxDepth = directoryRegistry.maxDepthFor(path);
                boolean result = existingMaxDepth < maxDepth;
                final TypedPath typedPath = TypedPaths.get(path);
                final Path realPath = typedPath.toRealPath();
                if (result) {
                  directoryRegistry.addDirectory(typedPath.getPath(), maxDepth);
                } else if (!typedPath.getPath().equals(realPath)) {
                  /*
                   * Note that watchedDir is not null, which means that this typedPath has been
                   * registered with a different alias.
                   */
                  throw new FileSystemLoopException(typedPath.toString());
                }
                final CachedDirectory<WatchedDirectory> dir =
                    Either.getOrElse(getOrAdd(realPath.getRoot()), null);
                if (dir != null) {
                  final List<Entry<WatchedDirectory>> directories =
                      dir.listEntries(typedPath.getPath(), -1, AllPass);
                  if (result || directories.isEmpty() || !isValid(directories.get(0).getValue())) {
                    Path toUpdate = typedPath.getPath();
                    if (toUpdate != null) update(dir, typedPath, thread);
                  }
                }
                return result;
              }
            })
        .castLeft(IOException.class, false);
  }

  CachedDirectory<WatchedDirectory> get(final Path root) {
    return rootDirectories.get(root);
  }

  private Either<IOException, CachedDirectory<WatchedDirectory>> getOrAdd(final Path root) {
    return executor
        .block(
            new Function<Executor.Thread, CachedDirectory<WatchedDirectory>>() {
              @Override
              public CachedDirectory<WatchedDirectory> apply(final Executor.Thread thread)
                  throws IOException {
                if (!closed.get()) {
                  final CachedDirectory<WatchedDirectory> existing = rootDirectories.get(root);
                  if (existing == null) {
                    final CachedDirectory<WatchedDirectory> newDirectory =
                        new CachedDirectoryImpl<>(
                                root,
                                root,
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
                    if (newDirectory != null) {
                      rootDirectories.put(root, newDirectory);
                    }
                    return newDirectory;
                  } else {
                    return existing;
                  }
                } else {
                  return null;
                }
              }
            })
        .castLeft(IOException.class, null);
  }

  @Override
  public void unregister(final Path path) {
    executor.block(
        new Consumer<Executor.Thread>() {
          @Override
          public void accept(Executor.Thread thread) {
            directoryRegistry.removeDirectory(path);
            final CachedDirectory<WatchedDirectory> dir = get(path.getRoot());
            if (dir != null) {
              final int depth = dir.getPath().relativize(path).getNameCount();
              List<Entry<WatchedDirectory>> toRemove =
                  dir.listEntries(
                      depth,
                      new Filter<Entry<WatchedDirectory>>() {
                        @Override
                        public boolean accept(final Entry<WatchedDirectory> entry) {
                          return !directoryRegistry.acceptPrefix(entry.getPath());
                        }
                      });
              final Iterator<Entry<WatchedDirectory>> it = toRemove.iterator();
              while (it.hasNext()) {
                final Entry<WatchedDirectory> entry = it.next();
                if (!directoryRegistry.acceptPrefix(entry.getPath())) {
                  final Iterator<Entry<WatchedDirectory>> toCancel =
                      dir.remove(entry.getPath(), thread).iterator();
                  while (toCancel.hasNext()) {
                    final Either<IOException, WatchedDirectory> either = toCancel.next().getValue();
                    if (either.isRight()) either.get().close();
                  }
                }
              }
            }
          }
        });
  }

  @Override
  public void close() {
    executor.run(
        new Consumer<Executor.Thread>() {
          @Override
          public void accept(Executor.Thread thread) {
            if (closed.compareAndSet(false, true)) {
              service.close();
              final Iterator<CachedDirectory<WatchedDirectory>> it =
                  rootDirectories.values().iterator();
              while (it.hasNext()) {
                final Either<IOException, WatchedDirectory> either =
                    it.next().getEntry().getValue();
                if (either.isRight()) either.get().close();
              }
            }
          }
        });
  }

  private void update(
      final CachedDirectory<WatchedDirectory> dir,
      final TypedPath typedPath,
      final Executor.Thread thread) {
    dir.update(typedPath, thread).observe(updateCacheObserver);
  }

  private boolean isValid(
      final com.swoval.functional.Either<IOException, WatchedDirectory> either) {
    return either.isRight() && either.get().isValid();
  }

  private void handleOverflow(final Overflow overflow, final Executor.Thread thread) {
    final Path path = overflow.getPath();
    //if (path.toString().contains("NioFile")) System.out.println("handle overflow for " + path);
    final int maxDepth = directoryRegistry.maxDepthFor(path);
    final CachedDirectory<WatchedDirectory> root = rootDirectories.get(path.getRoot());
    if (root != null) {
      if (maxDepth >= 0) {
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
            final boolean regResult =
                Either.getOrElse(
                    register(
                        file.getPath(),
                        maxDepth == Integer.MAX_VALUE ? Integer.MAX_VALUE : maxDepth - 1),
                    false);
            if (regResult) {
              maybeRunCallback(new Event(file, Create));
            }
          }
        } catch (final IOException e) {
          final Iterator<Entry<WatchedDirectory>> removed = root.remove(path, thread).iterator();
          while (removed.hasNext()) {
            maybeRunCallback(new Event(Entries.setExists(removed.next(), false), Delete));
          }
        }
      }
    }
  }

  private void maybeRunCallback(final Event event) {
    if (directoryRegistry.accept(event.getPath())) {
      callback.accept(event);
    }
  }

  private void handleEvent(final Event event, final Executor.Thread thread) {
    if (directoryRegistry.accept(event.getPath())) {
      final TypedPath typedPath = TypedPaths.get(event.getPath());
      if (!typedPath.exists()) {
        final CachedDirectory<WatchedDirectory> root =
            Either.getOrElse(getOrAdd(event.getPath().getRoot()), null);
        if (root != null) {
          final Iterator<Entry<WatchedDirectory>> it =
              root.remove(event.getPath(), thread).iterator();
          while (it.hasNext()) {
            final Either<IOException, WatchedDirectory> either = it.next().getValue();
            if (either.isRight()) either.get().close();
          }
        }
      }
      if (typedPath.isDirectory()) {
        add(typedPath, thread);
      }
      maybeRunCallback(event);
    } else {
     // System.out.println("Rejected " + event);
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
