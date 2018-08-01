package com.swoval.files;

import static com.swoval.files.PathWatchers.Event.Kind.Create;
import static com.swoval.files.PathWatchers.Event.Kind.Delete;
import static com.swoval.files.PathWatchers.Event.Kind.Error;
import static com.swoval.files.PathWatchers.Event.Kind.Modify;
import static com.swoval.functional.Filters.AllPass;

import com.swoval.files.FileTreeDataViews.Converter;
import com.swoval.files.FileTreeDataViews.Entry;
import com.swoval.files.FileTreeRepositoryImpl.Callback;
import com.swoval.files.FileTreeViews.CacheObserver;
import com.swoval.files.FileTreeViews.ObservableCache;
import com.swoval.files.FileTreeViews.Observer;
import com.swoval.files.PathWatchers.Event;
import com.swoval.files.PathWatchers.Event.Kind;
import com.swoval.functional.Filter;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

class FileCacheDirectories<T> extends LockableMap<Path, CachedDirectory<T>> {
  FileCacheDirectories(final ReentrantLock lock) {
    super(new HashMap<Path, CachedDirectory<T>>(), lock);
  }
}

class FileCachePendingFiles extends Lockable {
  private final Set<Path> pendingFiles = new HashSet<>();

  FileCachePendingFiles(final ReentrantLock reentrantLock) {
    super(reentrantLock);
  }

  void clear() {
    if (lock()) {
      try {
        pendingFiles.clear();
      } finally {
        unlock();
      }
    }
  }

  boolean add(final Path path) {
    if (lock()) {
      try {
        return pendingFiles.add(path);
      } finally {
        unlock();
      }
    } else {
      return false;
    }
  }

  boolean remove(final Path path) {
    if (lock()) {
      try {
        return pendingFiles.remove(path);
      } finally {
        unlock();
      }
    } else {
      return false;
    }
  }
}

class FileCacheDirectoryTree<T> implements ObservableCache<T>, FileTreeDataView<T> {
  private final DirectoryRegistry directoryRegistry = new DirectoryRegistryImpl();
  private final Converter<T> converter;
  private final CacheObservers<T> observers = new CacheObservers<>();
  private final Executor callbackExecutor;
  private final boolean followLinks;
  private final AtomicBoolean closed = new AtomicBoolean(false);
  final SymlinkWatcher symlinkWatcher;

  FileCacheDirectoryTree(
      final Converter<T> converter,
      final Executor callbackExecutor,
      final SymlinkWatcher symlinkWatcher) {
    this.converter = converter;
    this.callbackExecutor = callbackExecutor;
    this.symlinkWatcher = symlinkWatcher;
    this.followLinks = symlinkWatcher != null;
    if (symlinkWatcher != null) {
      symlinkWatcher.addObserver(
          new Observer<Event>() {
            @Override
            public void onError(final Throwable t) {
              t.printStackTrace(System.err);
            }

            @Override
            public void onNext(final Event event) {
              handleEvent(event);
            }
          });
    }
    final ReentrantLock reentrantLock = new ReentrantLock();
    pendingFiles = new FileCachePendingFiles(reentrantLock);
    directories = new FileCacheDirectories<>(reentrantLock);
  }

  private final FileCacheDirectories<T> directories;
  private final FileCachePendingFiles pendingFiles;

  private final DirectoryRegistry READ_ONLY_DIRECTORY_REGISTRY =
      new DirectoryRegistry() {
        @Override
        public void close() {}

        @Override
        public boolean addDirectory(final Path path, final int maxDepth) {
          return false;
        }

        @Override
        public int maxDepthFor(final Path path) {
          return directoryRegistry.maxDepthFor(path);
        }

        @Override
        public List<Path> registered() {
          return directoryRegistry.registered();
        }

        @Override
        public void removeDirectory(final Path path) {}

        @Override
        public boolean acceptPrefix(final Path path) {
          return directoryRegistry.acceptPrefix(path);
        }

        @Override
        public boolean accept(final Path path) {
          return directoryRegistry.accept(path);
        }
      };

