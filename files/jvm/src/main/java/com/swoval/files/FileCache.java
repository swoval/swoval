package com.swoval.files;

import static com.swoval.files.EntryFilters.AllPass;

import com.swoval.files.Directory.Converter;
import com.swoval.files.Directory.Entry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Provides an in memory cache of portions of the file system. Directories are added to the cache
 * using the {@link FileCache#register(Path, boolean)} method. Once a directory is added the cache,
 * its contents may be retrieved using the {@link FileCache#list(Path, boolean, EntryFilter)}
 * method. The cache stores the path information in {@link Directory.Entry} instances.
 *
 * <p>A default implementation is provided by {@link FileCache#apply(Directory.Converter,
 * Observer)}. The user may cache arbitrary information in the cache by customizing the {@link
 * Directory.Converter} that is passed into the factory {@link FileCache#apply(Directory.Converter,
 * FileCache.Observer)}.
 *
 * @param <T> The type of data stored in the {@link Directory.Entry} instances for the cache
 */
public abstract class FileCache<T> implements AutoCloseable {
  protected final Observers<T> observers = new Observers<>();

  /**
   * Callback to fire when a file in a monitored directory is created or deleted
   *
   * @param <T> The cached value associated with the path
   */
  public interface OnChange<T> {
    void apply(Entry<T> entry);
  }

  /**
   * Callback to fire when a file in a monitor is updated
   *
   * @param <T> The cached value associated with the path
   */
  public interface OnUpdate<T> {
    void apply(Entry<T> oldEntry, Entry<T> newEntry);
  }

  /**
   * Provides callbacks to run when different types of file events are detected by the cache
   *
   * @param <T> The type for the {@link Directory.Entry} data
   */
  public interface Observer<T> {
    void onCreate(Entry<T> newEntry);

    void onDelete(Entry<T> oldEntry);

    void onUpdate(Entry<T> oldEntry, Entry<T> newEntry);
  }

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
          public void onCreate(Entry<T> newEntry) {
            onChange.apply(newEntry);
          }

          @Override
          public void onDelete(Entry<T> oldEntry) {
            onChange.apply(oldEntry);
          }

