package com.swoval.files;

import static com.swoval.files.PathWatchers.Event.Kind.Create;
import static com.swoval.files.PathWatchers.Event.Kind.Delete;
import static com.swoval.files.PathWatchers.Event.Kind.Error;
import static com.swoval.files.PathWatchers.Event.Kind.Modify;
import static com.swoval.files.PathWatchers.Event.Kind.Overflow;
import static com.swoval.functional.Filters.AllPass;

import com.swoval.files.FileTreeDataViews.CacheObserver;
import com.swoval.files.FileTreeDataViews.Converter;
import com.swoval.files.FileTreeDataViews.Entry;
import com.swoval.files.FileTreeDataViews.ObservableCache;
import com.swoval.files.FileTreeRepositoryImpl.Callback;
import com.swoval.files.FileTreeViews.Observer;
import com.swoval.files.PathWatchers.Event;
import com.swoval.files.PathWatchers.Event.Kind;
import com.swoval.functional.Filter;
import com.swoval.logging.Logger;
import com.swoval.logging.Loggers;
import com.swoval.logging.Loggers.Level;
import com.swoval.runtime.Platform;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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

  boolean contains(final Path path) {
    if (lock()) {
      try {
        return pendingFiles.contains(path);
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
  private final Filter<TypedPath> filter = DirectoryRegistries.toTypedPathFilter(directoryRegistry);
  private final Converter<T> converter;
  private final CacheObservers<T> observers = new CacheObservers<>();
  private final Executor callbackExecutor;
  private final boolean followLinks;
  private final boolean rescanOnDirectoryUpdate;
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final Logger logger;
  final SymlinkWatcher symlinkWatcher;

  FileCacheDirectoryTree(
      final Converter<T> converter,
      final Executor callbackExecutor,
      final SymlinkWatcher symlinkWatcher,
      final boolean rescanOnDirectoryUpdate) {
    this(converter, callbackExecutor, symlinkWatcher, rescanOnDirectoryUpdate, Loggers.getLogger());
  }

  FileCacheDirectoryTree(
      final Converter<T> converter,
      final Executor callbackExecutor,
      final SymlinkWatcher symlinkWatcher,
      final boolean rescanOnDirectoryUpdate,
      final Logger logger) {
    this.converter = converter;
    this.callbackExecutor = callbackExecutor;
    this.symlinkWatcher = symlinkWatcher;
    this.followLinks = symlinkWatcher != null;
    this.rescanOnDirectoryUpdate = rescanOnDirectoryUpdate;
    this.logger = logger;
    if (symlinkWatcher != null) {
      final boolean log = System.getProperty("swoval.symlink.debug", "false").equals("true");
      symlinkWatcher.addObserver(
          new Observer<Event>() {
            @Override
            public void onError(final Throwable t) {
              if (log) t.printStackTrace(System.err);
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
        public Map<Path, Integer> registered() {
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
    final Path absolutePath = path.isAbsolute() ? path : path.toAbsolutePath();
    if (directories.lock()) {
      try {
        directoryRegistry.removeDirectory(absolutePath);
        if (!directoryRegistry.accept(absolutePath)) {
          final CachedDirectory<T> dir = find(absolutePath);
          if (dir != null) {
            if (dir.getPath().equals(absolutePath)) {
              directories.remove(absolutePath);
            } else {
              dir.remove(absolutePath);
            }
          }
        }
      } finally {
        directories.unlock();
      }
    }
    if (Loggers.shouldLog(logger, Level.DEBUG)) logger.debug(this + " unregistered " + path);
  }

  private CachedDirectory<T> find(final Path path) {
    CachedDirectory<T> foundDir = null;
    final List<CachedDirectory<T>> dirs = directories.values();
    Collections.sort(
        dirs,
        new Comparator<CachedDirectory<T>>() {
          @Override
          public int compare(final CachedDirectory<T> left, final CachedDirectory<T> right) {
            // Descending order so that we find the most specific path
            return right.getPath().compareTo(left.getPath());
          }
        });
    final Iterator<CachedDirectory<T>> it = dirs.iterator();
    while (it.hasNext() && foundDir == null) {
      final CachedDirectory<T> dir = it.next();
      if (path.startsWith(dir.getPath())) {
        if (dir.getMaxDepth() == Integer.MAX_VALUE || path.equals(dir.getPath())) {
          foundDir = dir;
        } else {
          int depth = dir.getPath().relativize(path).getNameCount() - 1;
          if (depth <= dir.getMaxDepth()) {
            foundDir = dir;
          }
        }
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
                final Callback callback = it.next();
                if (Loggers.shouldLog(logger, Level.DEBUG))
                  logger.debug(this + " running callback " + callback);
                try {
                  callback.run();
                } catch (final Exception e) {
                }
              }
            }
          });
    }
  }

  @SuppressWarnings("EmptyCatchBlock")
  void handleEvent(final Event event) {
    if (Loggers.shouldLog(logger, Level.DEBUG)) logger.debug(this + " received event " + event);
    final TypedPath typedPath = event.getTypedPath();
    final List<TypedPath> symlinks = new ArrayList<>();
    final List<Callback> callbacks = new ArrayList<>();
    if (!closed.get() && directories.lock()) {
      try {
        final Path path = typedPath.getPath();
        if (typedPath.exists()) {
          final CachedDirectory<T> dir = find(typedPath.getPath());
          if (dir != null) {
            try {
              final TypedPath updatePath =
                  (followLinks || !typedPath.isSymbolicLink())
                      ? typedPath
                      : TypedPaths.get(typedPath.getPath(), Entries.LINK);
              final boolean rescan = rescanOnDirectoryUpdate || event.getKind().equals(Overflow);
              if (Loggers.shouldLog(logger, Level.DEBUG))
                logger.debug(
                    this + " updating " + updatePath.getPath() + " in " + dir.getTypedPath());
              dir.update(updatePath, rescan).observe(callbackObserver(callbacks, symlinks));
            } catch (final IOException e) {
              handleDelete(path, callbacks, symlinks);
            }
          } else if (pendingFiles.contains(path)) {
            if (Loggers.shouldLog(logger, Level.DEBUG))
              logger.debug(this + " found pending file for " + path);
            try {
              CachedDirectory<T> cachedDirectory;
              try {
                cachedDirectory = newCachedDirectory(path, directoryRegistry.maxDepthFor(path));
                if (Loggers.shouldLog(logger, Level.DEBUG))
                  logger.debug(this + " successfully initialiazed directory for " + path);
              } catch (final NotDirectoryException nde) {
                if (Loggers.shouldLog(logger, Level.DEBUG))
                  logger.debug(this + " unable to initialize directory for " + path);
                cachedDirectory = newCachedDirectory(path, -1);
              }
              final CachedDirectory<T> previous = directories.put(path, cachedDirectory);
              if (previous != null) previous.close();
              addCallback(
                  callbacks,
                  symlinks,
                  cachedDirectory.getEntry(),
                  null,
                  cachedDirectory.getEntry(),
                  Create,
                  null);
              final Iterator<FileTreeDataViews.Entry<T>> it =
                  cachedDirectory.listEntries(cachedDirectory.getMaxDepth(), AllPass).iterator();
              while (it.hasNext()) {
                final FileTreeDataViews.Entry<T> entry = it.next();
                addCallback(callbacks, symlinks, entry, null, entry, Create, null);
              }
            } catch (final IOException e) {
              System.err.println("Caught unexpected io exception handling event for " + path);
              e.printStackTrace(System.err);
              pendingFiles.add(path);
            }
          }
        } else {
          if (Loggers.shouldLog(logger, Level.DEBUG))
            logger.debug(this + " deleting directory for " + path);
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
        final List<FileTreeDataViews.Entry<T>> updates =
            path.equals(dir.getPath())
                ? dir.listEntries(Integer.MAX_VALUE, AllPass)
                : new ArrayList<FileTreeDataViews.Entry<T>>();
        updates.addAll(dir.remove(path));
        final Iterator<Path> it = directoryRegistry.registered().keySet().iterator();
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
        if (symlinkWatcher != null && entry.getTypedPath().isSymbolicLink())
          symlinkWatcher.remove(entry.getTypedPath().getPath());
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
    if (Loggers.shouldLog(logger, Level.DEBUG)) logger.debug(this + " was closed");
  }

  CachedDirectory<T> register(
      final Path path, final int maxDepth, final PathWatcher<PathWatchers.Event> watcher)
      throws IOException {
    final Path absolutePath = path.isAbsolute() ? path : path.toAbsolutePath();
    if (directoryRegistry.addDirectory(absolutePath, maxDepth) && directories.lock()) {
      try {
        watcher.register(absolutePath, maxDepth);
        final List<CachedDirectory<T>> dirs = new ArrayList<>(directories.values());
        Collections.sort(
            dirs,
            new Comparator<CachedDirectory<T>>() {
              @Override
              public int compare(final CachedDirectory<T> left, final CachedDirectory<T> right) {
                return left.getPath().compareTo(right.getPath());
              }
            });
        final Iterator<CachedDirectory<T>> it = dirs.iterator();
        CachedDirectory<T> existing = null;
        while (it.hasNext() && existing == null) {
          final CachedDirectory<T> dir = it.next();
          if (absolutePath.startsWith(dir.getPath())) {
            final int depth = dir.getPath().relativize(absolutePath).getNameCount() - 1;
            if (dir.getMaxDepth() == Integer.MAX_VALUE || dir.getMaxDepth() - depth > maxDepth) {
              existing = dir;
            }
          }
        }
        CachedDirectory<T> dir;
        if (existing == null) {
          try {
            try {
              dir = newCachedDirectory(absolutePath, maxDepth);
            } catch (final NotDirectoryException e) {
              dir = newCachedDirectory(absolutePath, -1);
            }
            directories.put(absolutePath, dir);
          } catch (final NoSuchFileException e) {
            pendingFiles.add(absolutePath);
            dir = newCachedDirectory(absolutePath, -1);
          }
        } else {
          existing.update(TypedPaths.get(absolutePath));
          dir = existing;
        }
        cleanupDirectories(absolutePath, maxDepth);
        if (Loggers.shouldLog(logger, Level.DEBUG))
          logger.debug(this + " registered " + path + " with max depth " + maxDepth);
        return dir;
      } finally {
        directories.unlock();
      }
    } else {
      return null;
    }
  }

  private void cleanupDirectories(final Path path, final int maxDepth) {
    final Iterator<CachedDirectory<T>> it = directories.values().iterator();
    final List<Path> toRemove = new ArrayList<>();
    while (it.hasNext()) {
      final CachedDirectory<T> dir = it.next();
      if (dir.getPath().startsWith(path) && !dir.getPath().equals(path)) {
        if (maxDepth == Integer.MAX_VALUE) {
          toRemove.add(dir.getPath());
        } else {
          int depth = path.relativize(dir.getPath()).getNameCount();
          if (maxDepth - depth >= dir.getMaxDepth()) {
            toRemove.add(dir.getPath());
          }
        }
      }
    }
    final Iterator<Path> removeIterator = toRemove.iterator();
    while (removeIterator.hasNext()) {
      directories.remove(removeIterator.next());
    }
  }

  @SuppressWarnings("EmptyCatchBlock")
  private void addCallback(
      final List<Callback> callbacks,
      final List<TypedPath> symlinks,
      final FileTreeDataViews.Entry<T> entry,
      final FileTreeDataViews.Entry<T> oldEntry,
      final FileTreeDataViews.Entry<T> newEntry,
      final Kind kind,
      final IOException ioException) {
    final TypedPath typedPath = entry == null ? null : entry.getTypedPath();
    if (typedPath != null && typedPath.isSymbolicLink() && followLinks) {
      symlinks.add(typedPath);
    }
    callbacks.add(
        new Callback(typedPath == null ? Paths.get("") : typedPath.getPath()) {
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
  public int addObserver(final Observer<? super Entry<T>> observer) {
    return observers.addObserver(observer);
  }

  @Override
  public void removeObserver(final int handle) {
    observers.removeObserver(handle);
  }

  @Override
  public int addCacheObserver(final CacheObserver<T> observer) {
    return observers.addCacheObserver(observer);
  }

  @Override
  public List<Entry<T>> listEntries(
      final Path path, final int maxDepth, final Filter<? super Entry<T>> filter) {
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
            final int depth = directoryRegistry.maxDepthFor(path);
            return dir.listEntries(path, depth < maxDepth ? depth : maxDepth, filter);
          }
        }
      } finally {
        directories.unlock();
      }
    } else {
      return Collections.emptyList();
    }
  }

  private CacheObserver<T> callbackObserver(
      final List<Callback> callbacks, final List<TypedPath> symlinks) {
    return new CacheObserver<T>() {
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
            result.add(TypedPaths.getDelegate(dir.getPath(), dir.getTypedPath()));
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

  private CachedDirectory<T> newCachedDirectory(final Path path, final int depth)
      throws IOException {
    int attempt = 1;
    int MAX_ATTEMPTS = 3;
    CachedDirectory<T> result = null;
    do {
      try {
        result =
            new CachedDirectoryImpl<>(TypedPaths.get(path), converter, depth, filter, followLinks)
                .init();
      } catch (final NoSuchFileException | NotDirectoryException e) {
        throw e;
      } catch (final AccessDeniedException e) {
        if (Platform.isWin()) {
          try {
            Sleep.sleep(0);
          } catch (final InterruptedException ie) {
            throw e;
          }
        }
      }
      attempt += 1;
    } while (result == null && attempt <= MAX_ATTEMPTS);
    if (result == null) throw new NoSuchFileException(path.toString());
    return result;
  }
}
