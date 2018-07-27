package com.swoval.files;

import static com.swoval.functional.Either.leftProjection;
import static com.swoval.functional.Filters.AllPass;

import com.swoval.files.FileTreeDataViews.Converter;
import com.swoval.files.FileTreeDataViews.Entry;
import com.swoval.files.FileTreeViews.Updates;
import com.swoval.functional.Either;
import com.swoval.functional.Filter;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
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
 * CachedDirectory can be fully recursive as the subdirectories are themselves stored as recursive
 * (when the CachedDirectory is initialized without the recursive toggle, the subdirectories are
 * stored as {@link Entry} instances. The primary use case is the implementation of {@link
 * FileTreeRepository} and {@link NioPathWatcher}. Directly handling CachedDirectory instances is
 * discouraged because it is inherently mutable so it's better to let the FileTreeRepository manage
 * it and query the cache rather than CachedDirectory directly.
 *
 * <p>The CachedDirectory should cache all of the files and subdirectories up the maximum depth. A
 * maximum depth of zero means that the CachedDirectory should cache the subdirectories, but not
 * traverse them. A depth {@code < 0} means that it should not cache any files or subdirectories
 * within the directory. In the event that a loop is created by symlinks, the CachedDirectory will
 * include the symlink that completes the loop, but will not descend further (inducing a loop).
 *
 * @param <T> the cache value type.
 */
class CachedDirectoryImpl<T> implements CachedDirectory<T> {
  private final int depth;
  private final Path path;
  private final Path realPath;
  private final FileTreeView fileTreeView;
  private final Converter<T> converter;
  private final AtomicReference<Entry<T>> _cacheEntry;
  private final Object lock = new Object();
  private final Map<Path, CachedDirectoryImpl<T>> subdirectories = new HashMap<>();
  private final Map<Path, Entry<T>> files = new HashMap<>();
  private final Filter<? super TypedPath> pathFilter;

  /**
   * Returns the name components of a path in an array.
   *
   * @param path The path from which we extract the parts.
   * @return Empty array if path is an empty relative path, otherwise return the name parts.
   */
  private static List<Path> parts(final Path path) {
    final Iterator<Path> it = path.iterator();
    final List<Path> result = new ArrayList<>();
    while (it.hasNext()) result.add(it.next());
    return result;
  }

  public int getMaxDepth() {
    return depth;
  }

  @Override
  public Path getPath() {
    return path;
  }

  @Override
  public List<TypedPath> list(int maxDepth, Filter<? super TypedPath> filter) {
    return list(getPath(), maxDepth, filter);
  }

  @Override
  public List<TypedPath> list(
      final Path path, final int maxDepth, final Filter<? super TypedPath> filter) {
    final Either<Entry<T>, CachedDirectoryImpl<T>> findResult = find(path);
    if (findResult != null) {
      if (findResult.isRight()) {
        final List<TypedPath> result = new ArrayList<>();
        findResult
            .get()
            .<TypedPath>listImpl(
                maxDepth,
                filter,
                result,
                new TotalFunction<Entry<T>, TypedPath>() {
                  @Override
                  public TypedPath apply(Entry<T> entry) {
                    return TypedPaths.getDelegate(entry.getPath(), entry);
                  }
                });
        return result;
      } else {
        final Entry<T> entry = leftProjection(findResult).getValue();
        final List<TypedPath> result = new ArrayList<>();
        if (entry != null && filter.accept(entry))
          result.add(TypedPaths.getDelegate(entry.getPath(), entry));
        return result;
      }
    } else {
      return new ArrayList<>();
    }
  }

  @Override
  public List<Entry<T>> listEntries(
      final Path path, final int maxDepth, final Filter<? super Entry<T>> filter) {
    final Either<Entry<T>, CachedDirectoryImpl<T>> findResult = find(path);
    if (findResult != null) {
      if (findResult.isRight()) {
        final List<Entry<T>> result = new ArrayList<>();
        findResult
            .get()
            .<Entry<T>>listImpl(
                maxDepth,
                filter,
                result,
                new TotalFunction<Entry<T>, Entry<T>>() {
                  @Override
                  public Entry<T> apply(final Entry<T> entry) {
                    return entry;
                  }
                });
        return result;
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

  @Override
  public List<Entry<T>> listEntries(final int maxDepth, final Filter<? super Entry<T>> filter) {
    return listEntries(getPath(), maxDepth, filter);
  }

  @Override
  public Entry<T> getEntry() {
    return _cacheEntry.get();
  }

  @Override
  public void close() {
    synchronized (this.lock) {
      final Iterator<CachedDirectoryImpl<T>> it = subdirectories.values().iterator();
      while (it.hasNext()) it.next().close();
      subdirectories.clear();
      files.clear();
    }
  }

  @SuppressWarnings("unchecked")
  CachedDirectoryImpl(
      final Path path,
      final Path realPath,
      final Converter<T> converter,
      final int depth,
      final Filter<? super TypedPath> filter,
      final FileTreeView fileTreeView) {
    this.path = path;
    this.realPath = realPath;
    this.converter = converter;
    this.depth = depth;
    this._cacheEntry = new AtomicReference<>(null);
    this.pathFilter = filter;
    final TypedPath typedPath = TypedPaths.get(path);
    this._cacheEntry.set(
        Entries.get(TypedPaths.getDelegate(path, typedPath), converter, typedPath));
    this.fileTreeView = fileTreeView;
  }

  /**
   * Updates the CachedDirectory entry for a particular typed path.
   *
   * @param typedPath the path to update
   * @return a list of updates for the path. When the path is new, the updates have the
   *     oldCachedPath field set to null and will contain all of the children of the new path when
   *     it is a directory. For an existing path, the List contains a single Updates that contains
   *     the previous and new {@link Entry}.
   * @throws IOException when the updated Path is a directory and an IOException is encountered
   *     traversing the directory.
   */
  @Override
  public Updates<T> update(final TypedPath typedPath, final Executor.Thread thread) {
    return pathFilter.accept(typedPath)
        ? updateImpl(
            typedPath.getPath().equals(this.path)
                ? new ArrayList<Path>()
                : parts(this.path.relativize(typedPath.getPath())),
            typedPath)
        : new Updates<T>();
  }

  /**
   * Remove a path from the directory.
   *
   * @param path the path to remove
   * @return a List containing the Entry instances for the removed path. The result also contains
   *     the cache entries for any children of the path when the path is a non-empty directory.
   */
  public List<Entry<T>> remove(final Path path, final Executor.Thread thread) {
    if (path.isAbsolute() && path.startsWith(this.path)) {
      return removeImpl(parts(this.path.relativize(path)));
    } else {
      return new ArrayList<>();
    }
  }

  @Override
  public String toString() {
    return "CachedDirectory(" + path + ", maxDepth = " + depth + ")";
  }

  private int subdirectoryDepth() {
    return depth == Integer.MAX_VALUE ? depth : depth > 0 ? depth - 1 : 0;
  }

  @SuppressWarnings({"unchecked", "EmptyCatchBlock"})
  private void addDirectory(
      final CachedDirectoryImpl<T> currentDir,
      final TypedPath typedPath,
      final Updates<T> updates) {
    final Path path = typedPath.getPath();
    final CachedDirectoryImpl<T> dir =
        new CachedDirectoryImpl<>(
            path,
            typedPath.toRealPath(),
            converter,
            currentDir.subdirectoryDepth(),
            pathFilter,
            fileTreeView);
    boolean exists = true;
    try {
      dir.init();
    } catch (final NoSuchFileException nsfe) {
      exists = false;
    } catch (final IOException e) {
    }
    final Map<Path, Entry<T>> oldEntries = new HashMap<>();
    final Map<Path, Entry<T>> newEntries = new HashMap<>();
    if (exists) {
      final CachedDirectoryImpl<T> previous =
          currentDir.subdirectories.put(path.getFileName(), dir);
      if (previous != null) {
        oldEntries.put(previous.realPath, previous.getEntry());
        final Iterator<Entry<T>> entryIterator =
            previous.listEntries(Integer.MAX_VALUE, AllPass).iterator();
        while (entryIterator.hasNext()) {
          final Entry<T> entry = entryIterator.next();
          oldEntries.put(entry.getPath(), entry);
        }
        previous.close();
      }
      newEntries.put(dir.realPath, dir.getEntry());
      final Iterator<Entry<T>> it = dir.listEntries(Integer.MAX_VALUE, AllPass).iterator();
      while (it.hasNext()) {
        final Entry<T> entry = it.next();
        newEntries.put(entry.getPath(), entry);
      }
    } else {
      final CachedDirectoryImpl<T> previous = currentDir.subdirectories.get(path.getFileName());
      if (previous != null) {
        oldEntries.put(previous.realPath, Entries.setExists(previous.getEntry(), false));
        final Iterator<Entry<T>> entryIterator =
            previous.listEntries(Integer.MAX_VALUE, AllPass).iterator();
        while (entryIterator.hasNext()) {
          final Entry<T> entry = entryIterator.next();
          oldEntries.put(entry.getPath(), entry);
        }
      }
    }
    MapOps.diffDirectoryEntries(oldEntries, newEntries, updates);
  }

  private boolean isLoop(final Path path, final Path realPath) {
    return path.startsWith(realPath) && !path.equals(realPath);
  }

  private Updates<T> updateImpl(final List<Path> parts, final TypedPath typedPath) {
    final Updates<T> result = new Updates<>();
    if (!parts.isEmpty()) {
      final Iterator<Path> it = parts.iterator();
      CachedDirectoryImpl<T> currentDir = this;
      while (it.hasNext() && currentDir != null && currentDir.depth >= 0) {
        final Path p = it.next();
        if (p.toString().isEmpty()) return result;
        final Path resolved = currentDir.path.resolve(p);
        final Path realPath = typedPath.getPath();
        if (!it.hasNext()) {
          // We will always return from this block
          synchronized (currentDir.lock) {
            final boolean isDirectory = typedPath.isDirectory();
            if (!isDirectory || currentDir.depth <= 0 || isLoop(resolved, realPath)) {
              final CachedDirectoryImpl<T> previousCachedDirectoryImpl =
                  isDirectory ? currentDir.subdirectories.get(p) : null;
              final Entry<T> oldEntry =
                  previousCachedDirectoryImpl != null
                      ? previousCachedDirectoryImpl.getEntry()
                      : currentDir.files.get(p);
              final Entry<T> newEntry =
                  Entries.get(
                      TypedPaths.getDelegate(p, typedPath),
                      converter,
                      TypedPaths.getDelegate(resolved, typedPath));
              if (isDirectory) {
                final CachedDirectoryImpl<T> previous =
                    currentDir.subdirectories.put(
                        p,
                        new CachedDirectoryImpl<>(
                            resolved, realPath, converter, -1, pathFilter, fileTreeView));
                if (previous != null) previous.close();
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
              addDirectory(currentDir, typedPath, result);
              return result;
            }
          }
        } else {
          synchronized (currentDir.lock) {
            final CachedDirectoryImpl<T> dir = currentDir.subdirectories.get(p);
            if (dir == null && currentDir.depth > 0) {
              addDirectory(
                  currentDir,
                  TypedPaths.getDelegate(currentDir.path.resolve(p), typedPath),
                  result);
            }
            currentDir = dir;
          }
        }
      }
    } else if (typedPath.isDirectory()) {
      final List<Entry<T>> oldEntries = listEntries(getMaxDepth(), AllPass);
      try {
        init();
      } catch (final IOException e) {
      }
      final List<Entry<T>> newEntries = listEntries(getMaxDepth(), AllPass);
      MapOps.diffDirectoryEntries(oldEntries, newEntries, result);
    } else {
      final Entry<T> oldEntry = getEntry();
      final TypedPath tp = TypedPaths.getDelegate(realPath, typedPath);
      final Entry<T> newEntry = Entries.get(tp, converter, tp);
      _cacheEntry.set(newEntry);
      result.onUpdate(oldEntry, getEntry());
    }
    return result;
  }

  private Either<Entry<T>, CachedDirectoryImpl<T>> findImpl(final List<Path> parts) {
    final Iterator<Path> it = parts.iterator();
    CachedDirectoryImpl<T> currentDir = this;
    Either<Entry<T>, CachedDirectoryImpl<T>> result = null;
    while (it.hasNext() && currentDir != null && result == null) {
      final Path p = it.next();
      if (!it.hasNext()) {
        synchronized (currentDir.lock) {
          final CachedDirectoryImpl<T> subdir = currentDir.subdirectories.get(p);
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

  private Either<Entry<T>, CachedDirectoryImpl<T>> find(final Path path) {
    if (path.equals(this.path)) {
      return Either.right(this);
    } else if (!path.isAbsolute()) {
      return findImpl(parts(path));
    } else if (path.startsWith(this.path)) {
      return findImpl(parts(this.path.relativize(path)));
    } else {
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  private <R> void listImpl(
      final int maxDepth,
      final Filter<? super R> filter,
      final List<R> result,
      final TotalFunction<Entry<T>, R> function) {
    if (this.depth < 0 || maxDepth < 0) {
      result.add((R) this.getEntry());
    } else {
      final Collection<Entry<T>> files;
      final Collection<CachedDirectoryImpl<T>> subdirectories;
      synchronized (this.lock) {
        files = new ArrayList<>(this.files.values());
        subdirectories = new ArrayList<>(this.subdirectories.values());
      }
      final Iterator<Entry<T>> filesIterator = files.iterator();
      while (filesIterator.hasNext()) {
        final Entry<T> entry = filesIterator.next();
        final R resolved = function.apply(Entries.resolve(getPath(), entry));
        if (filter.accept(resolved)) result.add(resolved);
      }
      final Iterator<CachedDirectoryImpl<T>> subdirIterator = subdirectories.iterator();
      while (subdirIterator.hasNext()) {
        final CachedDirectoryImpl<T> subdir = subdirIterator.next();
        final Entry<T> entry = subdir.getEntry();
        final R resolved = function.apply(Entries.resolve(getPath(), entry));
        if (filter.accept(resolved)) result.add(resolved);
        if (maxDepth > 0) subdir.<R>listImpl(maxDepth - 1, filter, result, function);
      }
    }
  }

  private List<Entry<T>> removeImpl(final List<Path> parts) {
    final List<Entry<T>> result = new ArrayList<>();
    final Iterator<Path> it = parts.iterator();
    CachedDirectoryImpl<T> currentDir = this;
    while (it.hasNext() && currentDir != null) {
      final Path p = it.next();
      if (!it.hasNext()) {
        synchronized (currentDir.lock) {
          final Entry<T> entry = currentDir.files.remove(p);
          if (entry != null) {
            result.add(Entries.resolve(currentDir.path, entry));
          } else {
            final CachedDirectoryImpl<T> dir = currentDir.subdirectories.remove(p);
            if (dir != null) {
              result.addAll(dir.listEntries(Integer.MAX_VALUE, AllPass));
              result.add(dir.getEntry());
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

  CachedDirectoryImpl<T> init() throws IOException {
    subdirectories.clear();
    files.clear();
    if (depth >= 0 && (!this.path.startsWith(this.realPath) || this.path.equals(this.realPath))) {
      synchronized (lock) {
        final Iterator<TypedPath> it = fileTreeView.list(this.path, 0, pathFilter).iterator();
        while (it.hasNext()) {
          final TypedPath file = it.next();
          if (pathFilter.accept(file)) {
            final Path path = file.getPath();
            final Path realPath = file.toRealPath();
            final Path key = this.path.relativize(path).getFileName();
            if (file.isDirectory()) {
              if (depth > 0) {
                if (!file.isSymbolicLink() || !isLoop(path, realPath)) {
                  final CachedDirectoryImpl<T> dir =
                      new CachedDirectoryImpl<>(
                          path, realPath, converter, subdirectoryDepth(), pathFilter, fileTreeView);
                  try {
                    dir.init();
                  } catch (final IOException e) {
                  }
                  subdirectories.put(key, dir);
                } else {
                  subdirectories.put(
                      key,
                      new CachedDirectoryImpl<>(
                          path, realPath, converter, -1, pathFilter, fileTreeView));
                }
              } else {
                files.put(key, Entries.get(TypedPaths.getDelegate(key, file), converter, file));
              }
            } else {
              files.put(key, Entries.get(TypedPaths.getDelegate(key, file), converter, file));
            }
          }
        }
      }
    }
    return this;
  }
}
