package com.swoval.files;

import static com.swoval.files.DirectoryWatcher.DEFAULT_FACTORY;
import static com.swoval.files.DirectoryWatcher.Event.Overflow;
import static com.swoval.files.EntryFilters.AllPass;

import com.swoval.concurrent.Lock;
import com.swoval.files.Directory.Converter;
import com.swoval.files.Directory.Observer;
import com.swoval.files.Directory.OnChange;
import com.swoval.files.SymlinkWatcher.OnLoop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Provides an in memory cache of portions of the file system. Directories are added to the cache
 * using the {@link FileCache#register(Path, boolean)} method. Once a directory is added the cache,
 * its contents may be retrieved using the {@link FileCache#list(Path, boolean,
 * Directory.EntryFilter)} method. The cache stores the path information in {@link Directory.Entry}
 * instances.
 *
 * <p>A default implementation is provided by {@link FileCache#apply(Converter, Observer,
 * Option...)} . The user may cache arbitrary information in the cache by customizing the {@link
 * Directory.Converter} that is passed into the factory {@link FileCache#apply(Converter, Observer,
 * Option...)}
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
   * @param recursive Toggles whether or not to include paths in subdirectories. Even when the cache
   *     is recursively monitoring the input path, it will not return cache entries for children if
   *     this flag is false.
   * @param filter Only include cache entries that are accepted by the filter.
   * @return The list of cache elements. This will be empty if the path is not monitored in a
   *     monitored path. If the path is a file and the file is monitored by the cache, the returned
   *     list will contain just the cache entry for the path.
   */
  public abstract List<Directory.Entry<T>> list(
      final Path path, final boolean recursive, final Directory.EntryFilter<? super T> filter);

  /**
   * Lists the cache elements in the particular path without any filtering
   *
   * @param path The path to list. This may be a file in which case the result list contains only
   *     this path or the empty list if the path is not monitored by the cache.
   *     <p>is recursively monitoring the input path, it will not return cache entries for children
   *     if this flag is false.
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
    return list(path, true, AllPass);
  }
  /**
   * Register the directory for monitoring.
   *
   * @param path The path to monitor
   * @param maxDepth The maximum depth of subdirectories to include
   * @return The registered directory if it hasn't previously been registered, null otherwise
   */
  public abstract Directory<T> register(final Path path, final int maxDepth) throws IOException;
  /**
   * Register the directory for monitoring.
   *
   * @param path The path to monitor
   * @param recursive Recursively monitor the path if true
   * @return The registered directory if it hasn't previously been registered, null otherwise
   */
  public Directory<T> register(final Path path, final boolean recursive) throws IOException {
    return register(path, recursive ? Integer.MAX_VALUE : 0);
  }

  /**
   * Register the directory for monitoring recursively.
   *
   * @param path The path to monitor
   * @return The registered directory if it hasn't previously been registered, null otherwise
   */
  public Directory<T> register(final Path path) throws IOException {
    return register(path, Integer.MAX_VALUE);
  }

  /** Handle all exceptions in close. */
  @Override
  public void close() {}

  /**
   * Create a file cache
   *
   * @param converter Converts a path to the cached value type T
   * @param <T> The value type of the cache entries
   * @return A file cache
   * @throws IOException if the {@link DirectoryWatcher} cannot be initialized
   * @throws InterruptedException if the {@link DirectoryWatcher} cannot be initialized
   */
  public static <T> FileCache<T> apply(
      final Converter<T> converter, final FileCache.Option... options)
      throws IOException, InterruptedException {
    return new FileCacheImpl<>(converter, DEFAULT_FACTORY, options);
  }

  /**
   * Create a file cache using a specific DirectoryWatcher created by the provided factory
   *
   * @param converter Converts a path to the cached value type T
   * @param factory A factory to create a directory watcher
   * @param <T> The value type of the cache entries
   * @return A file cache
   * @throws IOException if the {@link DirectoryWatcher} cannot be initialized
   * @throws InterruptedException if the {@link DirectoryWatcher} cannot be initialized
   */
  public static <T> FileCache<T> apply(
      final Converter<T> converter,
      final DirectoryWatcher.Factory factory,
      final FileCache.Option... options)
      throws IOException, InterruptedException {
    return new FileCacheImpl<>(converter, factory, options);
  }

  /**
   * Create a file cache with an Observer of events
   *
   * @param converter Converts a path to the cached value type T
   * @param observer Observer of events for this cache
   * @param <T> The value type of the cache entries
   * @return A file cache
   * @throws IOException if the {@link DirectoryWatcher} cannot be initialized
   * @throws InterruptedException if the {@link DirectoryWatcher} cannot be initialized
   */
  public static <T> FileCache<T> apply(
      final Converter<T> converter, final Observer<T> observer, final FileCache.Option... options)
      throws IOException, InterruptedException {
    FileCache<T> res = new FileCacheImpl<>(converter, DEFAULT_FACTORY, options);
    res.addObserver(observer);
    return res;
  }

  /**
   * Create a file cache with an Observer of events
   *
   * @param converter Converts a path to the cached value type T
   * @param factory A factory to create a directory watcher
   * @param observer Observer of events for this cache
   * @param <T> The value type of the cache entries
   * @return A file cache
   * @throws IOException if the {@link DirectoryWatcher} cannot be initialized
   * @throws InterruptedException if the {@link DirectoryWatcher} cannot be initialized
   */
  public static <T> FileCache<T> apply(
      final Converter<T> converter,
      final DirectoryWatcher.Factory factory,
      final Observer<T> observer,
      final FileCache.Option... options)
      throws IOException, InterruptedException {
    FileCache<T> res = new FileCacheImpl<>(converter, factory, options);
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
  private final Converter<T> converter;
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final Lock lock = new Lock();
  private final SymlinkWatcher symlinkWatcher;

  private final DirectoryWatcher.Callback callback =
      new DirectoryWatcher.Callback() {
        @SuppressWarnings("unchecked")
        @Override
        public void apply(final DirectoryWatcher.Event event) {
          if (lock.lock()) {
            try {
              final Path path = event.path;
              if (event.kind.equals(Overflow)) {
                handleOverflow(path);
              } else {
                handleEvent(path);
              }
            } finally {
              lock.unlock();
            }
          }
        }
      };
  private final DirectoryWatcher watcher;

  FileCacheImpl(
      final Converter<T> converter, DirectoryWatcher.Factory factory, FileCache.Option... options)
      throws InterruptedException, IOException {
    this.watcher = factory.create(callback);
    this.converter = converter;
    symlinkWatcher =
        !ArrayOps.contains(options, FileCache.Option.NOFOLLOW_LINKS)
            ? new SymlinkWatcher(
                new SymlinkWatcher.EventConsumer() {
                  @Override
                  public void accept(Path path) {
                    handleEvent(path);
                  }
                },
                factory,
                new OnLoop() {
                  @Override
                  public void accept(final Path symlink, final IOException exception) {
                    observers.onError(symlink, exception);
                  }
                })
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
    }
  }

  @Override
  public List<Directory.Entry<T>> list(
      final Path path, final boolean recursive, final Directory.EntryFilter<? super T> filter) {
    Pair<Directory<T>, List<Directory.Entry<T>>> pair =
        listImpl(path, recursive ? Integer.MAX_VALUE : 0, filter);
    return pair == null ? new ArrayList<Directory.Entry<T>>() : pair.second;
  }

  @Override
  public Directory<T> register(final Path path, final int maxDepth) throws IOException {
    Directory<T> result = null;
    if (Files.exists(path)) {
      watcher.register(path, maxDepth);
      if (lock.lock()) {
        try {
          final List<Directory<T>> dirs = new ArrayList<>(directories.values());
          Collections.sort(
              new ArrayList<>(directories.values()),
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
                dir.close();
                existing =
                    Directory.cached(
                        dir.path,
                        converter,
                        maxDepth < Integer.MAX_VALUE - depth - 1
                            ? maxDepth + depth + 1
                            : Integer.MAX_VALUE);
                directories.put(dir.path, existing);
              }
            }
          }
          if (existing == null) {
            result = Directory.cached(path, converter, maxDepth);
            directories.put(path, result);
            final Iterator<Directory.Entry<T>> entryIterator =
                result.list(true, EntryFilters.AllPass).iterator();
            if (symlinkWatcher != null) {
              while (entryIterator.hasNext()) {
                final Directory.Entry<T> entry = entryIterator.next();
                if (entry.isSymbolicLink()) {
                  symlinkWatcher.addSymlink(entry.path, entry.isDirectory());
                }
              }
            }
          }
        } finally {
          lock.unlock();
        }
      }
    }
    return result;
  }

  private Pair<Directory<T>, List<Directory.Entry<T>>> listImpl(
      final Path path, final int maxDepth, final Directory.EntryFilter<? super T> filter) {
    lock.lock();
    try {
      Directory<T> foundDir = null;
      final Iterator<Directory<T>> it = directories.values().iterator();
      while (it.hasNext()) {
        final Directory<T> dir = it.next();
        if (path.startsWith(dir.path) && (foundDir == null || dir.path.startsWith(foundDir.path))) {
          foundDir = dir;
        }
      }
      if (foundDir != null) {
        return new Pair<>(foundDir, foundDir.list(path, maxDepth, filter));
      } else {
        return null;
      }
    } finally {
      lock.unlock();
    }
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
    } finally {
      lock.unlock();
    }
    return res;
  }

  @SuppressWarnings("unchecked")
  private boolean handleOverflow(final Path path) {
    lock.lock();
    try {
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
              if (!oldEntries.containsKey(mapEntry.getKey())) {
                creations.add(mapEntry.getValue());
              } else {
                final Directory.Entry<T> oldEntry = oldEntries.get(mapEntry.getKey());
                if (!oldEntry.equals(mapEntry.getValue())) {}
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
        final Iterator<Directory.Entry<T>> creationIterator = creations.iterator();
        while (creationIterator.hasNext()) observers.onCreate(creationIterator.next());
        final Iterator<Directory.Entry<T>> deletionIterator = deletions.iterator();
        while (deletionIterator.hasNext()) observers.onDelete(deletionIterator.next());
        final Iterator<Directory.Entry<T>[]> updateIterator = updates.iterator();
        while (updateIterator.hasNext()) {
          final Directory.Entry<T>[] update = updateIterator.next();
          observers.onUpdate(update[0], update[1]);
        }
        return creations.isEmpty() && deletions.isEmpty() && updates.isEmpty();
      } else {
        return false;
      }
    } finally {
      lock.unlock();
    }
  }

  @SuppressWarnings("EmptyCatchBlock")
  private void handleEvent(final Path path) {
    if (!closed.get()) {
      BasicFileAttributes attrs = null;
      try {
        attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      } catch (IOException e) {
      }
      if (attrs != null) {
        final Pair<Directory<T>, List<Directory.Entry<T>>> pair =
            listImpl(
                path,
                0,
                new Directory.EntryFilter<T>() {
                  @Override
                  public boolean accept(Directory.Entry<? extends T> entry) {
                    return path.equals(entry.path);
                  }
                });
        if (pair != null) {
          final Directory<T> dir = pair.first;
          final List<Directory.Entry<T>> paths = pair.second;
          if (!paths.isEmpty() || !path.equals(dir.path)) {
            final Path toUpdate = paths.isEmpty() ? path : paths.get(0).path;
            if (attrs.isSymbolicLink() && symlinkWatcher != null)
              symlinkWatcher.addSymlink(path, Files.isDirectory(path));
            try {
              final Iterator<Directory.Entry<T>[]> it =
                  dir.addUpdate(toUpdate, Directory.Entry.getKind(toUpdate, attrs)).iterator();
              while (it.hasNext()) {
                final Directory.Entry<T>[] entry = it.next();
                if (entry.length == 2) {
                  observers.onUpdate(entry[0], entry[1]);
                } else {
                  observers.onCreate(entry[0]);
                }
              }
            } catch (IOException e) {
              observers.onError(path, e);
            }
          }
        }
      } else {
        final List<Iterator<Directory.Entry<T>>> removeIterators = new ArrayList<>();
        lock.lock();
        try {
          final Iterator<Directory<T>> it = directories.values().iterator();
          while (it.hasNext()) {
            final Directory<T> dir = it.next();
            if (path.startsWith(dir.path)) {
              removeIterators.add(dir.remove(path).iterator());
            }
          }
        } finally {
          lock.unlock();
        }
        final Iterator<Iterator<Directory.Entry<T>>> it = removeIterators.iterator();
        while (it.hasNext()) {
          final Iterator<Directory.Entry<T>> removeIterator = it.next();
          while (removeIterator.hasNext()) {
            final Directory.Entry<T> entry = removeIterator.next();
            observers.onDelete(entry);
            if (symlinkWatcher != null) {
              symlinkWatcher.remove(entry.path);
            }
          }
        }
      }
    }
  }

  private static class Pair<A, B> {
    final A first;
    final B second;

    Pair(A first, B second) {
      this.first = first;
      this.second = second;
    }

    @Override
    public String toString() {
      return "Pair(" + first + ", " + second + ")";
    }
  }
}
