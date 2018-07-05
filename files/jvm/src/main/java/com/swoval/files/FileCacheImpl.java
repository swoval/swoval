package com.swoval.files;

import static com.swoval.files.EntryFilters.AllPass;
import static com.swoval.files.PathWatcher.Event.Create;
import static com.swoval.files.PathWatcher.Event.Delete;
import static com.swoval.files.PathWatcher.Event.Modify;
import static com.swoval.files.PathWatcher.Event.Overflow;
import static java.util.Map.Entry;

import com.swoval.files.Directory.Converter;
import com.swoval.files.Directory.EntryFilter;
import com.swoval.files.Directory.Observer;
import com.swoval.files.Directory.OnChange;
import com.swoval.files.Directory.OnError;
import com.swoval.files.PathWatcher.Event;
import com.swoval.files.PathWatcher.Event.Kind;
import com.swoval.functional.Consumer;
import com.swoval.functional.Either;
import com.swoval.runtime.ShutdownHooks;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
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
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

class FileCacheImpl<T> implements FileCache<T> {
  private final Observers<T> observers = new Observers<>();
  private final Map<Path, Directory<T>> directories = new HashMap<>();
  private final Set<Path> pendingFiles = new HashSet<>();
  private final Converter<T> converter;
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final Executor internalExecutor;
  private final Executor callbackExecutor =
      Executor.make("com.swoval.files.FileCache-callback-executor");
  private final SymlinkWatcher symlinkWatcher;
  private final DirectoryRegistry registry = new DirectoryRegistry();

  private Consumer<Event> callback(final Executor executor) {
    return new Consumer<Event>() {
      @SuppressWarnings("unchecked")
      @Override
      public void accept(final Event event) {
        executor.run(
            new Runnable() {
              @Override
              public void run() {
                final Path path = event.path;
                if (event.kind.equals(Overflow)) {
                  handleOverflow(path);
                } else {
                  handleEvent(path);
                }
              }
            });
      }
    };
  }

  private final PathWatcher watcher;

  FileCacheImpl(
      final Converter<T> converter,
      final PathWatcher.Factory factory,
      final Executor executor,
      FileCaches.Option... options)
      throws InterruptedException, IOException {
    ShutdownHooks.addHook(
        1,
        new Runnable() {
          @Override
          public void run() {
            close();
          }
        });
    this.internalExecutor =
        executor == null
            ? Executor.make("com.swoval.files.FileCache-callback-internalExecutor")
            : executor;
    this.watcher =
        factory.create(
            callback(this.internalExecutor.copy()), this.internalExecutor.copy(), registry);
    this.converter = converter;
    symlinkWatcher =
        !ArrayOps.contains(options, FileCaches.Option.NOFOLLOW_LINKS)
            ? new SymlinkWatcher(
                new Consumer<Path>() {
                  @Override
                  public void accept(Path path) {
                    handleEvent(path);
                  }
                },
                factory,
                new OnError() {
                  @Override
                  public void apply(final Path symlink, final IOException exception) {
                    observers.onError(symlink, exception);
                  }
                },
                this.internalExecutor.copy())
            : null;
  }