  DirectoryRegistry readOnlyDirectoryRegistry() {
    return READ_ONLY_DIRECTORY_REGISTRY;
  }

  void unregister(final Path path) {
    if (directories.lock()) {
      try {
        directoryRegistry.removeDirectory(path);
        if (!directoryRegistry.accept(path)) {
          final CachedDirectory<T> dir = find(path);
          if (dir != null) {
            if (dir.getPath().equals(path)) {
              directories.remove(path);
            } else {
              dir.remove(path);
            }
          }
        }
      } finally {
        directories.unlock();
      }
    }
  }

  private CachedDirectory<T> find(final Path path) {
    CachedDirectory<T> foundDir = null;
    final Iterator<CachedDirectory<T>> it = directories.values().iterator();
    while (it.hasNext()) {
      final CachedDirectory<T> dir = it.next();
      if (path.startsWith(dir.getPath())
          && (foundDir == null || dir.getPath().startsWith(foundDir.getPath()))) {
        foundDir = dir;
      }
    }
    return foundDir;
  }

  private void runCallbacks(final List<Callback> callbacks) {
    if (!callbacks.isEmpty() && !closed.get()) {
      callbackExecutor.run(
          new Runnable() {
            @Override
            public void run() {
              Collections.sort(callbacks);
              final Iterator<Callback> it = callbacks.iterator();
              while (it.hasNext()) {
                it.next().run();
              }
            }
          });
    }
  }

  @SuppressWarnings("EmptyCatchBlock")
  void handleEvent(final TypedPath typedPath) {
    final List<TypedPath> symlinks = new ArrayList<>();
    final List<Callback> callbacks = new ArrayList<>();
    if (!closed.get() && directories.lock()) {
      try {
        final Path path = typedPath.getPath();
        if (typedPath.exists()) {
          final CachedDirectory<T> dir = find(typedPath.getPath());
          if (dir != null) {
            try {
              dir.update(typedPath).observe(callbackObserver(callbacks, symlinks));
            } catch (final IOException e) {
              handleDelete(path, callbacks, symlinks);
            }
          } else if (pendingFiles.remove(path)) {
            try {
              CachedDirectory<T> cachedDirectory;
              try {
                cachedDirectory =
                    FileTreeDataViews.<T>cachedUpdatable(
                        path, converter, directoryRegistry.maxDepthFor(path), followLinks);
              } catch (final NotDirectoryException nde) {
                cachedDirectory =
                    FileTreeDataViews.<T>cachedUpdatable(path, converter, -1, followLinks);
              }
              final CachedDirectory<T> previous = directories.put(path, cachedDirectory);
              if (previous != null) previous.close();
              addCallback(
                  callbacks, symlinks, typedPath, null, cachedDirectory.getEntry(), Create, null);
              final Iterator<FileTreeDataViews.Entry<T>> it =
                  cachedDirectory.listEntries(cachedDirectory.getMaxDepth(), AllPass).iterator();
              while (it.hasNext()) {
                final FileTreeDataViews.Entry<T> entry = it.next();
                addCallback(callbacks, symlinks, entry, null, entry, Create, null);
              }
            } catch (final IOException e) {
              pendingFiles.add(path);
            }
          }
        } else {
          handleDelete(path, callbacks, symlinks);
        }
      } finally {
        directories.unlock();
      }
      final Iterator<TypedPath> it = symlinks.iterator();
      while (it.hasNext()) {
        final TypedPath tp = it.next();
        final Path path = tp.getPath();
        if (symlinkWatcher != null) {
          if (tp.exists()) {
            try {
              symlinkWatcher.addSymlink(path, directoryRegistry.maxDepthFor(path));
            } catch (final IOException e) {
              observers.onError(e);
            }
          } else {
            symlinkWatcher.remove(path);
          }
        }
      }
      runCallbacks(callbacks);
    }
  }