          @Override
          public void onUpdate(Entry<T> oldEntry, Entry<T> newEntry) {
            onChange.apply(newEntry);
          }
        });
  }

  /**
   * Stop firing the previously registered callback where <code>handle</code> is returned by {@link
   * #addObserver(Observer)}
   *
   * @param handle A handle to the observer added by {@link #addObserver(Observer)}
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
  public abstract List<Entry<T>> list(
      final Path path, final boolean recursive, final EntryFilter<? super T> filter);

  /**
   * Lists the cache elements in the particular path without any filtering
   *
   * @param path The path to list. This may be a file in which case the result list contains only
   *     this path or the empty list if the path is not monitored by the cache.
   * @param recursive Toggles whether or not to include paths in subdirectories. Even when the cache
   *     is recursively monitoring the input path, it will not return cache entries for children if
   *     this flag is false.
   * @return The list of cache elements. This will be empty if the path is not monitored in a
   *     monitored path. If the path is a file and the file is monitored by the cache, the returned
   *     list will contain just the cache entry for the path.
   */
  public List<Entry<T>> list(final Path path, final boolean recursive) {
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
  public List<Entry<T>> list(final Path path) {
    return list(path, true, AllPass);
  }
  /**
   * Register the directory for monitoring.
   *
   * @param path The path to monitor
   * @param recursive Recursively monitor the path if true
   * @return The registered directory if it hasn't previously been registered, null otherwise
   */
  public abstract Directory<T> register(final Path path, final boolean recursive);

  /**
   * Register the directory for monitoring recursively.
   *
   * @param path The path to monitor
   * @return The registered directory if it hasn't previously been registered, null otherwise
   */
  public Directory<T> register(final Path path) {
    return register(path, true);
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
  public static <T> FileCache<T> apply(Converter<T> converter)
      throws IOException, InterruptedException {
    return new FileCacheImpl<>(converter);
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
  public static <T> FileCache<T> apply(Converter<T> converter, Observer<T> observer)
      throws IOException, InterruptedException {
    FileCache<T> res = new FileCacheImpl<>(converter);
    res.addObserver(observer);
    return res;
  }
}

class FileCacheImpl<T> extends FileCache<T> {
  private final Map<Path, Directory<T>> directories = new HashMap<>();
  private final Converter<T> converter;
  private final DirectoryWatcher.Callback callback =
      new DirectoryWatcher.Callback() {
        @SuppressWarnings("unchecked")
        @Override
        public void apply(final DirectoryWatcher.Event event) {
          final Path path = event.path;
          if (Files.exists(path)) {
            Pair<Directory<T>, List<Entry<T>>> pair =
                listImpl(
                    path,
                    false,
                    new EntryFilter<T>() {
                      @Override
                      public boolean accept(Entry<? extends T> path) {
                        return event.path.equals(path.path);
                      }
                    });
            if (pair != null) {
              final Directory<T> dir = pair.first;
              final List<Entry<T>> paths = pair.second;
              final Path toUpdate =
                  paths.isEmpty() ? path : (path != dir.path) ? paths.get(0).path : null;
              if (toUpdate != null) {
                final Iterator<Entry<T>[]> it =
                    dir.addUpdate(toUpdate, !Files.isDirectory(path)).iterator();
                while (it.hasNext()) {
                  final Entry<T>[] entry = it.next();
                  if (entry.length == 2) {
                    observers.onUpdate(entry[0], entry[1]);
                  } else {
                    observers.onCreate(entry[0]);
                  }
                }
              }
            }
          } else {
            synchronized (directories) {
              final Iterator<Directory<T>> it = directories.values().iterator();
              while (it.hasNext()) {
                final Directory<T> dir = it.next();
                if (path.startsWith(dir.path)) {
                  final Iterator<Entry<T>> removeIterator = dir.remove(path).iterator();
                  while (removeIterator.hasNext()) {
                    observers.onDelete(removeIterator.next());
                  }
                }
              }
            }
          }
        }
      };
  private final DirectoryWatcher watcher = DirectoryWatcher.defaultWatcher(callback);

  FileCacheImpl(final Converter<T> converter) throws IOException, InterruptedException {
    this.converter = converter;
  }

  private Pair<Directory<T>, List<Entry<T>>> listImpl(
      final Path path, final boolean recursive, final EntryFilter<? super T> filter) {
    synchronized (directories) {
      if (Files.exists(path)) {
        Directory<T> foundDir = null;
        final Iterator<Directory<T>> it = directories.values().iterator();
        while (it.hasNext()) {
          final Directory<T> dir = it.next();
          if (path.startsWith(dir.path)
              && (foundDir == null || dir.path.startsWith(foundDir.path))) {
            foundDir = dir;
          }
        }
        if (foundDir != null) {
          return new Pair<Directory<T>, List<Entry<T>>>(
              foundDir, foundDir.list(path, recursive, filter));
        } else {
          return null;
        }
      } else {
        return null;
      }
    }
  }

  /** Cleans up the directory watcher and clears the directory cache. */
  @Override
  public void close() {
    watcher.close();
    directories.clear();
  }

  @Override
  public List<Entry<T>> list(
      final Path path, final boolean recursive, final EntryFilter<? super T> filter) {
    Pair<Directory<T>, List<Entry<T>>> pair = listImpl(path, recursive, filter);
    return pair == null ? new ArrayList<Entry<T>>() : pair.second;
  }

  @Override
  public Directory<T> register(final Path path, final boolean recursive) {
    Directory<T> result = null;
    if (Files.exists(path)) {
      watcher.register(path);
      synchronized (directories) {
        final Iterator<Directory<T>> it = directories.values().iterator();
        Directory<T> existing = null;
        while (it.hasNext() && existing == null) {
          final Directory<T> dir = it.next();
          if (path.startsWith(dir.path) && dir.recursive) {
            existing = dir;
          }
        }
        if (existing == null) {
          result = Directory.cached(path, converter, recursive);
          directories.put(path, result);
        }
      }
    }
    return result;
  }
  private static class Pair<A, B> {
    public final A first;
    public final B second;

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
