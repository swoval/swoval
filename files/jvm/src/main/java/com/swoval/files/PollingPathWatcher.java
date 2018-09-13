package com.swoval.files;

import static com.swoval.functional.Filters.AllPass;

import com.swoval.files.FileTreeDataViews.CacheObserver;
import com.swoval.files.FileTreeDataViews.Converter;
import com.swoval.files.FileTreeViews.Observer;
import com.swoval.files.PathWatchers.Event;
import com.swoval.files.PathWatchers.Event.Kind;
import com.swoval.functional.Either;
import java.io.IOException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

class PollingPathWatcher implements PathWatcher<PathWatchers.Event> {
  private final AtomicBoolean isClosed = new AtomicBoolean(false);
  private final boolean followLinks;
  private final DirectoryRegistry registry = new DirectoryRegistryImpl();
  private final Observers<PathWatchers.Event> observers = new Observers<>();
  private Map<Path, FileTreeDataViews.Entry<Path>> oldEntries;
  private final PeriodicTask periodicTask;
  private final Converter<Path> converter =
      new Converter<Path>() {
        @Override
        public Path apply(final TypedPath typedPath) {
          return typedPath.getPath();
        }
      };

  PollingPathWatcher(final boolean followLinks, final long pollInterval, final TimeUnit timeUnit)
      throws InterruptedException {
    this.followLinks = followLinks;
    oldEntries = getEntries();
    periodicTask = new PeriodicTask(new PollingRunnable(), timeUnit.toMillis(pollInterval));
  }

  @Override
  public Either<IOException, Boolean> register(final Path path, final int maxDepth) {
    boolean result;
    final List<FileTreeDataViews.Entry<Path>> entries = getEntries(path, maxDepth);
    synchronized (this) {
      addAll(oldEntries, entries);
      result = registry.addDirectory(path, maxDepth);
    }
    return Either.right(result);
  }

  @Override
  public void unregister(final Path path) {
    registry.removeDirectory(path);
  }

  @Override
  public void close() {
    if (isClosed.compareAndSet(false, true)) {
      registry.close();
      try {
        periodicTask.close();
      } catch (final InterruptedException e) {
        e.printStackTrace(System.err);
      }
    }
  }

  @Override
  public int addObserver(Observer<? super PathWatchers.Event> observer) {
    return observers.addObserver(observer);
  }

  @Override
  public void removeObserver(final int handle) {
    observers.removeObserver(handle);
  }

  private void addAll(
      final Map<Path, FileTreeDataViews.Entry<Path>> map,
      final List<FileTreeDataViews.Entry<Path>> list) {
    final Iterator<FileTreeDataViews.Entry<Path>> it = list.iterator();
    while (it.hasNext()) {
      final FileTreeDataViews.Entry<Path> entry = it.next();
      map.put(entry.getTypedPath().getPath(), entry);
    }
  }

  private List<FileTreeDataViews.Entry<Path>> getEntries(final Path path, final int maxDepth) {
    try {
      final DirectoryDataView<Path> view =
          FileTreeDataViews.cached(path, converter, maxDepth, followLinks);
      final List<FileTreeDataViews.Entry<Path>> entries = view.listEntries(-1, AllPass);
      entries.addAll(view.listEntries(maxDepth, AllPass));
      return entries;
    } catch (final NotDirectoryException e) {
      final List<FileTreeDataViews.Entry<Path>> result = new ArrayList<>();
      final TypedPath typedPath = TypedPaths.get(path);
      result.add(Entries.get(typedPath, converter, typedPath));
      return result;
    } catch (final IOException e) {
      return Collections.emptyList();
    }
  }

  private Map<Path, FileTreeDataViews.Entry<Path>> getEntries() {
    // I have to use putAll because scala.js doesn't handle new HashMap(registry.registered()).
    final HashMap<Path, Integer> map = new HashMap<>();
    synchronized (this) {
      map.putAll(registry.registered());
    }
    final Iterator<Entry<Path, Integer>> it = map.entrySet().iterator();
    final Map<Path, FileTreeDataViews.Entry<Path>> result = new HashMap<>();
    while (it.hasNext()) {
      final Entry<Path, Integer> entry = it.next();
      final List<FileTreeDataViews.Entry<Path>> entries =
          getEntries(entry.getKey(), entry.getValue());
      addAll(result, entries);
    }
    return result;
  }

  private class PollingRunnable implements Runnable {
    final CacheObserver<Path> cacheObserver =
        new CacheObserver<Path>() {
          @Override
          public void onCreate(final FileTreeDataViews.Entry<Path> newEntry) {
            observers.onNext(new Event(newEntry.getTypedPath(), Kind.Create));
          }

          @Override
          public void onDelete(final FileTreeDataViews.Entry<Path> oldEntry) {
            observers.onNext(new Event(oldEntry.getTypedPath(), Kind.Delete));
          }

          @Override
          public void onUpdate(
              final FileTreeDataViews.Entry<Path> oldEntry,
              FileTreeDataViews.Entry<Path> newEntry) {
            observers.onNext(new Event(newEntry.getTypedPath(), Kind.Modify));
          }

          @Override
          public void onError(final IOException exception) {
            observers.onError(exception);
          }
        };

    @Override
    public void run() {
      final Map<Path, FileTreeDataViews.Entry<Path>> newEntries = getEntries();
      MapOps.diffDirectoryEntries(oldEntries, newEntries, cacheObserver);
      synchronized (this) {
        oldEntries = newEntries;
      }
    }
  }
}