  private void handleDelete(
      final Path path, final List<Callback> callbacks, final List<TypedPath> symlinks) {
    final List<Iterator<FileTreeDataViews.Entry<T>>> removeIterators = new ArrayList<>();
    final Iterator<CachedDirectory<T>> directoryIterator =
        new ArrayList<>(directories.values()).iterator();
    while (directoryIterator.hasNext()) {
      final CachedDirectory<T> dir = directoryIterator.next();
      if (path.startsWith(dir.getPath())) {
        List<FileTreeDataViews.Entry<T>> updates = dir.remove(path);
        final Iterator<Path> it = directoryRegistry.registered().iterator();
        while (it.hasNext()) {
          if (it.next().equals(path)) {
            pendingFiles.add(path);
          }
        }
        if (dir.getPath().equals(path)) {
          directories.remove(path);
          updates.add(dir.getEntry());
        }
        removeIterators.add(updates.iterator());
      }
    }
    final Iterator<Iterator<FileTreeDataViews.Entry<T>>> it = removeIterators.iterator();
    while (it.hasNext()) {
      final Iterator<FileTreeDataViews.Entry<T>> removeIterator = it.next();
      while (removeIterator.hasNext()) {
        final FileTreeDataViews.Entry<T> entry = Entries.setExists(removeIterator.next(), false);
        addCallback(callbacks, symlinks, entry, entry, null, Delete, null);
      }
    }
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true) && directories.lock()) {
      try {
        callbackExecutor.close();
        if (symlinkWatcher != null) symlinkWatcher.close();
        directories.clear();
        observers.close();
        directoryRegistry.close();
        pendingFiles.clear();
      } finally {
        directories.unlock();
      }
    }
  }

  CachedDirectory<T> register(
      final Path path, final int maxDepth, final PathWatcher<PathWatchers.Event> watcher)
      throws IOException {
    if (directoryRegistry.addDirectory(path, maxDepth) && directories.lock()) {
      try {
        watcher.register(path, maxDepth);
        final List<CachedDirectory<T>> dirs = new ArrayList<>(directories.values());
        Collections.sort(
            dirs,
            new Comparator<CachedDirectory<T>>() {
              @Override
              public int compare(CachedDirectory<T> left, CachedDirectory<T> right) {
                return left.getPath().compareTo(right.getPath());
              }
            });
        final Iterator<CachedDirectory<T>> it = dirs.iterator();
        CachedDirectory<T> existing = null;
        while (it.hasNext() && existing == null) {
          final CachedDirectory<T> dir = it.next();
          if (path.startsWith(dir.getPath())) {
            final int depth =
                path.equals(dir.getPath())
                    ? 0
                    : (dir.getPath().relativize(path).getNameCount() - 1);
            if (dir.getMaxDepth() == Integer.MAX_VALUE || maxDepth < dir.getMaxDepth() - depth) {
              existing = dir;
            } else if (depth <= dir.getMaxDepth()) {
              dir.close();
              try {
                final int md =
                    maxDepth < Integer.MAX_VALUE - depth - 1
                        ? maxDepth + depth + 1
                        : Integer.MAX_VALUE;
                existing =
                    FileTreeDataViews.<T>cachedUpdatable(dir.getPath(), converter, md, followLinks);
                directories.put(dir.getPath(), existing);
              } catch (IOException e) {
                existing = null;
              }
            }
          }
        }
        CachedDirectory<T> dir;
        if (existing == null) {
          try {
            try {
              dir = FileTreeDataViews.<T>cachedUpdatable(path, converter, maxDepth, followLinks);
            } catch (final NotDirectoryException e) {
              dir = FileTreeDataViews.<T>cachedUpdatable(path, converter, -1, followLinks);
            }
            directories.put(path, dir);
          } catch (final NoSuchFileException e) {
            pendingFiles.add(path);
            dir = FileTreeDataViews.<T>cachedUpdatable(path, converter, -1, followLinks);
          }
        } else {
          existing.update(TypedPaths.get(path));
          dir = existing;
        }
        return dir;
      } finally {
        directories.unlock();
      }
    } else {
      return null;
    }
  }

  @SuppressWarnings("EmptyCatchBlock")
  private void addCallback(
      final List<Callback> callbacks,
      final List<TypedPath> symlinks,
      final TypedPath typedPath,
      final FileTreeDataViews.Entry<T> oldEntry,
      final FileTreeDataViews.Entry<T> newEntry,
      final Kind kind,
      final IOException ioException) {
    if (typedPath.isSymbolicLink()) {
      symlinks.add(typedPath);
    }
    callbacks.add(
        new Callback(typedPath.getPath()) {
          @Override
          public void run() {
            try {
              if (ioException != null) {
                observers.onError(ioException);
              } else if (kind.equals(Create)) {
                observers.onCreate(newEntry);
              } else if (kind.equals(Delete)) {
                observers.onDelete(Entries.setExists(oldEntry, false));
              } else if (kind.equals(Modify)) {
                observers.onUpdate(oldEntry, newEntry);
              }
            } catch (final Exception e) {
              e.printStackTrace();
            }
          }
        });
  }

  @Override
  public int addObserver(final Observer<Entry<T>> observer) {
    return observers.addObserver(observer);
  }

  @Override
  public void removeObserver(int handle) {
    observers.removeObserver(handle);
  }

  @Override
  public int addCacheObserver(final CacheObserver<T> observer) {
    return observers.addCacheObserver(observer);
  }

  @Override
  public List<Entry<T>> listEntries(Path path, int maxDepth, Filter<? super Entry<T>> filter) {
    if (directories.lock()) {
      try {
        final CachedDirectory<T> dir = find(path);
        if (dir == null) {
          return Collections.emptyList();
        } else {
          if (dir.getPath().equals(path) && dir.getMaxDepth() == -1) {
            List<FileTreeDataViews.Entry<T>> result = new ArrayList<>();
            result.add(dir.getEntry());
            return result;
          } else {
            return dir.listEntries(path, maxDepth, filter);
          }
        }
      } finally {
        directories.unlock();
      }
    } else {
      return Collections.emptyList();
    }
  }

  private FileTreeViews.CacheObserver<T> callbackObserver(
      final List<Callback> callbacks, final List<TypedPath> symlinks) {
    return new FileTreeViews.CacheObserver<T>() {
      @Override
      public void onCreate(final FileTreeDataViews.Entry<T> newEntry) {
        addCallback(callbacks, symlinks, newEntry, null, newEntry, Create, null);
      }

      @Override
      public void onDelete(final FileTreeDataViews.Entry<T> oldEntry) {
        addCallback(callbacks, symlinks, oldEntry, oldEntry, null, Delete, null);
      }

      @Override
      public void onUpdate(
          final FileTreeDataViews.Entry<T> oldEntry, final FileTreeDataViews.Entry<T> newEntry) {
        addCallback(callbacks, symlinks, oldEntry, oldEntry, newEntry, Modify, null);
      }

      @Override
      public void onError(final IOException exception) {
        addCallback(callbacks, symlinks, null, null, null, Error, exception);
      }
    };
  }

  @Override
  public List<TypedPath> list(Path path, int maxDepth, Filter<? super TypedPath> filter) {
    if (directories.lock()) {
      try {
        final CachedDirectory<T> dir = find(path);
        if (dir == null) {
          return Collections.emptyList();
        } else {
          if (dir.getPath().equals(path) && dir.getMaxDepth() == -1) {
            List<TypedPath> result = new ArrayList<>();
            result.add(TypedPaths.getDelegate(dir.getPath(), dir.getEntry()));
            return result;
          } else {
            return dir.list(path, maxDepth, filter);
          }
        }
      } finally {
        directories.unlock();
      }
    } else {
      return Collections.emptyList();
    }
  }
}
