package com.swoval.files;

import static com.swoval.files.PathWatchers.Event.Kind.Create;
import static com.swoval.files.PathWatchers.Event.Kind.Delete;
import static com.swoval.files.PathWatchers.Event.Kind.Error;
import static com.swoval.files.PathWatchers.Event.Kind.Modify;
import static com.swoval.functional.Filters.AllPass;

import com.swoval.files.Executor.ThreadHandle;
import com.swoval.files.FileTreeDataViews.Converter;
import com.swoval.files.FileTreeDataViews.Entry;
import com.swoval.files.FileTreeRepositoryImpl.Callback;
import com.swoval.files.FileTreeViews.CacheObserver;
import com.swoval.files.FileTreeViews.ObservableCache;
import com.swoval.files.FileTreeViews.Observer;
import com.swoval.files.PathWatchers.Event;
import com.swoval.files.PathWatchers.Event.Kind;
import com.swoval.functional.Consumer;
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
import java.util.Map;
import java.util.Set;

class FileCacheDirectoryTree<T> implements ObservableCache<T>, FileTreeDataView<T> {
  private final Map<Path, CachedDirectory<T>> directories = new HashMap<>();
  private final DirectoryRegistry directoryRegistry = new DirectoryRegistryImpl();
  private final Set<Path> pendingFiles = new HashSet<>();
  private final Converter<T> converter;
  private final CacheObservers<T> observers = new CacheObservers<>();
  private final Executor callbackExecutor;
  private final boolean followLinks = true;
  final SymlinkWatcher symlinkWatcher;

  FileCacheDirectoryTree(
      final Converter<T> converter,
      final Executor callbackExecutor,
      final Executor internalExecutor,
      final SymlinkWatcher symlinkWatcher) {
    this.converter = converter;
    this.callbackExecutor = callbackExecutor;
    this.symlinkWatcher = symlinkWatcher;
    symlinkWatcher.addObserver(
        new Observer<Event>() {
          @Override
          public void onError(final Throwable t) {
            t.printStackTrace(System.err);
          }

          @Override
          public void onNext(final Event event) {
            internalExecutor.run(
                new Consumer<ThreadHandle>() {
                  @Override
                  public void accept(ThreadHandle threadHandle) {
                    handleEvent(event, threadHandle);
                  }
                });
          }
        });
  }

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

