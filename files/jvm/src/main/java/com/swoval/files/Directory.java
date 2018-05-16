package com.swoval.files;

import static com.swoval.files.EntryFilters.AllPass;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Provides a mutable in-memory cache of files and subdirectories with basic CRUD functionality. The
 * Directory can be fully recursive as the subdirectories are themselves stored as recursive (when
 * the Directory is initialized without the recursive toggle, the subdirectories are stored as
 * {@link Directory.Entry} instances.The primary use case is the implementation of {@link
 * FileCache}. Directly handling Directory instances is discouraged because it is inherently mutable
 * so it's better to let the FileCache manage it and query the cache rather than Directory directly.
 *
 * @param <T> The cache value type
 */
public class Directory<T> implements AutoCloseable {
  public final Path path;
  public final boolean recursive;
  private final Converter<T> converter;
  private final AtomicReference<Entry<T>> _cacheEntry;
  private final Object lock = new Object();
  private final MapByName<Directory<T>> subdirectories = new MapByName<>();
  private final MapByName<Entry<T>> files = new MapByName<>();
  private static final Converter<Path> PATH_CONVERTER =
      new Converter<Path>() {
        @Override
        public Path apply(final Path path) {
          return path;
        }
      };

  @Override
  public void close() {
    synchronized (this.lock) {
      final Iterator<Directory<T>> it = subdirectories.values().iterator();
      while (it.hasNext()) it.next().close();
      subdirectories.clear();
      files.clear();
    }
  }

  /**
   * Converts a Path into an arbitrary value to be cached
   *
   * @param <R> The generic type generated from the path
   */
  public interface Converter<R> {
    R apply(Path path);
  }

  /**
   * The cache entry for the underlying path of this directory
   *
   * @return The Entry for the directory itself
   */
  public Entry<T> entry() {
    return _cacheEntry.get();
  }

  private Directory(final Path path, final Converter<T> converter, final boolean recursive) {
    this.path = path;
    this.converter = converter;
    this.recursive = recursive;
    this._cacheEntry = new AtomicReference<>(new Entry<>(path, converter.apply(path)));
  }

  /**
   * List all of the files for the <code>path</code>
   *
   * @param recursive Toggles whether or not to include children of subdirectories in the results
   * @param filter Include only entries accepted by the filter
   * @return a List of Entry instances accepted by the filter
   */
  public List<Entry<T>> list(final boolean recursive, final EntryFilter<? super T> filter) {
    final List<Entry<T>> result = new ArrayList<>();
    listImpl(recursive, filter, result);
    return result;
  }

  /**
   * List all of the files for the <code>path</code> that are accepted by the <code>filter</code>.
   *
   * @param path The path to list. If this is a file, returns a list containing the Entry for the
   *     file or an empty list if the file is not monitored by the path.
   * @param recursive Toggles whether or not to include children of subdirectories in the results
   * @param filter Include only paths accepted by this
   * @return a List of Entry instances accepted by the filter. The list will be empty if the path is
   *     not a subdirectory of this Directory or if it is a subdirectory, but the Directory was
   *     created without the recursive flag.
   */
  public List<Entry<T>> list(
      final Path path, final boolean recursive, final EntryFilter<? super T> filter) {
    final FindResult findResult = find(path);
    if (findResult != null) {
      final Directory<T> dir = findResult.directory;
      if (dir != null) {
        return dir.list(recursive, filter);
      } else {
        final Entry<T> entry = findResult.entry;
        final List<Entry<T>> result = new ArrayList<>();
        if (entry != null && filter.accept(entry)) result.add(entry);
        return result;
      }
    } else {
      return new ArrayList<>();
    }
  }

  /**
   * Update the Directory entry for a particular path.
   *
   * @param path The path to addUpdate
   * @param isFile Indicates whether <code>path</code> is a regular file
   * @return A list of updates for the path. When the path is new, the updates have the
   *     oldCachedPath field set to null and will contain all of the children of the new path when
   *     it is a directory. For an existing path, the List contains a single Update that contains
   *     the previous and new {@link Directory.Entry}
   */
  public List<Entry<T>[]> addUpdate(final Path path, final boolean isFile) {
    return !path.isAbsolute()
        ? updateImpl(FileOps.parts(path), isFile)
        : (path.startsWith(this.path))
            ? updateImpl(FileOps.parts(this.path.relativize(path)), isFile)
            : new ArrayList<Entry<T>[]>();
  }

  /**
   * Remove a path from the directory
   *
   * @param path The path to remove
   * @return List containing the Entry instances for the removed path. Also contains the cache
   *     entries for any children of the path when the path is a non-empty directory
   */
  public List<Entry<T>> remove(final Path path) {
    if (path.isAbsolute() && path.startsWith(this.path)) {
      return removeImpl(FileOps.parts(this.path.relativize(path)));
    } else {
      return new ArrayList<>();
    }
  }

  @Override
  public String toString() {
    return "Directory(" + path + ", recursive = " + recursive + ")";
  }

  private void addDirectory(
      final Directory<T> currentDir, final Path path, final List<Entry<T>[]> updates) {
    final Directory<T> dir = cached(path, converter, true);
    currentDir.subdirectories.put(path.getFileName().toString(), dir);
    addUpdate(updates, dir.entry());
    final Iterator<Entry<T>> it = dir.list(true, AllPass).iterator();
    while (it.hasNext()) {
      addUpdate(updates, it.next());
    }
  }

  @SuppressWarnings("unchecked")
  private void addUpdate(List<Entry<T>[]> list, Entry<T> oldEntry, Entry<T> newEntry) {
    list.add(oldEntry == null ? new Entry[] {newEntry} : new Entry[] {oldEntry, newEntry});
  }

  @SuppressWarnings("unchecked")
  private void addUpdate(List<Entry<T>[]> list, Entry<T> entry) {
    addUpdate(list, null, entry);
  }

  private List<Entry<T>[]> updateImpl(final List<Path> parts, final boolean isFile) {
    final Iterator<Path> it = parts.iterator();
    Directory<T> currentDir = this;
    List<Entry<T>[]> result = new ArrayList<>();
    while (it.hasNext() && currentDir != null) {
      final Path p = it.next();
      if (p.toString().isEmpty()) return result;
      final Path resolved = currentDir.path.resolve(p);
      if (!it.hasNext()) {
        // We will always return from this block
        synchronized (currentDir.lock) {
          if (isFile || !currentDir.recursive) {
            final Entry<T> oldEntry = currentDir.files.getByName(p);
            final Entry<T> newEntry = new Entry<>(p, converter.apply(resolved), false);
            currentDir.files.put(p.toString(), newEntry);
            final Entry<T> oldResolvedEntry =
                oldEntry == null ? null : oldEntry.resolvedFrom(currentDir.path);
            addUpdate(result, oldResolvedEntry, newEntry.resolvedFrom(currentDir.path));
            return result;
          } else {
            final Directory<T> dir = currentDir.subdirectories.getByName(p);
            if (dir == null) {
              addDirectory(currentDir, resolved, result);
              return result;
            } else {
              final Entry<T> oldEntry = dir.entry();
              dir._cacheEntry.set(new Entry<>(dir.path, converter.apply(dir.path), true));
              addUpdate(result, oldEntry.resolvedFrom(currentDir.path), dir.entry());
              return result;
            }
          }
        }
      } else {
        synchronized (currentDir.lock) {
          final Directory<T> dir = currentDir.subdirectories.getByName(p);
          if (dir == null && currentDir.recursive) {
            addDirectory(currentDir, currentDir.path.resolve(p), result);
          }
          currentDir = dir;
        }
      }
    }
    return result;
  }

  private FindResult findImpl(final List<Path> parts) {
    final Iterator<Path> it = parts.iterator();
    Directory<T> currentDir = this;
    FindResult result = null;
    while (it.hasNext() && currentDir != null && result == null) {
      final Path p = it.next();
      if (!it.hasNext()) {
        synchronized (currentDir.lock) {
          final Directory<T> subdir = currentDir.subdirectories.getByName(p);
          if (subdir != null) {
            result = right(subdir);
          } else {
            final Entry<T> file = currentDir.files.getByName(p);
            if (file != null) result = left(file.resolvedFrom(currentDir.path, false));
          }
        }
      } else {
        synchronized (currentDir.lock) {
          currentDir = currentDir.subdirectories.getByName(p);
        }
      }
    }
    return result;
  }

  private FindResult find(final Path path) {
    if (path.equals(this.path)) {
      return right(this);
    } else if (!path.isAbsolute()) {
      return findImpl(FileOps.parts(path));
    } else if (path.startsWith(this.path)) {
      return findImpl(FileOps.parts(this.path.relativize(path)));
    } else {
      return null;
    }
  }

  private void listImpl(
      final boolean recursive, final EntryFilter<? super T> filter, final List<Entry<T>> result) {
    final Collection<Entry<T>> files;
    final Collection<Directory<T>> subdirectories;
    synchronized (this.lock) {
      files = this.files.values();
      subdirectories = this.subdirectories.values();
    }
    final Iterator<Entry<T>> filesIterator = files.iterator();
    while (filesIterator.hasNext()) {
      final Entry<T> resolved = filesIterator.next().resolvedFrom(this.path, false);
      if (filter.accept(resolved)) result.add(resolved);
    }
    final Iterator<Directory<T>> subdirIterator = subdirectories.iterator();
    while (subdirIterator.hasNext()) {
      final Directory<T> subdir = subdirIterator.next();
      final Entry<T> resolved = subdir.entry().resolvedFrom(this.path, true);
      if (filter.accept(resolved)) result.add(resolved);
      if (recursive) subdir.listImpl(true, filter, result);
    }
  }

  private List<Entry<T>> removeImpl(final List<Path> parts) {
    final List<Entry<T>> result = new ArrayList<>();
    final Iterator<Path> it = parts.iterator();
    Directory<T> currentDir = this;
    while (it.hasNext() && currentDir != null) {
      final Path p = it.next();
      if (!it.hasNext()) {
        synchronized (currentDir.lock) {
          final Entry<T> file = currentDir.files.removeByName(p);
          if (file != null) {
            result.add(file.resolvedFrom(currentDir.path, true));
          } else {
            final Directory<T> dir = currentDir.subdirectories.removeByName(p);
            if (dir != null) {
              result.addAll(dir.list(true, AllPass));
              result.add(dir.entry());
            }
          }
        }
      } else {
        synchronized (currentDir.lock) {
          currentDir = currentDir.subdirectories.getByName(p);
        }
      }
    }
    return result;
  }

  private Directory<T> init() {
    if (Files.exists(path)) {
      synchronized (lock) {
        final Iterator<Path> it = FileOps.list(path, false).iterator();
        while (it.hasNext()) {
          final Path p = it.next();
          final Path key = path.relativize(p).getFileName();
          if (Files.isDirectory(p)) {
            if (recursive) {
              subdirectories.put(key.toString(), cached(p, converter));
            } else {
              files.put(key.toString(), new Entry<>(key, converter.apply(p), true));
            }
          } else {
            files.put(key.toString(), new Entry<>(key, converter.apply(p), false));
          }
        }
      }
    }
    return this;
  }

  private static class MapByName<T> extends HashMap<String, T> {
    T getByName(final Path path) {
      return get(path.getFileName().toString());
    }

    T removeByName(final Path path) {
      return remove(path.getFileName().toString());
    }
  }

  /**
   * Make a new recursive Directory with no cache value associated with the path
   *
   * @param path The path to monitor
   * @return A directory whose entries just contain the path itself
   */
  public static Directory<Path> of(final Path path) {
    return of(path, true);
  }

  /**
   * Make a new Directory with no cache value associated with the path
   *
   * @param path The path to monitor
   * @param recursive Toggles whether or not to cache the children of subdirectories
   * @return A directory whose entries just contain the path itself
   */
  public static Directory<Path> of(final Path path, final boolean recursive) {
    return new Directory<>(path, PATH_CONVERTER, recursive).init();
  }

  /**
   * Make a new recursive Directory with cache entries created by <code>converter</code>
   *
   * @param path The path to cache
   * @param converter Function to create the cache value for each path
   * @param <T> The cache value type
   * @return A directory with entries of type T
   */
  public static <T> Directory<T> cached(final Path path, final Converter<T> converter) {
    return cached(path, converter, true);
  }

  /**
   * Make a new Directory with a cache entries created by <code>converter</code>
   *
   * @param path The path to cache
   * @param converter Function to create the cache value for each path
   * @param recursive Toggles whether or not to cache the children of subdirectories
   * @param <T> The cache value type
   * @return A directory with entries of type T
   */
  public static <T> Directory<T> cached(
      final Path path, final Converter<T> converter, final boolean recursive) {
    return new Directory<>(path, converter, recursive).init();
  }

  private class FindResult {
    public final Entry<T> entry;
    public final Directory<T> directory;

    FindResult(final Entry<T> entry, final Directory<T> directory) {
      this.entry = entry;
      this.directory = directory;
    }
  }

  private FindResult left(Entry<T> entry) {
    return new FindResult(entry, null);
  }

  private FindResult right(Directory<T> directory) {
    return new FindResult(null, directory);
  }

  /**
   * Container class for {@link Directory} entries. Contains both the path to which the path
   * corresponds along with a data value.
   *
   * @param <T> The value wrapped in the Entry
   */
  public static final class Entry<T> {
    public final Path path;
    public final T value;
    public final boolean isDirectory;

    /**
     * Create a new Entry
     *
     * @param path The path to which this entry corresponds blah
     * @param value The <code>path</code> derived value for this entry
     * @param isDirectory True when the path is a directory -- this is an optimization to avoid
     *     having to query the file system to check whether the cache entry is a directory or not.
     */
    public Entry(final Path path, final T value, final boolean isDirectory) {
      this.path = path;
      this.value = value;
      this.isDirectory = isDirectory;
    }

    /**
     * Create a new Entry using the FileSystem to check if the Entry is for a directory
     *
     * @param path The path to which this entry corresponds
     * @param value The <code>path</code> derived value for this entry
     */
    public Entry(final Path path, final T value) {
      this(path, value, Files.isDirectory(path));
    }

    public boolean exists() {
      return Files.exists(path);
    }

    /**
     * Resolve a Entry for a relative <code>path</code>
     *
     * @param other The path to resolve <code>path</code> against
     * @return A Entry where the <code>path</code> has been resolved against <code>other</code>
     */
    @SuppressWarnings("unchecked")
    public Entry<T> resolvedFrom(Path other) {
      return new Entry(other.resolve(path), this.value, this.isDirectory);
    }
    /**
     * Resolve a Entry for a relative <code>path</code> where <code>isDirectory</code> is known in
     * advance
     *
     * @param other The path to resolve <code>path</code> against
     * @param isDirectory Indicates whether the path is a directory
     * @return A Entry where the <code>path</code> has been resolved against <code>other</code>
     */
    @SuppressWarnings("unchecked")
    public Entry<T> resolvedFrom(Path other, boolean isDirectory) {
      return new Entry(other.resolve(path), this.value, isDirectory);
    }

    @Override
    public boolean equals(Object other) {
      if (other instanceof Directory.Entry<?>) {
        Entry<?> that = (Entry<?>) other;
        return this.path.equals(that.path) && this.value.equals(that.value);
      } else {
        return false;
      }
    }

    @Override
    public int hashCode() {
      return path.hashCode() ^ value.hashCode();
    }

    @Override
    public String toString() {
      return "Entry(" + path + ", " + value + ")";
    }
  }

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

}
