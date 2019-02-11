package com.swoval.files;

import static com.swoval.functional.Either.leftProjection;
import static com.swoval.functional.Filters.AllPass;

import com.swoval.files.FileTreeDataViews.Converter;
import com.swoval.files.FileTreeDataViews.Entry;
import com.swoval.files.FileTreeViews.Updates;
import com.swoval.functional.Either;
import com.swoval.functional.Filter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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
  private final AtomicReference<Entry<T>> _cacheEntry;
  private final int depth;
  private final TypedPath typedPath;
  private final FileTreeView fileTreeView;
  private final boolean followLinks;
  private final Converter<T> converter;
  private final Filter<? super TypedPath> pathFilter;
  private final LockableMap<Path, CachedDirectoryImpl<T>> subdirectories = new LockableMap<>();
  private final Map<Path, Entry<T>> files = new HashMap<>();

  private interface ListTransformer<T, R> {
    R apply(final Entry<T> entry);
  }

  CachedDirectoryImpl(
      final TypedPath typedPath,
      final Converter<T> converter,
      final int depth,
      final Filter<? super TypedPath> filter,
      final boolean followLinks,
      final FileTreeView fileTreeView) {
    this.typedPath = typedPath;
    this.converter = converter;
    this.depth = depth;
    this._cacheEntry = new AtomicReference<>(null);
    this.pathFilter = filter;
    this._cacheEntry.set(Entries.get(this.typedPath, converter, this.typedPath));
    this.fileTreeView = fileTreeView;
    this.followLinks = followLinks;
  }

  CachedDirectoryImpl(
      final TypedPath typedPath,
      final Converter<T> converter,
      final int depth,
      final Filter<? super TypedPath> filter,
      final boolean followLinks) {
    this(typedPath, converter, depth, filter, followLinks, FileTreeViews.getDefault(followLinks));
  }

  /**
   * Returns the name components of a path in an array.
   *
   * @param path The path from which we extract the parts.
   * @return Empty array if the path is an empty relative path, otherwise return the name parts.
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
    return typedPath.getPath();
  }

  @Override
  public TypedPath getTypedPath() {
    return typedPath;
  }

  @Override
  public List<TypedPath> list(int maxDepth, Filter<? super TypedPath> filter) {
    return list(getPath(), maxDepth, filter);
  }

  @Override
  public List<TypedPath> list(
      final Path path, final int maxDepth, final Filter<? super TypedPath> filter) {
    if (this.subdirectories.lock()) {
      try {
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
                    new ListTransformer<T, TypedPath>() {
                      @Override
                      public TypedPath apply(final Entry<T> entry) {
                        return TypedPaths.getDelegate(
                            entry.getTypedPath().getPath(), entry.getTypedPath());
                      }
                    });
            return result;
          } else {
            final Entry<T> entry = leftProjection(findResult).getValue();
            final List<TypedPath> result = new ArrayList<>();
            if (entry != null && filter.accept(entry.getTypedPath()) && maxDepth == -1)
              result.add(
                  TypedPaths.getDelegate(entry.getTypedPath().getPath(), entry.getTypedPath()));
            return result;
          }
        } else {
          return Collections.emptyList();
        }
      } finally {
        this.subdirectories.unlock();
      }
    } else {
      return Collections.emptyList();
    }
  }

  @Override
  public List<Entry<T>> listEntries(
      final Path path, final int maxDepth, final Filter<? super Entry<T>> filter) {
    if (this.subdirectories.lock()) {
      try {
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
                    new ListTransformer<T, Entry<T>>() {
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
          return Collections.emptyList();
        }
      } finally {
        this.subdirectories.unlock();
      }
    } else {
      return Collections.emptyList();
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
    subdirectories.clear();
    files.clear();
  }

  /**
   * Updates the CachedDirectory entry for a particular typed typedPath.
   *
   * @param typedPath the typedPath to update
   * @return a list of updates for the typedPath. When the typedPath is new, the updates have the
   *     oldCachedPath field set to null and will contain all of the children of the new typedPath
   *     when it is a directory. For an existing typedPath, the List contains a single Updates that
   *     contains the previous and new {@link Entry}.
   * @throws IOException when the updated Path is a directory and an IOException is encountered
   *     traversing the directory.
   */
  @Override
  public Updates<T> update(final TypedPath typedPath) throws IOException {
    return update(typedPath, true);
  }

  /**
   * Updates the CachedDirectory entry for a particular typed typedPath.
   *
   * @param typedPath the typedPath to update
   * @param rescanDirectoriesOnUpdate if true, rescan the entire subtree for this directory. This
   *     can be very expensive.
   * @return a list of updates for the typedPath. When the typedPath is new, the updates have the
   *     oldCachedPath field set to null and will contain all of the children of the new typedPath
   *     when it is a directory. For an existing typedPath, the List contains a single Updates that
   *     contains the previous and new {@link Entry}.
   * @throws IOException when the updated Path is a directory and an IOException is encountered
   *     traversing the directory.
   */
  @Override
  public Updates<T> update(final TypedPath typedPath, final boolean rescanDirectoriesOnUpdate)
      throws IOException {
    if (pathFilter.accept(typedPath)) {
      if (typedPath.exists()) {
        return updateImpl(
            typedPath.getPath().equals(this.getPath())
                ? new ArrayList<Path>()
                : parts(this.getPath().relativize(typedPath.getPath())),
            typedPath,
            rescanDirectoriesOnUpdate);
      } else {
        final Iterator<Entry<T>> it = remove(typedPath.getPath()).iterator();
        final Updates<T> result = new Updates<>();
        while (it.hasNext()) result.onDelete(it.next());
        return result;
      }
    } else {
      return new Updates<T>();
    }
  }

  /**
   * Remove a path from the directory.
   *
   * @param path the path to remove
   * @return a List containing the Entry instances for the removed path. The result also contains
   *     the cache entries for any children of the path when the path is a non-empty directory.
   */
  public List<Entry<T>> remove(final Path path) {
    if (path.isAbsolute() && path.startsWith(this.getPath())) {
      return removeImpl(parts(this.getPath().relativize(path)));
    } else {
      return Collections.emptyList();
    }
  }

  @Override
  public String toString() {
    return "CachedDirectory(" + getPath() + ", maxDepth = " + depth + ")";
  }

  private int subdirectoryDepth() {
    return depth == Integer.MAX_VALUE ? depth : depth > 0 ? depth - 1 : 0;
  }

  @SuppressWarnings("EmptyCatchBlock")
  private void addDirectory(
      final CachedDirectoryImpl<T> currentDir,
      final TypedPath typedPath,
      final Updates<T> updates) {
    final Path path = typedPath.getPath();
    final CachedDirectoryImpl<T> dir =
        new CachedDirectoryImpl<>(
            typedPath, converter, currentDir.subdirectoryDepth(), pathFilter, followLinks);
    boolean exists = true;
    try {
      final TypedPath tp = dir.getEntry().getTypedPath();
      if (tp.isDirectory() && (followLinks || !tp.isSymbolicLink())) dir.init();
      else {
        currentDir.files.put(tp.getPath(), dir.getEntry());
        exists = false;
      }
    } catch (final NoSuchFileException nsfe) {
      exists = false;
    } catch (final IOException e) {
    }
    if (exists) {
      final Map<Path, Entry<T>> oldEntries = new HashMap<>();
      final Map<Path, Entry<T>> newEntries = new HashMap<>();
      final CachedDirectoryImpl<T> previous =
          currentDir.subdirectories.put(path.getFileName(), dir);
      if (previous != null) {
        oldEntries.put(previous.getPath(), previous.getEntry());
        final Iterator<Entry<T>> entryIterator =
            previous.listEntries(Integer.MAX_VALUE, AllPass).iterator();
        while (entryIterator.hasNext()) {
          final Entry<T> entry = entryIterator.next();
          oldEntries.put(entry.getTypedPath().getPath(), entry);
        }
        previous.close();
      }
      newEntries.put(dir.getPath(), dir.getEntry());
      final Iterator<Entry<T>> it = dir.listEntries(Integer.MAX_VALUE, AllPass).iterator();
      while (it.hasNext()) {
        final Entry<T> entry = it.next();
        newEntries.put(entry.getTypedPath().getPath(), entry);
      }
      MapOps.diffDirectoryEntries(oldEntries, newEntries, updates);
    } else {
      final Iterator<Entry<T>> it = remove(dir.getPath()).iterator();
      while (it.hasNext()) {
        updates.onDelete(it.next());
      }
    }
  }

  private boolean isLoop(final Path path, final Path realPath) {
    return path.startsWith(realPath) && !path.equals(realPath);
  }

  private void updateDirectory(
      final CachedDirectoryImpl<T> dir, final Updates<T> result, final Entry<T> entry) {
    result.onUpdate(dir.getEntry(), entry);
    dir._cacheEntry.set(entry);
  }

  private Updates<T> updateImpl(
      final List<Path> parts, final TypedPath typedPath, final boolean rescanOnDirectoryUpdate)
      throws IOException {
    final Updates<T> result = new Updates<>();
    if (this.subdirectories.lock()) {
      try {
        if (!parts.isEmpty()) {
          final Iterator<Path> it = parts.iterator();
          CachedDirectoryImpl<T> currentDir = this;
          while (it.hasNext() && currentDir != null && currentDir.depth >= 0) {
            final Path p = it.next();
            if (p.toString().isEmpty()) return result;
            final Path resolved = currentDir.getPath().resolve(p);
            if (!it.hasNext()) {
              // We will always return from this block
              final boolean isDirectory =
                  typedPath.isDirectory() && (followLinks || !typedPath.isSymbolicLink());
              if (!isDirectory
                  || currentDir.depth <= 0
                  || isLoop(resolved, TypedPaths.expanded(typedPath))) {
                final CachedDirectoryImpl<T> previousCachedDirectoryImpl =
                    isDirectory ? currentDir.subdirectories.get(p) : null;
                final Entry<T> fileEntry = currentDir.files.remove(p);
                final Entry<T> oldEntry =
                    fileEntry != null
                        ? fileEntry
                        : previousCachedDirectoryImpl != null
                            ? previousCachedDirectoryImpl.getEntry()
                            : null;
                final Entry<T> newEntry =
                    Entries.get(
                        TypedPaths.getDelegate(resolved, typedPath),
                        converter,
                        TypedPaths.getDelegate(resolved, typedPath));
                if (isDirectory) {
                  final CachedDirectoryImpl<T> previous = currentDir.subdirectories.get(p);
                  if (previous == null || rescanOnDirectoryUpdate) {
                    currentDir.subdirectories.put(
                        p,
                        new CachedDirectoryImpl<>(
                            TypedPaths.getDelegate(resolved, typedPath),
                            converter,
                            -1,
                            pathFilter,
                            followLinks));
                  } else {
                    updateDirectory(previous, result, newEntry);
                  }
                } else {
                  currentDir.files.put(p, newEntry);
                }
                final Entry<T> oldResolvedEntry =
                    oldEntry == null ? null : Entries.resolve(currentDir.getPath(), oldEntry);
                if (oldResolvedEntry == null) {
                  result.onCreate(Entries.resolve(currentDir.getPath(), newEntry));
                } else {
                  result.onUpdate(
                      oldResolvedEntry, Entries.resolve(currentDir.getPath(), newEntry));
                }
                return result;
              } else {
                final CachedDirectoryImpl<T> previous = currentDir.subdirectories.get(p);
                if (previous == null || rescanOnDirectoryUpdate) {
                  addDirectory(currentDir, typedPath, result);
                } else {
                  updateDirectory(previous, result, Entries.get(typedPath, converter, typedPath));
                }
                return result;
              }
            } else {
              final CachedDirectoryImpl<T> dir = currentDir.subdirectories.get(p);
              if (dir == null && currentDir.depth > 0) {
                addDirectory(currentDir, TypedPaths.get(currentDir.getPath().resolve(p)), result);
              }
              currentDir = dir;
            }
          }
        } else if (typedPath.isDirectory() && rescanOnDirectoryUpdate) {
          final List<Entry<T>> oldEntries = listEntries(getMaxDepth(), AllPass);
          init();
          final List<Entry<T>> newEntries = listEntries(getMaxDepth(), AllPass);
          MapOps.diffDirectoryEntries(oldEntries, newEntries, result);
        } else {
          final Entry<T> oldEntry = getEntry();
          final TypedPath tp =
              TypedPaths.getDelegate(TypedPaths.expanded(getTypedPath()), typedPath);
          final Entry<T> newEntry = Entries.get(typedPath, converter, tp);
          _cacheEntry.set(newEntry);
          result.onUpdate(oldEntry, getEntry());
        }
      } finally {
        this.subdirectories.unlock();
      }
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
        final CachedDirectoryImpl<T> subdir = currentDir.subdirectories.get(p);
        if (subdir != null) {
          result = Either.right(subdir);
        } else {
          final Entry<T> entry = currentDir.files.get(p);
          if (entry != null) result = Either.left(Entries.resolve(currentDir.getPath(), entry));
        }
      } else {
        currentDir = currentDir.subdirectories.get(p);
      }
    }
    return result;
  }

  private Either<Entry<T>, CachedDirectoryImpl<T>> find(final Path path) {
    if (path.equals(this.getPath())) {
      return Either.right(this);
    } else if (!path.isAbsolute()) {
      return findImpl(parts(path));
    } else if (path.startsWith(this.getPath())) {
      return findImpl(parts(this.getPath().relativize(path)));
    } else {
      return null;
    }
  }

  private <R> void listImpl(
      final int maxDepth,
      final Filter<? super R> filter,
      final List<R> result,
      final ListTransformer<T, R> function) {
    if (this.depth < 0 || maxDepth < 0) {
      result.add(function.apply(this.getEntry()));
    } else {
      if (subdirectories.lock()) {
        try {
          final Collection<Entry<T>> files = new ArrayList<>(this.files.values());
          final Collection<CachedDirectoryImpl<T>> subdirectories =
              new ArrayList<>(this.subdirectories.values());
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
        } finally {
          subdirectories.unlock();
        }
      }
    }
  }

  private List<Entry<T>> removeImpl(final List<Path> parts) {
    final List<Entry<T>> result = new ArrayList<>();
    if (this.subdirectories.lock()) {
      try {
        final Iterator<Path> it = parts.iterator();
        CachedDirectoryImpl<T> currentDir = this;
        while (it.hasNext() && currentDir != null) {
          final Path p = it.next();
          if (!it.hasNext()) {
            final Entry<T> entry = currentDir.files.remove(p);
            if (entry != null) {
              result.add(Entries.resolve(currentDir.getPath(), entry));
            } else {
              final CachedDirectoryImpl<T> dir = currentDir.subdirectories.remove(p);
              if (dir != null) {
                result.addAll(dir.listEntries(Integer.MAX_VALUE, AllPass));
                result.add(dir.getEntry());
              }
            }
          } else {
            currentDir = currentDir.subdirectories.get(p);
          }
        }
      } finally {
        this.subdirectories.unlock();
      }
    }
    return result;
  }

  CachedDirectoryImpl<T> init() throws IOException {
    return init(typedPath.getPath());
  }

  private CachedDirectoryImpl<T> init(final Path realPath) throws IOException {
    if (subdirectories.lock()) {
      try {
        subdirectories.clear();
        files.clear();
        if (depth >= 0
            && (!this.getPath().startsWith(realPath) || this.getPath().equals(realPath))) {
          final Iterator<TypedPath> it =
              fileTreeView.list(this.getPath(), 0, pathFilter).iterator();
          while (it.hasNext()) {
            final TypedPath file = it.next();
            final Path path = file.getPath();
            final Path key = this.typedPath.getPath().relativize(path).getFileName();
            if (file.isDirectory()) {
              if (depth > 0) {
                if (!file.isSymbolicLink() || !isLoop(path, TypedPaths.expanded(file))) {
                  final CachedDirectoryImpl<T> dir =
                      new CachedDirectoryImpl<>(
                          file, converter, subdirectoryDepth(), pathFilter, followLinks);
                  try {
                    dir.init();
                    subdirectories.put(key, dir);
                  } catch (final IOException e) {
                    if (Files.exists(dir.getPath())) {
                      subdirectories.put(key, dir);
                    }
                  }
                } else {
                  subdirectories.put(
                      key, new CachedDirectoryImpl<>(file, converter, -1, pathFilter, followLinks));
                }
              } else {
                files.put(key, Entries.get(TypedPaths.getDelegate(key, file), converter, file));
              }
            } else {
              files.put(key, Entries.get(TypedPaths.getDelegate(key, file), converter, file));
            }
          }
        }
      } finally {
        subdirectories.unlock();
      }
    }
    return this;
  }
}