  void unregister(final Path path, final ThreadHandle threadHandle) {
    directoryRegistry.removeDirectory(path);
    if (!directoryRegistry.accept(path)) {
      final CachedDirectory<T> dir = find(path);
      if (dir != null) {
        if (dir.getPath().equals(path)) {
          directories.remove(path);
        } else {
          dir.remove(path, threadHandle);
        }
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
    if (!callbacks.isEmpty()) {
      callbackExecutor.run(
          new Consumer<ThreadHandle>() {
            @Override
            public void accept(final ThreadHandle threadHandle) {
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
  void handleEvent(final TypedPath typedPath, final ThreadHandle threadHandle) {
    final Path path = typedPath.getPath();

    final List<Callback> callbacks = new ArrayList<>();
    if (typedPath.exists()) {
      final CachedDirectory<T> dir = find(typedPath.getPath());
      if (dir != null) {
        dir.update(typedPath, threadHandle).observe(callbackObserver(callbacks, threadHandle));
      } else if (pendingFiles.remove(path)) {
        try {
          CachedDirectory<T> cachedDirectory;
          try {
            cachedDirectory =
                FileTreeViews.<T>cached(
                    path, converter, directoryRegistry.maxDepthFor(path), followLinks);
          } catch (final NotDirectoryException nde) {
            cachedDirectory = FileTreeViews.<T>cached(path, converter, -1, followLinks);
          }
          final CachedDirectory<T> previous = directories.put(path, cachedDirectory);
          if (previous != null) previous.close();
          addCallback(callbacks, typedPath, null, cachedDirectory.getEntry(), Create, null, threadHandle);
          final Iterator<FileTreeDataViews.Entry<T>> it =
              cachedDirectory.listEntries(cachedDirectory.getMaxDepth(), AllPass).iterator();
          while (it.hasNext()) {
            final FileTreeDataViews.Entry<T> entry = it.next();
            addCallback(callbacks, entry, null, entry, Create, null, threadHandle);
          }
        } catch (final IOException e) {
          pendingFiles.add(path);
        }
      }
    } else {
      final List<Iterator<FileTreeDataViews.Entry<T>>> removeIterators = new ArrayList<>();
      final Iterator<CachedDirectory<T>> directoryIterator =
          new ArrayList<>(directories.values()).iterator();
      while (directoryIterator.hasNext()) {
        final CachedDirectory<T> dir = directoryIterator.next();
        if (path.startsWith(dir.getPath())) {
          List<FileTreeDataViews.Entry<T>> updates = dir.remove(path, threadHandle);
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
          addCallback(callbacks, entry, entry, null, Delete, null, threadHandle);
        }
      }
    }
    runCallbacks(callbacks);
  }

  public void close() {
    throw new UnsupportedOperationException("close");
  }

  public void close(final ThreadHandle threadHandle) {
    callbackExecutor.close();
    if (symlinkWatcher != null) symlinkWatcher.close();
    final Iterator<CachedDirectory<T>> directoryIterator = directories.values().iterator();
    while (directoryIterator.hasNext()) {
      directoryIterator.next().close();
    }
    observers.close();
    directories.clear();
    directoryRegistry.close();
    pendingFiles.clear();
  }

  CachedDirectory<T> register(
      final Path path,
      final int maxDepth,
      final PathWatcher<PathWatchers.Event> watcher,
      final ThreadHandle threadHandle)
      throws IOException {
    if (directoryRegistry.addDirectory(path, maxDepth)) {
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
              path.equals(dir.getPath()) ? 0 : (dir.getPath().relativize(path).getNameCount() - 1);
          if (dir.getMaxDepth() == Integer.MAX_VALUE || maxDepth < dir.getMaxDepth() - depth) {
            existing = dir;
          } else if (depth <= dir.getMaxDepth()) {
            dir.close();
            try {
              final int md =
                  maxDepth < Integer.MAX_VALUE - depth - 1
                      ? maxDepth + depth + 1
                      : Integer.MAX_VALUE;
              existing = FileTreeViews.<T>cached(dir.getPath(), converter, md, followLinks);
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
            dir = FileTreeViews.<T>cached(path, converter, maxDepth, followLinks);
          } catch (final NotDirectoryException e) {
            dir = FileTreeViews.<T>cached(path, converter, -1, followLinks);
          }
          directories.put(path, dir);
        } catch (final NoSuchFileException e) {
          pendingFiles.add(path);
          dir = FileTreeViews.<T>cached(path, converter, -1, followLinks);
        }
      } else {
        existing.update(TypedPaths.get(path), threadHandle);
        dir = existing;
      }
      return dir;
    } else {
      return null;
    }
  }

  @SuppressWarnings("EmptyCatchBlock")
  private void addCallback(
      final List<Callback> callbacks,
      final TypedPath typedPath,
      final FileTreeDataViews.Entry<T> oldEntry,
      final FileTreeDataViews.Entry<T> newEntry,
      final Kind kind,
      final IOException ioException,
      final ThreadHandle threadHandle) {
    if (typedPath.isSymbolicLink()) {
      final Path path = typedPath.getPath();
      if (typedPath.exists()) {
        symlinkWatcher.addSymlink(path, directoryRegistry.maxDepthFor(path), threadHandle);
      } else {
        try {
          symlinkWatcher.remove(path);
        } catch (final InterruptedException e) {
        }
      }
    }
    callbacks.add(
        new Callback(typedPath, kind) {
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
    final CachedDirectory<T> dir = find(path);
    if (dir == null) {
      return new ArrayList<>();
    } else {
      if (dir.getPath().equals(path) && dir.getMaxDepth() == -1) {
        List<FileTreeDataViews.Entry<T>> result = new ArrayList<>();
        result.add(dir.getEntry());
        return result;
      } else {
        return dir.listEntries(path, maxDepth, filter);
      }
    }
  }

  private FileTreeViews.CacheObserver<T> callbackObserver(final List<Callback> callbacks, final ThreadHandle threadHandle) {
    return new FileTreeViews.CacheObserver<T>() {
      @Override
      public void onCreate(final FileTreeDataViews.Entry<T> newEntry) {
        addCallback(callbacks, newEntry, null, newEntry, Create, null, threadHandle);
      }

      @Override
      public void onDelete(final FileTreeDataViews.Entry<T> oldEntry) {
        addCallback(callbacks, oldEntry, oldEntry, null, Delete, null, threadHandle);
      }

      @Override
      public void onUpdate(
          final FileTreeDataViews.Entry<T> oldEntry, final FileTreeDataViews.Entry<T> newEntry) {
        addCallback(callbacks, oldEntry, oldEntry, newEntry, Modify, null, threadHandle);
      }

      @Override
      public void onError(final IOException exception) {
        addCallback(callbacks, null, null, null, Error, exception, threadHandle);
      }
    };
  }

  @Override
  public List<TypedPath> list(Path path, int maxDepth, Filter<? super TypedPath> filter) {
    final CachedDirectory<T> dir = find(path);
    if (dir == null) {
      return new ArrayList<>();
    } else {
      if (dir.getPath().equals(path) && dir.getMaxDepth() == -1) {
        List<TypedPath> result = new ArrayList<>();
        result.add(TypedPaths.getDelegate(dir.getPath(), dir.getEntry()));
        return result;
      } else {
        return dir.list(path, maxDepth, filter);
      }
    }
  }
}
