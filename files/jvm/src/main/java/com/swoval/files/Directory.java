package com.swoval.files;

import static com.swoval.files.EntryFilters.AllPass;

import com.swoval.functional.Either;
import com.swoval.functional.Filter;
import com.swoval.functional.Filters;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
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
 * @param <T> The cache value type
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
  private final MapByName<Directory<T>> subdirectories = new MapByName<>();
  private final MapByName<Entry<T>> files = new MapByName<>();
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
   * Converts a Path into an arbitrary value to be cached
   *
   * @param <R> The generic type generated from the path
   */
  public interface Converter<R> {

    /**
     * Convert the path to a value
     *
     * @param path The path to convert
     * @return The value
     * @throws IOException when the value can't be computed
     */
    R apply(Path path) throws IOException;
  }

  /**
   * The cache entry for the underlying path of this directory
   *
   * @return The Entry for the directory itself
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
   * List all of the files for the {@code path}
   *
   * @param maxDepth The maximum depth of subdirectories to query
   * @param filter Include only entries accepted by the filter
   * @return a List of Entry instances accepted by the filter
   */
  public List<Entry<T>> list(final int maxDepth, final EntryFilter<? super T> filter) {
    final List<Entry<T>> result = new ArrayList<>();
    listImpl(maxDepth, filter, result);
    return result;
  }

  /**
   * List all of the files for the {@code path}
   *
   * @param recursive Toggles whether to include the children of subdirectories
   * @param filter Include only entries accepted by the filter
   * @return a List of Entry instances accepted by the filter
   */
  public List<Entry<T>> list(final boolean recursive, final EntryFilter<? super T> filter) {
    final List<Entry<T>> result = new ArrayList<>();
    listImpl(recursive ? Integer.MAX_VALUE : 0, filter, result);
    return result;
  }

  /**
   * List all of the files for the {@code path</code> that are accepted by the <code>filter}.
   *
   * @param path The path to list. If this is a file, returns a list containing the Entry for the
   *     file or an empty list if the file is not monitored by the path.
   * @param maxDepth The maximum depth of subdirectories to return
   * @param filter Include only paths accepted by this
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
        final Entry<T> entry = findResult.left().getValue();
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
    return list(path, recursive ? Integer.MAX_VALUE : 0, filter);
  }

  /**
   * Updates the Directory entry for a particular path.
   *
   * @param path The path to update
   * @param kind Specifies the type of file. This can be DIRECTORY, FILE with an optional LINK bit
   *     set if the file is a symbolic link.
   * @return A list of updates for the path. When the path is new, the updates have the
   *     oldCachedPath field set to null and will contain all of the children of the new path when
   *     it is a directory. For an existing path, the List contains a single Updates that contains
   *     the previous and new {@link Directory.Entry}
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

  public boolean recursive() {
    return depth == Integer.MAX_VALUE;
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
    final Directory<T> previous = currentDir.subdirectories.put(path.getFileName().toString(), dir);
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
                  isDirectory ? currentDir.subdirectories.getByName(p) : null;
              final Entry<T> oldEntry =
                  previousDirectory != null
                      ? previousDirectory.entry()
                      : currentDir.files.getByName(p);
              final Entry<T> newEntry = Entries.get(p, kind, converter, resolved);
              if (isDirectory) {
                currentDir.subdirectories.put(
                    p.toString(), new Directory<>(resolved, realPath, converter, -1, pathFilter));
              } else {
                currentDir.files.put(p.toString(), newEntry);
              }
              final Entry<T> oldResolvedEntry =
                  oldEntry == null ? null : oldEntry.resolvedFrom(currentDir.path);
              if (oldResolvedEntry == null) {
                result.onCreate(newEntry.resolvedFrom(currentDir.path));
              } else {
                result.onUpdate(oldResolvedEntry, newEntry.resolvedFrom(currentDir.path));
              }
              return result;
            } else {
              addDirectory(currentDir, resolved, result);
              return result;
            }
          }
        } else {
          synchronized (currentDir.lock) {
            final Directory<T> dir = currentDir.subdirectories.getByName(p);
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
          final Directory<T> subdir = currentDir.subdirectories.getByName(p);
          if (subdir != null) {
            result = Either.right(subdir);
          } else {
            final Entry<T> file = currentDir.files.getByName(p);
            if (file != null)
              result = Either.left(file.resolvedFrom(currentDir.path, file.getKind()));
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
        final Entry<T> resolved = entry.resolvedFrom(this.getPath(), entry.getKind());
        if (filter.accept(resolved)) result.add(resolved);
      }
      final Iterator<Directory<T>> subdirIterator = subdirectories.iterator();
      while (subdirIterator.hasNext()) {
        final Directory<T> subdir = subdirIterator.next();
        final Entry<T> entry = subdir.entry();
        final Entry<T> resolved = entry.resolvedFrom(this.path, entry.getKind());
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
          final Entry<T> file = currentDir.files.removeByName(p);
          if (file != null) {
            result.add(file.resolvedFrom(currentDir.path, file.getKind()));
          } else {
            final Directory<T> dir = currentDir.subdirectories.removeByName(p);
            if (dir != null) {
              result.addAll(dir.list(Integer.MAX_VALUE, AllPass));
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
                  subdirectories.put(
                      key.toString(),
                      new Directory<>(path, realPath, converter, subdirectoryDepth(), pathFilter)
                          .init());
                } else {
                  subdirectories.put(
                      key.toString(), new Directory<>(path, realPath, converter, -1, pathFilter));
                }
              } else {
                files.put(key.toString(), Entries.get(key, kind, converter, path));
              }
            } else {
              files.put(key.toString(), Entries.get(key, kind, converter, path));
            }
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
   * @throws IOException when an error is encountered traversing the directory
   */
  public static Directory<Path> of(final Path path) throws IOException {
    return of(path, true);
  }

  /**
   * Make a new Directory with no cache value associated with the path
   *
   * @param path The path to monitor
   * @param depth Sets how the limit for how deep to traverse the children of this directory
   * @return A directory whose entries just contain the path itself
   * @throws IOException when an error is encountered traversing the directory
   */
  public static Directory<Path> of(final Path path, final int depth) throws IOException {
    return new Directory<>(path, path, PATH_CONVERTER, depth, Filters.AllPass).init();
  }
  /**
   * Make a new Directory with no cache value associated with the path
   *
   * @param path The path to monitor
   * @param recursive Toggles whether or not to cache the children of subdirectories
   * @return A directory whose entries just contain the path itself
   * @throws IOException when an error is encountered traversing the directory
   */
  public static Directory<Path> of(final Path path, final boolean recursive) throws IOException {
    return new Directory<>(
            path, path, PATH_CONVERTER, recursive ? Integer.MAX_VALUE : 0, Filters.AllPass)
        .init();
  }

  /**
   * Make a new Directory with a cache entries created by {@code converter}
   *
   * @param path The path to cache
   * @param converter Function to create the cache value for each path
   * @param <T> The cache value type
   * @return A directory with entries of type T
   * @throws IOException when an error is encountered traversing the directory
   */
  public static <T> Directory<T> cached(final Path path, final Converter<T> converter)
      throws IOException {
    return new Directory<>(path, path, converter, Integer.MAX_VALUE, Filters.AllPass).init();
  }
  /**
   * Make a new Directory with a cache entries created by {@code converter}
   *
   * @param path The path to cache
   * @param converter Function to create the cache value for each path
   * @param recursive How many levels of children to accept for this directory
   * @param <T> The cache value type
   * @return A directory with entries of type T
   * @throws IOException when an error is encountered traversing the directory
   */
  public static <T> Directory<T> cached(
      final Path path, final Converter<T> converter, final boolean recursive) throws IOException {
    return new Directory<>(
            path, path, converter, recursive ? Integer.MAX_VALUE : 0, Filters.AllPass)
        .init();
  }

  /**
   * Make a new Directory with a cache entries created by {@code converter}
   *
   * @param path The path to cache
   * @param converter Function to create the cache value for each path
   * @param depth How many levels of children to accept for this directory
   * @param <T> The cache value type
   * @return A directory with entries of type T
   * @throws IOException when an error is encountered traversing the directory
   */
  public static <T> Directory<T> cached(
      final Path path, final Converter<T> converter, final int depth) throws IOException {
    return new Directory<>(path, path, converter, depth, Filters.AllPass).init();
  }

  /** Provides static constants and methods related to {@link com.swoval.files.Directory.Entry}. */
  public static final class Entries {
    static final int DIRECTORY = 1;
    static final int FILE = 2;
    static final int LINK = 4;
    static final int UNKNOWN = 8;

    private Entries() {}

    /**
     * Construct an entry with a particular value
     *
     * @param path The path of the entry
     * @param kind The kind of the entry
     * @param value The value of the entry
     * @param <T> The type of the value
     * @return an Entry with the provided path and value
     */
    public static <T> Entry<T> valid(final Path path, final int kind, final T value) {
      return new ValidEntry<>(path, kind, value);
    }

    /**
     * Construct an entry with a particular value
     *
     * @param path The path of the entry
     * @param value The value of the entry
     * @param <T> The type of the value
     * @return an Entry with the provided path and value
     */
    public static <T> Entry<T> valid(final Path path, final T value) {
      return new ValidEntry<>(path, getKindOrUnknown(path), value);
    }

    static <T> Entry<T> get(
        final Path path, final int kind, final Converter<T> converter, final Path converterPath) {
      try {
        return new ValidEntry<>(path, kind, converter.apply(converterPath));
      } catch (final IOException e) {
        return new InvalidEntry<>(path, kind, e);
      }
    }

    /**
     * Compute the underlying file type for the path.
     *
     * @param path The path whose type is to be determined.
     * @param attrs The attributes of the ile
     * @return The file type of the path
     */
    static int getKind(final Path path, final BasicFileAttributes attrs) {
      return attrs.isSymbolicLink()
          ? LINK | (Files.isDirectory(path) ? DIRECTORY : FILE)
          : attrs.isDirectory() ? DIRECTORY : FILE;
    }

    /**
     * Compute the underlying file type for the path.
     *
     * @param path The path whose type is to be determined.
     * @return The file type of the path
     * @throws IOException if the path can't be opened
     */
    static int getKind(final Path path) throws IOException {
      return getKind(path, NioWrappers.readAttributes(path, LinkOption.NOFOLLOW_LINKS));
    }

    static int getKindOrUnknown(final Path path) {
      try {
        return getKind(path);
      } catch (final IOException e) {
        return UNKNOWN;
      }
    }
  }
  /**
   * Container class for {@link Directory} entries. Contains both the path to which the path
   * corresponds along with a data value.
   *
   * @param <T> The value wrapped in the Entry
   */
  public interface Entry<T> extends Comparable<Entry<T>> {
    /**
     * Is the path represented by this Entry a directory?
     *
     * @return true if the underlying path is a directory
     */
    boolean isDirectory();
    /**
     * Is the path represented by this Entry a regular file?
     *
     * @return true if the underlying path is a regular file
     */
    boolean isFile();
    /**
     * Is the path represented by this Entry a symbolic link?
     *
     * @return true if the underlying path is a symbolic link
     */
    boolean isSymbolicLink();
    /**
     * Returns the kind of file, see {@link Entries}
     *
     * @return the kind of file to which this corresponds
     */
    int getKind();
    /**
     * Get the value associated with this entry
     *
     * @return the value associated with this entry
     * @throws NullPointerException if the value could not have been computed due to an IOException.
     */
    T getValue() throws NullPointerException;
    /**
     * Get the path associated with this entry
     *
     * @return the path associated with this entry
     */
    Path getPath();
    /**
     * Returns the value of this entry or a default if it is null
     *
     * @param t The default value
     * @return the underlying value if not null, otherwise the default
     */
    T getValueOrDefault(final T t);

    /**
     * Getter for the IOException that may have thrown computing the value for the Entry
     *
     * @return the IOException thrown attempting to compute the value
     * @throws NullPointerException if no IOException was thrown computing the value
     */
    IOException getIOException() throws NullPointerException;

    /**
     * Resolves the path of this entry to the provided absolute path
     *
     * @param other The path to resolve against
     * @return a new Entry with the same value, exception and kind
     */
    Entry<T> resolvedFrom(final Path other);

    /**
     * Resolves the path of this entry to the provided absolute path
     *
     * @param other The path to resolve against
     * @param kind The kind of the entry
     * @return a new Entry with the same value, exception but the kind replaced by the input kind
     */
    Entry<T> resolvedFrom(final Path other, final int kind);
  }

  abstract static class EntryImpl<T> implements Entry<T> {
    private final int kind;
    private final Path path;

    EntryImpl(final Path path, final int kind) {
      this.path = path;
      this.kind = kind;
    }

    @Override
    public boolean isDirectory() {
      return is(Entries.DIRECTORY) || (is(Entries.UNKNOWN) && Files.isDirectory(path));
    }

    @Override
    public boolean isFile() {
      return is(Entries.FILE) || (is(Entries.UNKNOWN) && Files.isRegularFile(path));
    }

    @Override
    public boolean isSymbolicLink() {
      return is(Entries.LINK) || (is(Entries.UNKNOWN) && Files.isRegularFile(path));
    }

    @Override
    public int getKind() {
      return kind;
    }

    @Override
    public Path getPath() {
      return path;
    }

    @Override
    public int compareTo(final Entry<T> that) {
      return this.getPath().compareTo(that.getPath());
    }

    private boolean is(final int kind) {
      return (kind & this.kind) != 0;
    }

    @Override
    public int hashCode() {
      final T value = getValue();
      return path.hashCode() ^ (value == null ? 0 : value.hashCode());
    }

    @Override
    public boolean equals(final Object other) {
      return other instanceof Entry<?>
          && ((Entry<?>) other).getPath().equals(getPath())
          && (getValue() != null && getValue().equals(((Entry<?>) other).getValue())
              || ((Entry<?>) other).getIOException() != null);
    }
  }

  private static final class ValidEntry<T> extends EntryImpl<T> {
    private final T value;

    /**
     * Returns the value of this entry. The value may be null, so in general it is better to use
     * {@link Entry#getValueOrDefault}.
     *
     * @return the value
     * @throws NullPointerException if the value is null
     */
    @Override
    public T getValue() throws NullPointerException {
      if (value == null) throw new NullPointerException();
      return value;
    }

    /**
     * Returns the value of this entry or a default if it is null
     *
     * @param t The nullable value
     * @return the value
     */
    @Override
    public T getValueOrDefault(final T t) {
      return value == null ? t : value;
    }

    /**
     * Get the IOException thrown trying to compute the value for this entry
     *
     * @return ehe IOException thrown trying to convert the path to a value. Will be null if no
     *     exception was thrown
     */
    @Override
    public IOException getIOException() throws NullPointerException {
      throw new NullPointerException();
    }

    /**
     * Create a new Entry
     *
     * @param path The path to which this entry corresponds blah
     * @param value The {@code path} derived value for this entry
     * @param kind The type of file that this entry represents. In the case of symbolic links, it
     *     can be both a link and a directory or file.
     */
    ValidEntry(final Path path, final int kind, final T value) {
      super(path, kind);
      this.value = value;
    }

    /**
     * Create a new Entry using the FileSystem to check if the Entry is for a directory
     *
     * @param path The path to which this entry corresponds
     * @param value The {@code path} derived value for this entry
     */
    ValidEntry(final Path path, final T value) {
      this(path, Entries.getKindOrUnknown(path), value);
    }

    /**
     * Resolve a Entry for a relative {@code path}
     *
     * @param other The path to resolve {@code path} against
     * @return A Entry where the {@code path</code> has been resolved against <code>other}
     */
    @SuppressWarnings("unchecked")
    public Entry<T> resolvedFrom(Path other) {
      return new ValidEntry<>(other.resolve(getPath()), this.getKind(), this.value);
    }
    /**
     * Resolve a Entry for a relative {@code path</code> where <code>isDirectory} is known in
     * advance
     *
     * @param other The path to resolve {@code path} against
     * @param kind The known kind of the file
     * @return A Entry where the {@code path</code> has been resolved against <code>other}
     */
    @SuppressWarnings("unchecked")
    public Entry<T> resolvedFrom(Path other, final int kind) {
      return new ValidEntry<>(other.resolve(getPath()), kind, this.value);
    }

    @Override
    public String toString() {
      return "ValidEntry(" + getPath() + ", " + value + ")";
    }

    @Override
    public int compareTo(Entry<T> that) {
      return this.getPath().compareTo(that.getPath());
    }
  }

  private static class InvalidEntry<T> extends EntryImpl<T> {
    private final IOException exception;

    InvalidEntry(final Path path, final int kind, final IOException exception) {
      super(path, kind);
      this.exception = exception;
    }

    @Override
    public T getValue() throws NullPointerException {
      throw new NullPointerException();
    }

    @Override
    public T getValueOrDefault(T t) {
      return t;
    }

    @Override
    public IOException getIOException() throws NullPointerException {
      return exception;
    }

    @Override
    public Entry<T> resolvedFrom(final Path other) {
      return new InvalidEntry<>(other.resolve(getPath()), getKind(), exception);
    }

    @Override
    public Entry<T> resolvedFrom(Path other, int kind) {
      return new InvalidEntry<>(other.resolve(getPath()), kind, exception);
    }
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
   * Filter {@link Directory.Entry} elements
   *
   * @param <T> The data value type for the {@link Directory.Entry}
   */
  public interface EntryFilter<T> {
    boolean accept(Entry<? extends T> entry);
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
   * Callback to fire when an error is encountered. This will generally be a {@link
   * java.nio.file.FileSystemLoopException}.
   */
  public interface OnError {

    /**
     * Apply callback for error
     *
     * @param path The path that induced the error
     * @param exception The encountered error
     */
    void apply(final Path path, final IOException exception);
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

    void onError(final Path path, final IOException exception);
  }
}
