package com.swoval.files;

import com.swoval.files.FileTreeDataViews.CacheObserver;
import com.swoval.files.FileTreeDataViews.Entry;
import com.swoval.files.FileTreeViews.Observer;
import com.swoval.functional.Either;
import com.swoval.functional.Filter;
import com.swoval.logging.Logger;
import com.swoval.logging.Loggers;
import com.swoval.logging.Loggers.Level;
import com.swoval.runtime.ShutdownHooks;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

class FileTreeRepositoryImpl<T> implements FileTreeRepository<T> {
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final FileCacheDirectoryTree<T> directoryTree;
  private final FileCachePathWatcher<T> watcher;
  private final Runnable closeRunnable =
      new Runnable() {
        @Override
        @SuppressWarnings("EmptyCatchBlock")
        public void run() {
          if (closed.compareAndSet(false, true)) {
            ShutdownHooks.removeHook(shutdownHookId);
            watcher.close();
            directoryTree.close();
          }
        }
      };
  private final int shutdownHookId;
  private final Logger logger;

  FileTreeRepositoryImpl(
      final FileCacheDirectoryTree<T> directoryTree,
      final FileCachePathWatcher<T> watcher,
      final Logger logger) {
    this.shutdownHookId = ShutdownHooks.addHook(1, closeRunnable);
    this.directoryTree = directoryTree;
    this.watcher = watcher;
    this.logger = logger;
  }

  FileTreeRepositoryImpl(
      final FileCacheDirectoryTree<T> directoryTree, final FileCachePathWatcher<T> watcher) {
    this(directoryTree, watcher, Loggers.getLogger());
  }

  /** Cleans up the path watcher and clears the directory cache. */
  @Override
  public void close() {
    closeRunnable.run();
  }

  @Override
  public int addObserver(final Observer<? super FileTreeDataViews.Entry<T>> observer) {
    return addCacheObserver(
        new CacheObserver<T>() {
          @Override
          public void onCreate(final Entry<T> newEntry) {
            observer.onNext(newEntry);
          }

          @Override
          public void onDelete(final Entry<T> oldEntry) {
            observer.onNext(oldEntry);
          }

          @Override
          public void onUpdate(final Entry<T> oldEntry, final Entry<T> newEntry) {
            observer.onNext(newEntry);
          }

          @Override
          public void onError(final IOException exception) {
            observer.onError(exception);
          }
        });
  }

  @Override
  public void removeObserver(int handle) {
    directoryTree.removeObserver(handle);
  }

  @Override
  public List<FileTreeDataViews.Entry<T>> listEntries(
      final Path path,
      final int maxDepth,
      final Filter<? super FileTreeDataViews.Entry<T>> filter) {
    return directoryTree.listEntries(path, maxDepth, filter);
  }

  @Override
  public Either<IOException, Boolean> register(final Path path, final int maxDepth) {
    try {
      final Path absolutePath = path.isAbsolute() ? path : path.toAbsolutePath();
      final Either<IOException, Boolean> res =
          Either.right(watcher.register(absolutePath, maxDepth));
      if (Loggers.shouldLog(logger, Level.DEBUG))
        logger.debug(this + " registered " + path + " with max depth " + maxDepth);
      return res;
    } catch (final IOException e) {
      return Either.left(e);
    }
  }

  @Override
  @SuppressWarnings("EmptyCatchBlock")
  public void unregister(final Path path) {
    final Path absolutePath = path.isAbsolute() ? path : path.toAbsolutePath();
    watcher.unregister(absolutePath);
  }

  @Override
  public List<TypedPath> list(Path path, int maxDepth, Filter<? super TypedPath> filter) {
    return directoryTree.list(path, maxDepth, filter);
  }

  @Override
  public int addCacheObserver(final CacheObserver<T> observer) {
    return directoryTree.addCacheObserver(observer);
  }

  abstract static class Callback implements Runnable, Comparable<Callback> {
    private final Path path;

    Callback(final Path path) {
      this.path = path;
    }

    @Override
    public int compareTo(final Callback that) {
      return this.path.compareTo(that.path);
    }
  }
}
