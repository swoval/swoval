package com.swoval.files;

import static com.swoval.files.EntryFilters.AllPass;
import static com.swoval.functional.Either.leftProjection;

import com.swoval.functional.Either;
import com.swoval.functional.Filter;
import com.swoval.functional.Filters;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Provides a mutable in-memory cache of files and subdirectories with basic CRUD functionality. The
 * Directory can be fully recursive as the subdirectories are themselves stored as recursive (when
 * the Directory is initialized without the recursive toggle, the subdirectories are stored as
 * {@link Directory.Entry} instances. The primary use case is the implementation of {@link
 * FileCache} and {@link NioPathWatcher}. Directly handling Directory instances is discouraged
 * because it is inherently mutable so it's better to let the FileCache manage it and query the
 * cache rather than Directory directly.
 *
 * <p>The Directory should cache all of the files and subdirectories up the maximum depth. A maximum
 * depth of zero means that the Directory should cache the subdirectories, but not traverse them. A
 * depth {@code < 0} means that it should not cache any files or subdirectories within the
 * directory. In the event that a loop is created by symlinks, the Directory will include the
 * symlink that completes the loop, but will not descend further (inducing a loop).
 *
 * @param <T> the cache value type.
 */
public class Directory<T> implements AutoCloseable {

  public int getDepth() {
    return depth;
  }

  public Path getPath() {
    return path;
  }

  private final int depth;

  private final Path path;
  private final Path realPath;
  private final Converter<T> converter;
  private final AtomicReference<Entry<T>> _cacheEntry;
  private final Object lock = new Object();
  private final Map<Path, Directory<T>> subdirectories = new HashMap<>();
  private final Map<Path, Entry<T>> files = new HashMap<>();
  private static final Converter<Path> PATH_CONVERTER =
      new Converter<Path>() {
        @Override
        public Path apply(final Path path) {
          return path;
        }
      };
  private final Filter<QuickFile> pathFilter;

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
   * Converts a Path into an arbitrary value to be cached.
   *
   * @param <R> the generic type generated from the path.
   */
  public interface Converter<R> {

    /**
     * Convert the path to a value.
     *
     * @param path the path to convert
     * @return the converted value
     * @throws IOException when the value can't be computed
     */
    R apply(Path path) throws IOException;
  }

  /**
   * The cache entry for the underlying path of this directory.
   *
   * @return the Entry for the directory itself.
   */
  public Entry<T> entry() {
    return _cacheEntry.get();
  }

  @SuppressWarnings("unchecked")
  Directory(
      final Path path,
      final Path realPath,
      final Converter<T> converter,
      final int depth,
      final Filter<? super QuickFile> filter)
      throws IOException {
    this.path = path;
    this.realPath = realPath;
    this.converter = converter;
    this.depth = depth;
    final int kind = Entries.getKind(path);
    this._cacheEntry = new AtomicReference<>(null);
    this.pathFilter =
        new Filter<QuickFile>() {
          @Override
          public boolean accept(QuickFile quickFile) {
            return quickFile.toPath().startsWith(Directory.this.path) && filter.accept(quickFile);
          }
        };
    this._cacheEntry.set(Entries.get(path, kind, converter, realPath));
  }

  /**
   * List all of the files for the {@code path}.
   *
   * @param maxDepth the maximum depth of subdirectories to query
   * @return a List of Entry instances accepted by the filter.
   */
  public List<Entry<T>> list(final int maxDepth) {
    final List<Entry<T>> result = new ArrayList<>();
    listImpl(maxDepth, EntryFilters.AllPass, result);
    return result;
  }

  /**
   * List all of the files for the {@code path}.
   *
   * @param recursive toggles whether or not the children of subdirectories are returned
   * @return a List of Entry instances accepted by the filter.
   */
  public List<Entry<T>> list(final boolean recursive) {
    final List<Entry<T>> result = new ArrayList<>();
    listImpl(recursive ? Integer.MAX_VALUE : 0, EntryFilters.AllPass, result);
    return result;
  }
  /**
   * List all of the files for the {@code path}, returning only those files that are accepted by the
   * provided filter.
   *
   * @param maxDepth the maximum depth of subdirectories to query
   * @param filter include only entries accepted by the filter
   * @return a List of Entry instances accepted by the filter.
   */
  public List<Entry<T>> list(final int maxDepth, final EntryFilter<? super T> filter) {
    final List<Entry<T>> result = new ArrayList<>();
    listImpl(maxDepth, filter, result);
    return result;
  }

  /**
   * List all of the files for the {@code path}.
   *
   * @param recursive toggles whether to include the children of subdirectories
   * @param filter include only entries accepted by the filter
   * @return a list of Entry instances accepted by the filter.
   */
  public List<Entry<T>> list(final boolean recursive, final EntryFilter<? super T> filter) {
    final List<Entry<T>> result = new ArrayList<>();
    listImpl(recursive ? Integer.MAX_VALUE : 0, filter, result);
    return result;
  }

  /**
   * List all of the files for the {@code path</code> that are accepted by the <code>filter}.
   *
   * @param path the path to list. If this is a file, returns a list containing the Entry for the
   *     file or an empty list if the file is not monitored by the path.
   * @param maxDepth the maximum depth of subdirectories to return
   * @param filter include only paths accepted by this
   * @return a List of Entry instances accepted by the filter. The list will be empty if the path is
   *     not a subdirectory of this Directory or if it is a subdirectory, but the Directory was
   *     created without the recursive flag.
   */
  public List<Entry<T>> list(
      final Path path, final int maxDepth, final EntryFilter<? super T> filter) {
    final Either<Entry<T>, Directory<T>> findResult = find(path);
    if (findResult != null) {
      if (findResult.isRight()) {
        return findResult.get().list(maxDepth, filter);
      } else {
        final Entry<T> entry = leftProjection(findResult).getValue();
        final List<Entry<T>> result = new ArrayList<>();
        if (entry != null && filter.accept(entry)) result.add(entry);
        return result;
      }
    } else {
      return new ArrayList<>();
    }
  }

  /**
   * List all of the files for the {@code path</code> that are accepted by the <code>filter}.
   *
   * @param path the path to list. If this is a file, returns a list containing the Entry for the
   *     file or an empty list if the file is not monitored by the path.
   * @param recursive toggles whether or not to include children of subdirectories in the results
   * @param filter include only paths accepted by this.
   * @return a List of Entry instances accepted by the filter. The list will be empty if the path is
   *     not a subdirectory of this Directory or if it is a subdirectory, but the Directory was
   *     created without the recursive flag.
   */
  public List<Entry<T>> list(
      final Path path, final boolean recursive, final EntryFilter<? super T> filter) {
    return list(path, recursive ? Integer.MAX_VALUE : 0, filter);
  }

  /**
   * Updates the Directory entry for a particular path.
   *
   * @param path the path to update
   * @param kind specifies the type of file. This can be DIRECTORY, FILE with an optional LINK bit
   *     set if the file is a symbolic link
   * @return a list of updates for the path. When the path is new, the updates have the
   *     oldCachedPath field set to null and will contain all of the children of the new path when
   *     it is a directory. For an existing path, the List contains a single Updates that contains
   *     the previous and new {@link Directory.Entry}.
   * @throws IOException when the updated Path is a directory and an IOException is encountered
   *     traversing the directory.
   */
  public Updates<T> update(final Path path, final int kind) throws IOException {
    return pathFilter.accept(new QuickFileImpl(path.toString(), kind))
        ? updateImpl(
            path.equals(this.path)
                ? new ArrayList<Path>()
                : FileOps.parts(this.path.relativize(path)),
            kind)
        : new Updates<T>();
  }

  /**
   * Remove a path from the directory.
   *
   * @param path the path to remove
   * @return a List containing the Entry instances for the removed path. The result also contains
   *     the cache entries for any children of the path when the path is a non-empty directory.
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
    return "Directory(" + path + ", maxDepth = " + depth + ")";
  }

  private int subdirectoryDepth() {
    return depth == Integer.MAX_VALUE ? depth : depth > 0 ? depth - 1 : 0;
  }

  @SuppressWarnings("unchecked")
  private void addDirectory(
      final Directory<T> currentDir, final Path path, final Updates<T> updates) throws IOException {
    final Directory<T> dir =
        new Directory<>(path, path, converter, currentDir.subdirectoryDepth(), pathFilter).init();
    final Map<Path, Entry<T>> oldEntries = new HashMap<>();
    final Directory<T> previous = currentDir.subdirectories.put(path.getFileName(), dir);
    if (previous != null) {
      oldEntries.put(previous.realPath, previous.entry());
      final Iterator<Entry<T>> entryIterator = previous.list(Integer.MAX_VALUE, AllPass).iterator();
      while (entryIterator.hasNext()) {
        final Entry<T> entry = entryIterator.next();
        oldEntries.put(entry.getPath(), entry);
      }
    }
    final Map<Path, Entry<T>> newEntries = new HashMap<>();
    newEntries.put(dir.realPath, dir.entry());
    final Iterator<Entry<T>> it = dir.list(Integer.MAX_VALUE, AllPass).iterator();
    while (it.hasNext()) {
      final Entry<T> entry = it.next();
      newEntries.put(entry.getPath(), entry);
    }
    MapOps.diffDirectoryEntries(oldEntries, newEntries, updates);
  }

  private boolean isLoop(final Path path, final Path realPath) {
    return path.startsWith(realPath) && !path.equals(realPath);
  }

  private Updates<T> updateImpl(final List<Path> parts, final int kind) throws IOException {
    final Updates<T> result = new Updates<>();
    if (!parts.isEmpty()) {
      final Iterator<Path> it = parts.iterator();
      Directory<T> currentDir = this;
      while (it.hasNext() && currentDir != null && currentDir.depth >= 0) {
        final Path p = it.next();
        if (p.toString().isEmpty()) return result;
        final Path resolved = currentDir.path.resolve(p);
        final Path realPath = toRealPath(resolved);
        if (!it.hasNext()) {
          // We will always return from this block
          synchronized (currentDir.lock) {
            final boolean isDirectory = (kind & Entries.DIRECTORY) != 0;
            if (!isDirectory || currentDir.depth <= 0 || isLoop(resolved, realPath)) {
              final Directory<T> previousDirectory =
                  isDirectory ? currentDir.subdirectories.get(p) : null;
              final Entry<T> oldEntry =
                  previousDirectory != null ? previousDirectory.entry() : currentDir.files.get(p);
              final Entry<T> newEntry = Entries.get(p, kind, converter, resolved);
              if (isDirectory) {
                currentDir.subdirectories.put(
                    p, new Directory<>(resolved, realPath, converter, -1, pathFilter));
              } else {
                currentDir.files.put(p, newEntry);
              }
              final Entry<T> oldResolvedEntry =
                  oldEntry == null ? null : Entries.resolve(currentDir.path, oldEntry);
              if (oldResolvedEntry == null) {
                result.onCreate(Entries.resolve(currentDir.path, newEntry));
              } else {
                result.onUpdate(oldResolvedEntry, Entries.resolve(currentDir.path, newEntry));
              }
              return result;
            } else {
              addDirectory(currentDir, resolved, result);
              return result;
            }
          }
        } else {
          synchronized (currentDir.lock) {
            final Directory<T> dir = currentDir.subdirectories.get(p);
            if (dir == null && currentDir.depth > 0) {
              addDirectory(currentDir, currentDir.path.resolve(p), result);
            }
            currentDir = dir;
          }
        }
      }
    } else if (kind == Entries.DIRECTORY) {
      final List<Entry<T>> oldEntries = list(true, AllPass);
      init();
      MapOps.diffDirectoryEntries(oldEntries, list(true, AllPass), result);
    } else {
      final Entry<T> oldEntry = entry();
      final Entry<T> newEntry = Entries.get(realPath, kind, converter, realPath);
      _cacheEntry.set(newEntry);
      result.onUpdate(oldEntry, entry());
    }
    return result;
  }

  private Either<Entry<T>, Directory<T>> findImpl(final List<Path> parts) {
    final Iterator<Path> it = parts.iterator();
    Directory<T> currentDir = this;
    Either<Entry<T>, Directory<T>> result = null;
    while (it.hasNext() && currentDir != null && result == null) {
      final Path p = it.next();
      if (!it.hasNext()) {
        synchronized (currentDir.lock) {
          final Directory<T> subdir = currentDir.subdirectories.get(p);
          if (subdir != null) {
            result = Either.right(subdir);
          } else {
            final Entry<T> entry = currentDir.files.get(p);
            if (entry != null) result = Either.left(Entries.resolve(currentDir.path, entry));
          }
        }
      } else {
        synchronized (currentDir.lock) {
          currentDir = currentDir.subdirectories.get(p);
        }
      }
    }
    return result;
  }

  private Either<Entry<T>, Directory<T>> find(final Path path) {
    if (path.equals(this.path)) {
      return Either.right(this);
    } else if (!path.isAbsolute()) {
      return findImpl(FileOps.parts(path));
    } else if (path.startsWith(this.path)) {
      return findImpl(FileOps.parts(this.path.relativize(path)));
    } else {
      return null;
    }
  }

  private void listImpl(
      final int maxDepth, final EntryFilter<? super T> filter, final List<Entry<T>> result) {
    if (this.depth < 0 || maxDepth < 0) {
      result.add(this.entry());
    } else {
      final Collection<Entry<T>> files;
      final Collection<Directory<T>> subdirectories;
      synchronized (this.lock) {
        files = new ArrayList<>(this.files.values());
        subdirectories = new ArrayList<>(this.subdirectories.values());
      }
      final Iterator<Entry<T>> filesIterator = files.iterator();
      while (filesIterator.hasNext()) {
        final Entry<T> entry = filesIterator.next();
        final Entry<T> resolved = Entries.resolve(getPath(), entry);
        if (filter.accept(resolved)) result.add(resolved);
      }
      final Iterator<Directory<T>> subdirIterator = subdirectories.iterator();
      while (subdirIterator.hasNext()) {
        final Directory<T> subdir = subdirIterator.next();
        final Entry<T> entry = subdir.entry();
        final Entry<T> resolved = Entries.resolve(getPath(), entry);
        if (filter.accept(resolved)) result.add(resolved);
        if (maxDepth > 0) subdir.listImpl(maxDepth - 1, filter, result);
      }
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
          final Entry<T> entry = currentDir.files.remove(p);
          if (entry != null) {
            result.add(Entries.resolve(currentDir.path, entry));
          } else {
            final Directory<T> dir = currentDir.subdirectories.remove(p);
            if (dir != null) {
              result.addAll(dir.list(Integer.MAX_VALUE, AllPass));
              result.add(dir.entry());
            }
          }
        }
      } else {
        synchronized (currentDir.lock) {
          currentDir = currentDir.subdirectories.get(p);
        }
      }
    }
    return result;
  }

  private Path toRealPath(final Path path) {
    try {
      return path.toRealPath();
    } catch (final IOException e) {
      return path;
    }
  }

  Directory<T> init() throws IOException {
    if (depth >= 0) {
      synchronized (lock) {
        final Iterator<QuickFile> it = QuickList.list(path, 0, true).iterator();
        while (it.hasNext()) {
          final QuickFile file = it.next();
          if (pathFilter.accept(file)) {
            final int kind =
                (file.isSymbolicLink() ? Entries.LINK : 0)
                    | (file.isDirectory() ? Entries.DIRECTORY : Entries.FILE);
            final Path path = file.toPath();
            final Path key = this.path.relativize(path).getFileName();
            if (file.isDirectory()) {
              if (depth > 0) {
                final Path realPath = toRealPath(path);
                if (!file.isSymbolicLink() || !isLoop(path, realPath)) {
                  final Directory<T> dir =
                      new Directory<>(path, realPath, converter, subdirectoryDepth(), pathFilter);
                  subdirectories.put(key, dir);
                  try {
                    dir.init();
                  } catch (final IOException e) {
                  }
                } else {
                  subdirectories.put(
                      key, new Directory<>(path, realPath, converter, -1, pathFilter));
                }
              } else {
                files.put(key, Entries.get(key, kind, converter, path));
              }
            } else {
              files.put(key, Entries.get(key, kind, converter, path));
            }
          }
        }
      }
    }
    return this;
  }

  /**
   * Make a new recursive Directory with no cache value associated with the path.
   *
   * @param path the path to monitor
   * @return a directory whose entries just contain the path itself.
   * @throws IOException when an error is encountered traversing the directory.
   */
  public static Directory<Path> of(final Path path) throws IOException {
    return of(path, true);
  }

  /**
   * Make a new Directory with no cache value associated with the path.
   *
   * @param path the path to monitor
   * @param depth sets how the limit for how deep to traverse the children of this directory
   * @return a directory whose entries just contain the path itself.
   * @throws IOException when an error is encountered traversing the directory.
   */
  public static Directory<Path> of(final Path path, final int depth) throws IOException {
    return new Directory<>(path, path, PATH_CONVERTER, depth, Filters.AllPass).init();
  }
  /**
   * Make a new Directory with no cache value associated with the path.
   *
   * @param path the path to monitor
   * @param recursive Toggles whether or not to cache the children of subdirectories
   * @return a directory whose entries just contain the path itself.
   * @throws IOException when an error is encountered traversing the directory.
   */
  public static Directory<Path> of(final Path path, final boolean recursive) throws IOException {
    return new Directory<>(
            path, path, PATH_CONVERTER, recursive ? Integer.MAX_VALUE : 0, Filters.AllPass)
        .init();
  }

  /**
   * Make a new Directory with a cache entries created by {@code converter}.
   *
   * @param path the path to cache
   * @param converter a function to create the cache value for each path
   * @param <T> the cache value type
   * @return a directory with entries of type T.
   * @throws IOException when an error is encountered traversing the directory.
   */
  public static <T> Directory<T> cached(final Path path, final Converter<T> converter)
      throws IOException {
    return new Directory<>(path, path, converter, Integer.MAX_VALUE, Filters.AllPass).init();
  }
  /**
   * Make a new Directory with a cache entries created by {@code converter}.
   *
   * @param path the path to cache
   * @param converter a function to create the cache value for each path
   * @param recursive toggles whether or not to the children of subdirectories
   * @param <T> the cache value type
   * @return a directory with entries of type T.
   * @throws IOException when an error is encountered traversing the directory.
   */
  public static <T> Directory<T> cached(
      final Path path, final Converter<T> converter, final boolean recursive) throws IOException {
    return new Directory<>(
            path, path, converter, recursive ? Integer.MAX_VALUE : 0, Filters.AllPass)
        .init();
  }

  /**
   * Make a new Directory with a cache entries created by {@code converter}.
   *
   * @param path the path to cache
   * @param converter a function to create the cache value for each path
   * @param depth determines how many levels of children of subdirectories to include in the results
   * @param <T> the cache value type
   * @return a directory with entries of type T.
   * @throws IOException when an error is encountered traversing the directory.
   */
  public static <T> Directory<T> cached(
      final Path path, final Converter<T> converter, final int depth) throws IOException {
    return new Directory<>(path, path, converter, depth, Filters.AllPass).init();
  }

  /**
   * Container class for {@link Directory} entries. Contains both the path to which the path
   * corresponds along with a data value.
   *
   * @param <T> The value wrapped in the Entry
   */
  public interface Entry<T> extends TypedPath {
    /**
     * Return the value associated with this entry.
     *
     * @return the value associated with this entry.
     */
    Either<IOException, T> getValue();
    /**
     * Return the path associated with this entry.
     *
     * @return the path associated with this entry.
     */
    Path getPath();
  }

  static class Updates<T> implements Observer<T> {

    private final List<Entry<T>> creations = new ArrayList<>();
    private final List<Entry<T>> deletions = new ArrayList<>();
    private final List<Entry<T>[]> updates = new ArrayList<>();

    public void observe(final Observer<T> observer) {
      final Iterator<Entry<T>> creationIterator = creations.iterator();
      while (creationIterator.hasNext()) {
        observer.onCreate(creationIterator.next());
      }
      final Iterator<Entry<T>[]> updateIterator = updates.iterator();
      while (updateIterator.hasNext()) {
        final Entry<T>[] entries = updateIterator.next();
        observer.onUpdate(entries[0], entries[1]);
      }
      final Iterator<Entry<T>> deletionIterator = deletions.iterator();
      while (deletionIterator.hasNext()) {
        observer.onDelete(deletionIterator.next());
      }
    }

    @Override
    public void onCreate(Entry<T> newEntry) {
      creations.add(newEntry);
    }

    @Override
    public void onDelete(Entry<T> oldEntry) {
      deletions.add(oldEntry);
    }

    @SuppressWarnings("unchecked")
    @Override
    public void onUpdate(Entry<T> oldEntry, Entry<T> newEntry) {
      updates.add(new Entry[] {oldEntry, newEntry});
    }

    @Override
    public void onError(Path path, IOException exception) {}
  }

  /**
   * A Filter for {@link Directory.Entry} elements.
   *
   * @param <T> the data value type for the {@link Directory.Entry}
   */
  public interface EntryFilter<T> {

    /**
     * Evaluates the filter for a given entry.
     *
     * @param entry the entry type
     * @return true if the {@link com.swoval.files.Directory.Entry} is accepted.
     */
    boolean accept(Entry<? extends T> entry);
  }

  /**
   * A callback to fire when a file in a monitored directory is created or deleted.
   *
   * @param <T> The cached value associated with the path
   */
  public interface OnChange<T> {

    /**
     * The callback to run when the path changes.
     *
     * @param entry the entry for the updated path
     */
    void apply(Entry<T> entry);
  }

  /**
   * A callback to fire when a file in a monitor is updated.
   *
   * @param <T> the cached value associated with the path
   */
  public interface OnUpdate<T> {
    /**
     * The callback to run when a path is updated.
     *
     * @param oldEntry the previous entry for the updated path
     * @param newEntry the new entry for the updated path
     */
    void apply(Entry<T> oldEntry, Entry<T> newEntry);
  }

  /**
   * A callback to fire when an error is encountered. This will generally be a {@link
   * java.nio.file.FileSystemLoopException}.
   */
  public interface OnError {

    /**
     * Apply callback for error.
     *
     * @param path the path that induced the error
     * @param exception the encountered error
     */
    void apply(final Path path, final IOException exception);
  }

  /**
   * Provides callbacks to run when different types of file events are detected by the cache.
   *
   * @param <T> the type for the {@link Directory.Entry} data
   */
  public interface Observer<T> {

    /**
     * Callback to fire when a new path is created.
     *
     * @param newEntry the {@link com.swoval.files.Directory.Entry} for the newly created file
     */
    void onCreate(Entry<T> newEntry);

    /**
     * Callback to fire when a path is deleted.
     *
     * @param oldEntry the {@link com.swoval.files.Directory.Entry} for the deleted.
     */
    void onDelete(Entry<T> oldEntry);

    /**
     * Callback to fire when a path is modified.
     *
     * @param oldEntry the {@link com.swoval.files.Directory.Entry} for the updated path
     * @param newEntry the {@link com.swoval.files.Directory.Entry} for the deleted path
     */
    void onUpdate(Entry<T> oldEntry, Entry<T> newEntry);

    /**
     * Callback to fire when an error is encountered generating while updating a path.
     *
     * @param path The path that triggered the exception
     * @param exception The exception thrown by the computation
     */
    void onError(final Path path, final IOException exception);
  }
}
