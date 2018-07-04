package com.swoval.files;

import static com.swoval.files.PathWatcher.DEFAULT_FACTORY;
import static com.swoval.files.PathWatcher.Event.Create;
import static com.swoval.files.PathWatcher.Event.Delete;
import static com.swoval.files.PathWatcher.Event.Modify;
import static com.swoval.files.PathWatcher.Event.Overflow;
import static com.swoval.files.EntryFilters.AllPass;

import com.swoval.files.Directory.Converter;
import com.swoval.files.Directory.Observer;
import com.swoval.files.Directory.OnChange;
import com.swoval.files.Directory.OnError;
import com.swoval.files.PathWatcher.Event;
import com.swoval.files.PathWatcher.Event.Kind;
import com.swoval.functional.Consumer;
import com.swoval.functional.Either;
import com.swoval.runtime.ShutdownHooks;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Provides an in memory cache of portions of the file system. Directories are added to the cache
 * using the {@link FileCache#register(Path, boolean)} method. Once a directory is added the cache,
 * its contents may be retrieved using the {@link FileCache#list(Path, boolean,
 * Directory.EntryFilter)} method. The cache stores the path information in {@link Directory.Entry}
 * instances.
 *
 * <p>A default implementation is provided by {@link FileCache#apply}. The user may cache arbitrary
 * information in the cache by customizing the {@link Directory.Converter} that is passed into the
 * factory {@link FileCache#apply}.
 *
 * @param <T> The type of data stored in the {@link Directory.Entry} instances for the cache
 */
public abstract class FileCache<T> implements AutoCloseable {
  final Observers<T> observers = new Observers<>();

  /**
   * Add observer of file events
   *
   * @param observer The new observer
   * @return handle that can be used to remove the callback using {@link #removeObserver(int)}
   */
  public int addObserver(final Observer<T> observer) {
    return observers.addObserver(observer);
  }
  /**
   * Add callback to fire when a file event is detected by the monitor
   *
   * @param onChange The callback to fire on file events
   * @return handle that can be used to remove the callback using {@link #removeObserver(int)}
   */
  public int addCallback(final OnChange<T> onChange) {
    return addObserver(
        new Observer<T>() {
          @Override
          public void onCreate(final Directory.Entry<T> newEntry) {
            onChange.apply(newEntry);
          }

          @Override
          public void onDelete(final Directory.Entry<T> oldEntry) {
            onChange.apply(oldEntry);
          }

          @Override
          public void onUpdate(
              final Directory.Entry<T> oldEntry, final Directory.Entry<T> newEntry) {
            onChange.apply(newEntry);
          }

          @Override
          public void onError(final Path path, final IOException exception) {}
        });
  }

  /**
   * Stop firing the previously registered callback where {@code handle} is returned by {@link
   * #addObserver(Directory.Observer)}
   *
   * @param handle A handle to the observer added by {@link #addObserver(Directory.Observer)}
   */
  public void removeObserver(final int handle) {
    observers.removeObserver(handle);
  }

  /**
   * Lists the cache elements in the particular path
   *
   * @param path The path to list. This may be a file in which case the result list contains only
   *     this path or the empty list if the path is not monitored by the cache.
   * @param maxDepth The maximum depth of children of the parent to traverse in the tree.
   * @param filter Only include cache entries that are accepted by the filter.
   * @return The list of cache elements. This will be empty if the path is not monitored in a
   *     monitored path. If the path is a file and the file is monitored by the cache, the returned
   *     list will contain just the cache entry for the path.
   */
  public abstract List<Directory.Entry<T>> list(
      final Path path, final int maxDepth, final Directory.EntryFilter<? super T> filter);

  /**
   * Lists the cache elements in the particular path
   *
   * @param path The path to list. This may be a file in which case the result list contains only
   *     this path or the empty list if the path is not monitored by the cache.
   * @param recursive Toggles whether or not to include paths in subdirectories. Even when the cache
   *     is recursively monitoring the input path, it will not return cache entries for children if
   *     this flag is false.
   * @param filter Only include cache entries that are accepted by the filter.
   * @return The list of cache elements. This will be empty if the path is not monitored in a
   *     monitored path. If the path is a file and the file is monitored by the cache, the returned
   *     list will contain just the cache entry for the path.
   */
  public List<Directory.Entry<T>> list(
      final Path path, final boolean recursive, final Directory.EntryFilter<? super T> filter) {
    return list(path, recursive ? Integer.MAX_VALUE : 0, filter);
  }

  /**
   * Lists the cache elements in the particular path without any filtering
   *
   * @param path The path to list. This may be a file in which case the result list contains only
   *     this path or the empty list if the path is not monitored by the cache.
   *     <p>is recursively monitoring the input path, it will not return cache entries for children
   *     if this flag is false.
   * @param maxDepth The maximum depth of children of the parent to traverse in the tree.
   * @return The list of cache elements. This will be empty if the path is not monitored in a
   *     monitored path. If the path is a file and the file is monitored by the cache, the returned
   *     list will contain just the cache entry for the path.
   */
  @SuppressWarnings("unused")
  public List<Directory.Entry<T>> list(final Path path, final int maxDepth) {
    return list(path, maxDepth, AllPass);
  }
  /**
   * Lists the cache elements in the particular path without any filtering
   *
   * @param path The path to list. This may be a file in which case the result list contains only
   *     this path or the empty list if the path is not monitored by the cache.
   *     <p>is recursively monitoring the input path, it will not return cache entries for children
   *     if this flag is false.
   * @param recursive Toggles whether or not to traverse the children of the path
   * @return The list of cache elements. This will be empty if the path is not monitored in a
   *     monitored path. If the path is a file and the file is monitored by the cache, the returned
   *     list will contain just the cache entry for the path.
   */
  @SuppressWarnings("unused")
  public List<Directory.Entry<T>> list(final Path path, final boolean recursive) {
    return list(path, recursive, AllPass);
  }

  /**
   * Lists the cache elements in the particular path recursively and with no filter.
   *
   * @param path The path to list. This may be a file in which case the result list contains only
   *     this path or the empty list if the path is not monitored by the cache.
   * @return The list of cache elements. This will be empty if the path is not monitored in a
   *     monitored path. If the path is a file and the file is monitored by the cache, the returned
   *     list will contain just the cache entry for the path.
   */
  @SuppressWarnings("unused")
  public List<Directory.Entry<T>> list(final Path path) {
    return list(path, Integer.MAX_VALUE, AllPass);
  }
  /**
   * Register the directory for monitoring.
   *
   * @param path The path to monitor
   * @param maxDepth The maximum depth of subdirectories to include
   * @return an instance of {@link com.swoval.functional.Either} that contains a boolean flag
   *     indicating whether registration succeeds. If an IOException is thrown registering the
   *     directory, it is returned as a {@link com.swoval.functional.Either.Left}. This method
   *     should be idempotent and returns false if the call was a no-op.
   */
  public abstract Either<IOException, Boolean> register(final Path path, final int maxDepth);
  /**
   * Register the directory for monitoring.
   *
   * @param path The path to monitor
   * @param recursive Recursively monitor the path if true
   * @return an instance of {@link com.swoval.functional.Either} that contains a boolean flag
   *     indicating whether registration succeeds. If an IOException is thrown registering the
   *     directory, it is returned as a {@link com.swoval.functional.Either.Left}. This method
   *     should be idempotent and returns false if the call was a no-op.
   */
  public Either<IOException, Boolean> register(final Path path, final boolean recursive) {
    return register(path, recursive ? Integer.MAX_VALUE : 0);
  }

  /**
   * Register the directory for monitoring recursively.
   *
   * @param path The path to monitor
   * @return an instance of {@link com.swoval.functional.Either} that contains a boolean flag
   *     indicating whether registration succeeds. If an IOException is thrown registering the
   *     directory, it is returned as a {@link com.swoval.functional.Either.Left}. This method
   *     should be idempotent and returns false if the call was a no-op.
   */
  public Either<IOException, Boolean> register(final Path path) {
    return register(path, Integer.MAX_VALUE);
  }

  /** Handle all exceptions in close. */
  @Override
  public void close() {}

  /**
   * Create a file cache
   *
   * @param converter Converts a path to the cached value type T
   * @param options Options for the cache.
   * @param <T> The value type of the cache entries
   * @return A file cache
   * @throws IOException if the {@link PathWatcher} cannot be initialized
   * @throws InterruptedException if the {@link PathWatcher} cannot be initialized
   */
  public static <T> FileCache<T> apply(
      final Converter<T> converter, final FileCache.Option... options)
      throws IOException, InterruptedException {
    return new FileCacheImpl<>(converter, DEFAULT_FACTORY, null, options);
  }

  /**
   * Create a file cache using a specific PathWatcher created by the provided factory
   *
   * @param converter Converts a path to the cached value type T
   * @param factory A factory to create a directory watcher
   * @param options Options for the cache
   * @param <T> The value type of the cache entries
   * @return A file cache
   * @throws IOException if the {@link PathWatcher} cannot be initialized
   * @throws InterruptedException if the {@link PathWatcher} cannot be initialized
   */
  public static <T> FileCache<T> apply(
      final Converter<T> converter,
      final PathWatcher.Factory factory,
      final FileCache.Option... options)
      throws IOException, InterruptedException {
    return new FileCacheImpl<>(converter, factory, null, options);
  }

  /**
   * Create a file cache with an Observer of events
   *
   * @param converter Converts a path to the cached value type T
   * @param observer Observer of events for this cache
   * @param options Options for the cache
   * @param <T> The value type of the cache entries
   * @return A file cache
   * @throws IOException if the {@link PathWatcher} cannot be initialized
   * @throws InterruptedException if the {@link PathWatcher} cannot be initialized
   */
  public static <T> FileCache<T> apply(
      final Converter<T> converter, final Observer<T> observer, final FileCache.Option... options)
      throws IOException, InterruptedException {
    FileCache<T> res = new FileCacheImpl<>(converter, DEFAULT_FACTORY, null, options);
    res.addObserver(observer);
    return res;
  }

  /**
   * Create a file cache with an Observer of events
   *
   * @param converter Converts a path to the cached value type T
   * @param factory A factory to create a directory watcher
   * @param observer Observer of events for this cache
   * @param options Options for the cache
   * @param <T> The value type of the cache entries
   * @return A file cache
   * @throws IOException if the {@link PathWatcher} cannot be initialized
   * @throws InterruptedException if the {@link PathWatcher} cannot be initialized
   */
  public static <T> FileCache<T> apply(
      final Converter<T> converter,
      final PathWatcher.Factory factory,
      final Observer<T> observer,
      final FileCache.Option... options)
      throws IOException, InterruptedException {
    FileCache<T> res = new FileCacheImpl<>(converter, factory, null, options);
    res.addObserver(observer);
    return res;
  }

  /** Options for the implementation of a {@link FileCache} */
  public static class Option {
    /** This constructor is needed for code gen. Otherwise only the companion is generated */
    Option() {}
    /**
     * When the FileCache encounters a symbolic link with a directory as target, treat the symbolic
     * link like a directory. Note that it is possible to create a loop if two directories mutually
     * link to each other symbolically. When this happens, the FileCache will throw a {@link
     * java.nio.file.FileSystemLoopException} when attempting to register one of these directories
     * or if the link that completes the loop is added to a registered directory.
     */
    public static final FileCache.Option NOFOLLOW_LINKS = new Option();
  }
}

class FileCacheImpl<T> extends FileCache<T> {
  private final Map<Path, Directory<T>> directories = new HashMap<>();
  private final Set<Path> pendingFiles = new HashSet<>();
  private final Converter<T> converter;
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final Executor internalExecutor;
  private final Executor callbackExecutor =
      Executor.make("com.swoval.files.FileCache-callback-executor");
  private final SymlinkWatcher symlinkWatcher;
  private final DirectoryRegistry registry = new DirectoryRegistry();

  private Consumer<Event> callback(final Executor executor) {
    return new Consumer<Event>() {
      @SuppressWarnings("unchecked")
      @Override
      public void accept(final PathWatcher.Event event) {
        executor.run(
            new Runnable() {
              @Override
              public void run() {
                final Path path = event.path;
                if (event.kind.equals(Overflow)) {
                  handleOverflow(path);
                } else {
                  handleEvent(path);
                }
              }
            });
      }
    };
  }

  private final PathWatcher watcher;

  FileCacheImpl(
      final Converter<T> converter,
      final PathWatcher.Factory factory,
      final Executor executor,
      FileCache.Option... options)
      throws InterruptedException, IOException {
    ShutdownHooks.addHook(
        1,
        new Runnable() {
          @Override
          public void run() {
            close();
          }
        });
    this.internalExecutor =
        executor == null
            ? Executor.make("com.swoval.files.FileCache-callback-internalExecutor")
            : executor;
    this.watcher =
        factory.create(
            callback(this.internalExecutor.copy()), this.internalExecutor.copy(), registry);
    this.converter = converter;
    symlinkWatcher =
        !ArrayOps.contains(options, FileCache.Option.NOFOLLOW_LINKS)
            ? new SymlinkWatcher(
                new Consumer<Path>() {
                  @Override
                  public void accept(Path path) {
                    handleEvent(path);
                  }
                },
                factory,
                new OnError() {
                  @Override
                  public void apply(final Path symlink, final IOException exception) {
                    observers.onError(symlink, exception);
                  }
                },
                this.internalExecutor.copy())
            : null;
  }

  /** Cleans up the directory watcher and clears the directory cache. */
  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      if (symlinkWatcher != null) symlinkWatcher.close();
      watcher.close();
      final Iterator<Directory<T>> directoryIterator = directories.values().iterator();
      while (directoryIterator.hasNext()) {
        directoryIterator.next().close();
      }
      directories.clear();
      internalExecutor.close();
      callbackExecutor.close();
    }
  }

  @Override
  public List<Directory.Entry<T>> list(
      final Path path, final int maxDepth, final Directory.EntryFilter<? super T> filter) {
    return internalExecutor
        .block(
            new Callable<List<Directory.Entry<T>>>() {
              @Override
              public List<Directory.Entry<T>> call() {
                final Directory<T> dir = find(path);
                if (dir == null) {
                  return new ArrayList<>();
                } else {
                  if (dir.path.equals(path) && dir.getDepth() == -1) {
                    List<Directory.Entry<T>> result = new ArrayList<>();
                    result.add(dir.entry());
                    return result;
                  } else {
                    return dir.list(path, maxDepth, filter);
                  }
                }
              }
            })
        .get();
  }

  @Override
  public Either<IOException, Boolean> register(final Path path, final int maxDepth) {
    Either<IOException, Boolean> result = watcher.register(path, maxDepth);
    if (result.isRight()) {
      result =
          internalExecutor
              .block(
                  new Callable<Boolean>() {
                    @Override
                    public Boolean call() throws IOException {
                      return doReg(path, maxDepth);
                    }
                  })
              .castLeft(IOException.class);
    }
    return result;
  }

  private boolean doReg(final Path path, final int maxDepth) throws IOException {
    boolean result = false;
    registry.addDirectory(path, maxDepth);
    final List<Directory<T>> dirs = new ArrayList<>(directories.values());
    Collections.sort(
        dirs,
        new Comparator<Directory<T>>() {
          @Override
          public int compare(Directory<T> left, Directory<T> right) {
            return left.path.compareTo(right.path);
          }
        });
    final Iterator<Directory<T>> it = dirs.iterator();
    Directory<T> existing = null;
    while (it.hasNext() && existing == null) {
      final Directory<T> dir = it.next();
      if (path.startsWith(dir.path)) {
        final int depth =
            path.equals(dir.path) ? 0 : (dir.path.relativize(path).getNameCount() - 1);
        if (dir.getDepth() == Integer.MAX_VALUE || maxDepth < dir.getDepth() - depth) {
          existing = dir;
        } else if (depth <= dir.getDepth()) {
          result = true;
          dir.close();
          try {
            existing =
                Directory.cached(
                    dir.path,
                    converter,
                    maxDepth < Integer.MAX_VALUE - depth - 1
                        ? maxDepth + depth + 1
                        : Integer.MAX_VALUE);
            directories.put(dir.path, existing);
          } catch (IOException e) {
            existing = null;
          }
        }
      }
    }
    if (existing == null) {
      try {
        Directory<T> dir;
        try {
          dir = Directory.cached(path, converter, maxDepth);
        } catch (final NotDirectoryException e) {
          dir = Directory.cached(path, converter, -1);
        }
        directories.put(path, dir);
        final Iterator<Directory.Entry<T>> entryIterator =
            dir.list(true, EntryFilters.AllPass).iterator();
        if (symlinkWatcher != null) {
          while (entryIterator.hasNext()) {
            final Directory.Entry<T> entry = entryIterator.next();
            if (entry.isSymbolicLink()) {
              symlinkWatcher.addSymlink(entry.path, entry.isDirectory(), maxDepth - 1);
            }
          }
        }
        result = true;
      } catch (final NoSuchFileException e) {
        result = pendingFiles.add(path);
      }
    }
    return result;
  }

  private Directory<T> find(final Path path) {
    Directory<T> foundDir = null;
    final Iterator<Directory<T>> it = directories.values().iterator();
    while (it.hasNext()) {
      final Directory<T> dir = it.next();
      if (path.startsWith(dir.path) && (foundDir == null || dir.path.startsWith(foundDir.path))) {
        foundDir = dir;
      }
    }
    return foundDir;
  }

  private boolean diff(Directory<T> left, Directory<T> right) {
    List<Directory.Entry<T>> oldEntries = left.list(left.recursive(), AllPass);
    Set<Path> oldPaths = new HashSet<>();
    final Iterator<Directory.Entry<T>> oldEntryIterator = oldEntries.iterator();
    while (oldEntryIterator.hasNext()) {
      oldPaths.add(oldEntryIterator.next().path);
    }
    List<Directory.Entry<T>> newEntries = right.list(left.recursive(), AllPass);
    Set<Path> newPaths = new HashSet<>();
    final Iterator<Directory.Entry<T>> newEntryIterator = newEntries.iterator();
    while (newEntryIterator.hasNext()) {
      newPaths.add(newEntryIterator.next().path);
    }
    boolean result = oldPaths.size() != newPaths.size();
    final Iterator<Path> oldIterator = oldPaths.iterator();
    while (oldIterator.hasNext() && !result) {
      if (newPaths.add(oldIterator.next())) result = true;
    }
    final Iterator<Path> newIterator = newPaths.iterator();
    while (newIterator.hasNext() && !result) {
      if (oldPaths.add(newIterator.next())) result = true;
    }
    return result;
  }

  @SuppressWarnings("EmptyCatchBlock")
  private Directory<T> cachedOrNull(final Path path, final int maxDepth) {
    Directory<T> res = null;
    try {
      res = Directory.cached(path, converter, maxDepth);
    } catch (IOException e) {
    }
    return res;
  }

  @SuppressWarnings("unchecked")
  private void handleOverflow(final Path path) {
    if (!closed.get()) {
      final Iterator<Directory<T>> directoryIterator = directories.values().iterator();
      final List<Directory<T>> toReplace = new ArrayList<>();
      final List<Directory.Entry<T>> creations = new ArrayList<>();
      final List<Directory.Entry<T>[]> updates = new ArrayList<>();
      final List<Directory.Entry<T>> deletions = new ArrayList<>();
      while (directoryIterator.hasNext()) {
        final Directory<T> currentDir = directoryIterator.next();
        if (path.startsWith(currentDir.path)) {
          Directory<T> oldDir = currentDir;
          Directory<T> newDir = cachedOrNull(oldDir.path, oldDir.getDepth());
          while (newDir == null || diff(oldDir, newDir)) {
            if (newDir != null) oldDir = newDir;
            newDir = cachedOrNull(oldDir.path, oldDir.getDepth());
          }
          final Map<Path, Directory.Entry<T>> oldEntries = new HashMap<>();
          final Map<Path, Directory.Entry<T>> newEntries = new HashMap<>();
          final Iterator<Directory.Entry<T>> oldEntryIterator =
              currentDir.list(currentDir.recursive(), AllPass).iterator();
          while (oldEntryIterator.hasNext()) {
            final Directory.Entry<T> entry = oldEntryIterator.next();
            oldEntries.put(entry.path, entry);
          }
          final Iterator<Directory.Entry<T>> newEntryIterator =
              newDir.list(currentDir.recursive(), AllPass).iterator();
          while (newEntryIterator.hasNext()) {
            final Directory.Entry<T> entry = newEntryIterator.next();
            newEntries.put(entry.path, entry);
          }
          final Iterator<Entry<Path, Directory.Entry<T>>> oldIterator =
              oldEntries.entrySet().iterator();
          while (oldIterator.hasNext()) {
            final Entry<Path, Directory.Entry<T>> mapEntry = oldIterator.next();
            if (!newEntries.containsKey(mapEntry.getKey())) {
              deletions.add(mapEntry.getValue());
            }
          }
          final Iterator<Entry<Path, Directory.Entry<T>>> newIterator =
              newEntries.entrySet().iterator();
          while (newIterator.hasNext()) {
            final Entry<Path, Directory.Entry<T>> mapEntry = newIterator.next();
            final Directory.Entry<T> oldEntry = oldEntries.get(mapEntry.getKey());
            if (oldEntry == null) {
              creations.add(mapEntry.getValue());
            } else if (!oldEntry.equals(mapEntry.getValue())) {
              updates.add(new Directory.Entry[] {oldEntry, mapEntry.getValue()});
            }
          }
          toReplace.add(newDir);
        }
      }
      final Iterator<Directory<T>> replacements = toReplace.iterator();
      while (replacements.hasNext()) {
        final Directory<T> replacement = replacements.next();
        directories.put(replacement.path, replacement);
      }
      callbackExecutor.run(
          new Runnable() {
            @Override
            public void run() {
              final Iterator<Directory.Entry<T>> creationIterator = creations.iterator();
              while (creationIterator.hasNext()) observers.onCreate(creationIterator.next());
              final Iterator<Directory.Entry<T>> deletionIterator = deletions.iterator();
              while (deletionIterator.hasNext()) observers.onDelete(deletionIterator.next());
              final Iterator<Directory.Entry<T>[]> updateIterator = updates.iterator();
              while (updateIterator.hasNext()) {
                final Directory.Entry<T>[] update = updateIterator.next();
                observers.onUpdate(update[0], update[1]);
              }
            }
          });
    }
  }

  private abstract class Callback implements Runnable, Comparable<Callback> {
    private final Kind kind;
    private final Path path;

    Callback(final Path path, final Kind kind) {
      this.kind = kind;
      this.path = path;
    }

    @Override
    public int compareTo(final Callback that) {
      final int kindComparision = this.kind.compareTo(that.kind);
      return kindComparision == 0 ? this.path.compareTo(that.path) : kindComparision;
    }
  }

  private void addCallback(
      final List<Callback> callbacks,
      final Path path,
      final Directory.Entry<T> oldEntry,
      final Directory.Entry<T> newEntry,
      final Kind kind,
      final IOException ioException) {
    callbacks.add(
        new Callback(path, kind) {
          @Override
          public void run() {
            if (ioException != null) {
              observers.onError(path, ioException);
            } else if (kind.equals(Create)) {
              observers.onCreate(newEntry);
            } else if (kind.equals(Delete)) {
              observers.onDelete(oldEntry);
            } else if (kind.equals(Modify)) {
              observers.onUpdate(oldEntry, newEntry);
            }
          }
        });
  }

  @SuppressWarnings("EmptyCatchBlock")
  private void handleEvent(final Path path) {
    if (!closed.get()) {
      BasicFileAttributes attrs = null;
      final List<Callback> callbacks = new ArrayList<>();
      try {
        attrs = NioWrappers.readAttributes(path, LinkOption.NOFOLLOW_LINKS);
      } catch (IOException e) {
      }
      if (attrs != null) {
        final Directory<T> dir = find(path);
        if (dir != null) {
          final List<Directory.Entry<T>> paths =
              dir.list(
                  path,
                  0,
                  new Directory.EntryFilter<T>() {
                    @Override
                    public boolean accept(Directory.Entry<? extends T> entry) {
                      return path.equals(entry.path);
                    }
                  });
          if (!paths.isEmpty() || !path.equals(dir.path)) {
            final Path toUpdate = paths.isEmpty() ? path : paths.get(0).path;
            try {
              if (attrs.isSymbolicLink() && symlinkWatcher != null)
                symlinkWatcher.addSymlink(
                    path,
                    Files.isDirectory(path),
                    dir.getDepth() == Integer.MAX_VALUE ? Integer.MAX_VALUE : dir.getDepth() - 1);
              final Directory.Updates<T> updates =
                  dir.update(toUpdate, Directory.Entry.getKind(toUpdate, attrs));
              updates.observe(callbackObserver(callbacks));
            } catch (final IOException e) {
              addCallback(callbacks, path, null, null, Event.Error, e);
            }
          }
        } else if (pendingFiles.remove(path)) {
          try {
            Directory<T> directory;
            try {
              directory = Directory.cached(path, converter, registry.maxDepthFor(path));
            } catch (final NotDirectoryException nde) {
              directory = Directory.cached(path, converter, -1);
            }
            directories.put(path, directory);
            addCallback(callbacks, path, null, directory.entry(), Create, null);
            final Iterator<Directory.Entry<T>> it = directory.list(true, AllPass).iterator();
            while (it.hasNext()) {
              final Directory.Entry<T> entry = it.next();
              addCallback(callbacks, entry.path, null, entry, Create, null);
            }
          } catch (final IOException e) {
            pendingFiles.add(path);
          }
        }
      } else {
        final List<Iterator<Directory.Entry<T>>> removeIterators = new ArrayList<>();
        final Iterator<Directory<T>> directoryIterator =
            new ArrayList<>(directories.values()).iterator();
        while (directoryIterator.hasNext()) {
          final Directory<T> dir = directoryIterator.next();
          if (path.startsWith(dir.path)) {
            List<Directory.Entry<T>> updates = dir.remove(path);
            if (dir.path.equals(path)) {
              pendingFiles.add(path);
              updates.add(dir.entry());
              directories.remove(path);
            }
            removeIterators.add(updates.iterator());
          }
        }
        final Iterator<Iterator<Directory.Entry<T>>> it = removeIterators.iterator();
        while (it.hasNext()) {
          final Iterator<Directory.Entry<T>> removeIterator = it.next();
          while (removeIterator.hasNext()) {
            final Directory.Entry<T> entry = removeIterator.next();
            addCallback(callbacks, entry.path, entry, null, Delete, null);
            if (symlinkWatcher != null) {
              symlinkWatcher.remove(entry.path);
            }
          }
        }
      }
      if (!callbacks.isEmpty()) {
        callbackExecutor.run(
            new Runnable() {
              @Override
              public void run() {
                Collections.sort(callbacks);
                final Iterator<Callback> callbackIterator = callbacks.iterator();
                while (callbackIterator.hasNext()) {
                  callbackIterator.next().run();
                }
              }
            });
      }
    }
  }

  private Observer<T> callbackObserver(final List<Callback> callbacks) {
    return new Observer<T>() {
      @Override
      public void onCreate(final Directory.Entry<T> newEntry) {
        addCallback(callbacks, newEntry.path, null, newEntry, Create, null);
      }

      @Override
      public void onDelete(final Directory.Entry<T> oldEntry) {
        addCallback(callbacks, oldEntry.path, oldEntry, null, Delete, null);
      }

      @Override
      public void onUpdate(final Directory.Entry<T> oldEntry, final Directory.Entry<T> newEntry) {
        addCallback(callbacks, oldEntry.path, oldEntry, newEntry, Modify, null);
      }

      @Override
      public void onError(final Path path, final IOException exception) {
        addCallback(callbacks, path, null, null, Event.Error, exception);
      }
    };
  }
}