  /** Cleans up the path watcher and clears the directory cache. */
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
      internalExecutor.close();
      callbackExecutor.close();
    }
  }

  @Override
  public int addObserver(final Observer<T> observer) {
    return observers.addObserver(observer);
  }

  @Override
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

  @Override
  public void removeObserver(int handle) {
    observers.removeObserver(handle);
  }

  @Override
  public List<Directory.Entry<T>> list(
      final Path path, final int maxDepth, final Directory.EntryFilter<? super T> filter) {
    return internalExecutor
        .block(
            new Callable<List<Directory.Entry<T>>>() {
              @Override
              public List<Directory.Entry<T>> call() {
                final Directory<T> dir = find(path);
                if (dir == null) {
                  return new ArrayList<>();
                } else {
                  if (dir.path.equals(path) && dir.getDepth() == -1) {
                    List<Directory.Entry<T>> result = new ArrayList<>();
                    result.add(dir.entry());
                    return result;
                  } else {
                    return dir.list(path, maxDepth, filter);
                  }
                }
              }
            })
        .get();
  }

  @Override
  public List<Directory.Entry<T>> list(
      final Path path, final boolean recursive, EntryFilter<? super T> filter) {
    return list(path, recursive ? Integer.MAX_VALUE : 0, filter);
  }

  @Override
  public List<Directory.Entry<T>> list(final Path path, final int maxDepth) {
    return list(path, maxDepth, AllPass);
  }

  @Override
  public List<Directory.Entry<T>> list(final Path path, final boolean recursive) {
    return list(path, recursive, AllPass);
  }

  @Override
  public List<Directory.Entry<T>> list(Path path) {
    return list(path, Integer.MAX_VALUE, AllPass);
  }

  @Override
  public Either<IOException, Boolean> register(final Path path, final int maxDepth) {
    Either<IOException, Boolean> result = watcher.register(path, maxDepth);
    if (result.isRight()) {
      result =
          internalExecutor
              .block(
                  new Callable<Boolean>() {
                    @Override
                    public Boolean call() throws IOException {
                      return doReg(path, maxDepth);
                    }
                  })
              .castLeft(IOException.class);
    }
    return result;
  }

  @Override
  public Either<IOException, Boolean> register(Path path, boolean recursive) {
    return register(path, recursive ? Integer.MAX_VALUE : 0);
  }

  @Override
  public Either<IOException, Boolean> register(Path path) {
    return register(path, Integer.MAX_VALUE);
  }

  @Override
  public void unregister(final Path path) {
    internalExecutor.block(
        new Runnable() {
          @Override
          public void run() {
            registry.removeDirectory(path);
            watcher.unregister(path);
            if (!registry.accept(path)) {
              final Directory<T> dir = find(path);
              if (dir != null) {
                if (dir.path.equals(path)) {
                  directories.remove(path);
                } else {
                  dir.remove(path);
                }
              }
            }
          }
        });
  }

  private boolean doReg(final Path path, final int maxDepth) throws IOException {
    boolean result = false;
    registry.addDirectory(path, maxDepth);
    final List<Directory<T>> dirs = new ArrayList<>(directories.values());
    Collections.sort(
        dirs,
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
          result = true;
          dir.close();
          try {
            final int md =
                maxDepth < Integer.MAX_VALUE - depth - 1 ? maxDepth + depth + 1 : Integer.MAX_VALUE;
            existing = Directory.cached(dir.path, converter, md);
            directories.put(dir.path, existing);
          } catch (IOException e) {
            existing = null;
          }
        }
      }
    }
    if (existing == null) {
      try {
        Directory<T> dir;
        try {
          dir = Directory.cached(path, converter, maxDepth);
        } catch (final NotDirectoryException e) {
          dir = Directory.cached(path, converter, -1);
        }
        directories.put(path, dir);
        final Iterator<Directory.Entry<T>> entryIterator =
            dir.list(true, EntryFilters.AllPass).iterator();
        if (symlinkWatcher != null) {
          while (entryIterator.hasNext()) {
            final Directory.Entry<T> entry = entryIterator.next();
            if (entry.isSymbolicLink()) {
              symlinkWatcher.addSymlink(
                  entry.path, maxDepth == Integer.MAX_VALUE ? maxDepth : maxDepth - 1);
            }
          }
        }
        result = true;
      } catch (final NoSuchFileException e) {
        result = pendingFiles.add(path);
      }
    }
    return result;
  }

  private Directory<T> find(final Path path) {
    Directory<T> foundDir = null;
    final Iterator<Directory<T>> it = directories.values().iterator();
    while (it.hasNext()) {
      final Directory<T> dir = it.next();
      if (path.startsWith(dir.path) && (foundDir == null || dir.path.startsWith(foundDir.path))) {
        foundDir = dir;
      }
    }
    return foundDir;
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
    }
    return res;
  }

  @SuppressWarnings("unchecked")
  private void handleOverflow(final Path path) {
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
              watcher.unregister(mapEntry.getKey());
            }
          }
          final Iterator<Entry<Path, Directory.Entry<T>>> newIterator =
              newEntries.entrySet().iterator();
          while (newIterator.hasNext()) {
            final Entry<Path, Directory.Entry<T>> mapEntry = newIterator.next();
            final Directory.Entry<T> oldEntry = oldEntries.get(mapEntry.getKey());
            if (oldEntry == null) {
              if (registry.accept(mapEntry.getKey()) && mapEntry.getValue().isDirectory()) {
                if (registry.accept(mapEntry.getKey()) && mapEntry.getValue().isDirectory()) {
                  /*
                   * Using Integer.MIN_VALUE will ensure that we update the directory without changing
                   * the depth of the registration.
                   */
                  watcher.register(mapEntry.getKey(), Integer.MIN_VALUE);
                }
              }
              creations.add(mapEntry.getValue());
            } else if (!oldEntry.equals(mapEntry.getValue())) {
              updates.add(new Directory.Entry[] {oldEntry, mapEntry.getValue()});
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
      callbackExecutor.run(
          new Runnable() {
            @Override
            public void run() {
              final Iterator<Directory.Entry<T>> creationIterator = creations.iterator();
              while (creationIterator.hasNext()) observers.onCreate(creationIterator.next());
              final Iterator<Directory.Entry<T>> deletionIterator = deletions.iterator();
              while (deletionIterator.hasNext()) observers.onDelete(deletionIterator.next());
              final Iterator<Directory.Entry<T>[]> updateIterator = updates.iterator();
              while (updateIterator.hasNext()) {
                final Directory.Entry<T>[] update = updateIterator.next();
                observers.onUpdate(update[0], update[1]);
              }
            }
          });
    }
  }

  private abstract class Callback implements Runnable, Comparable<Callback> {
    private final Kind kind;
    private final Path path;

    Callback(final Path path, final Kind kind) {
      this.kind = kind;
      this.path = path;
    }

    @Override
    public int compareTo(final Callback that) {
      final int kindComparision = this.kind.compareTo(that.kind);
      return kindComparision == 0 ? this.path.compareTo(that.path) : kindComparision;
    }
  }

  private void addCallback(
      final List<Callback> callbacks,
      final Path path,
      final Directory.Entry<T> oldEntry,
      final Directory.Entry<T> newEntry,
      final Kind kind,
      final IOException ioException) {
    callbacks.add(
        new Callback(path, kind) {
          @Override
          public void run() {
            if (ioException != null) {
              observers.onError(path, ioException);
            } else if (kind.equals(Create)) {
              observers.onCreate(newEntry);
            } else if (kind.equals(Delete)) {
              observers.onDelete(oldEntry);
            } else if (kind.equals(Modify)) {
              observers.onUpdate(oldEntry, newEntry);
            }
          }
        });
  }

  @SuppressWarnings("EmptyCatchBlock")
  private void handleEvent(final Path path) {
    if (!closed.get()) {
      BasicFileAttributes attrs = null;
      final List<Callback> callbacks = new ArrayList<>();
      try {
        attrs = NioWrappers.readAttributes(path, LinkOption.NOFOLLOW_LINKS);
      } catch (IOException e) {
      }
      if (attrs != null) {
        final Directory<T> dir = find(path);
        if (dir != null) {
          final List<Directory.Entry<T>> paths =
              dir.list(
                  path,
                  0,
                  new Directory.EntryFilter<T>() {
                    @Override
                    public boolean accept(Directory.Entry<? extends T> entry) {
                      return path.equals(entry.path);
                    }
                  });
          if (!paths.isEmpty() || !path.equals(dir.path)) {
            final Path toUpdate = paths.isEmpty() ? path : paths.get(0).path;
            try {
              if (attrs.isSymbolicLink() && symlinkWatcher != null)
                symlinkWatcher.addSymlink(
                    path,
                    dir.getDepth() == Integer.MAX_VALUE ? Integer.MAX_VALUE : dir.getDepth() - 1);
              final Directory.Updates<T> updates =
                  dir.update(toUpdate, Directory.Entry.getKind(toUpdate, attrs));
              updates.observe(callbackObserver(callbacks));
            } catch (final IOException e) {
              addCallback(callbacks, path, null, null, Event.Error, e);
            }
          }
        } else if (pendingFiles.remove(path)) {
          try {
            Directory<T> directory;
            try {
              directory = Directory.cached(path, converter, registry.maxDepthFor(path));
            } catch (final NotDirectoryException nde) {
              directory = Directory.cached(path, converter, -1);
            }
            directories.put(path, directory);
            addCallback(callbacks, path, null, directory.entry(), Create, null);
            final Iterator<Directory.Entry<T>> it = directory.list(true, AllPass).iterator();
            while (it.hasNext()) {
              final Directory.Entry<T> entry = it.next();
              addCallback(callbacks, entry.path, null, entry, Create, null);
            }
          } catch (final IOException e) {
            pendingFiles.add(path);
          }
        }
      } else {
        final List<Iterator<Directory.Entry<T>>> removeIterators = new ArrayList<>();
        final Iterator<Directory<T>> directoryIterator =
            new ArrayList<>(directories.values()).iterator();
        while (directoryIterator.hasNext()) {
          final Directory<T> dir = directoryIterator.next();
          if (path.startsWith(dir.path)) {
            List<Directory.Entry<T>> updates = dir.remove(path);
            if (dir.path.equals(path)) {
              pendingFiles.add(path);
              updates.add(dir.entry());
              directories.remove(path);
            }
            removeIterators.add(updates.iterator());
          }
        }
        final Iterator<Iterator<Directory.Entry<T>>> it = removeIterators.iterator();
        while (it.hasNext()) {
          final Iterator<Directory.Entry<T>> removeIterator = it.next();
          while (removeIterator.hasNext()) {
            final Directory.Entry<T> entry = removeIterator.next();
            addCallback(callbacks, entry.path, entry, null, Delete, null);
            if (symlinkWatcher != null) {
              symlinkWatcher.remove(entry.path);
            }
          }
        }
      }
      if (!callbacks.isEmpty()) {
        callbackExecutor.run(
            new Runnable() {
              @Override
              public void run() {
                Collections.sort(callbacks);
                final Iterator<Callback> callbackIterator = callbacks.iterator();
                while (callbackIterator.hasNext()) {
                  callbackIterator.next().run();
                }
              }
            });
      }
    }
  }

  private Observer<T> callbackObserver(final List<Callback> callbacks) {
    return new Observer<T>() {
      @Override
      public void onCreate(final Directory.Entry<T> newEntry) {
        addCallback(callbacks, newEntry.path, null, newEntry, Create, null);
      }

      @Override
      public void onDelete(final Directory.Entry<T> oldEntry) {
        addCallback(callbacks, oldEntry.path, oldEntry, null, Delete, null);
      }

      @Override
      public void onUpdate(final Directory.Entry<T> oldEntry, final Directory.Entry<T> newEntry) {
        addCallback(callbacks, oldEntry.path, oldEntry, newEntry, Modify, null);
      }

      @Override
      public void onError(final Path path, final IOException exception) {
        addCallback(callbacks, path, null, null, Event.Error, exception);
      }
    };
  }
}
