package com.swoval.files;

import com.swoval.files.FileTreeDataViews.Entry;
import com.swoval.files.FileTreeViews.CacheObserver;
import com.swoval.files.FileTreeViews.Observer;
import com.swoval.files.PathWatchers.Event.Kind;
import com.swoval.functional.Consumer;
import com.swoval.functional.Either;
import com.swoval.functional.Filter;
import com.swoval.runtime.ShutdownHooks;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

class FileTreeRepositoryImpl<T> implements FileTreeRepository<T> {
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final Executor internalExecutor;
  private final FileCacheDirectoryTree<T> directoryTree;
  private final FileCachePathWatcher<T> watcher;
  private final Runnable closeRunnable =
      new Runnable() {
        @Override
        public void run() {
          if (closed.compareAndSet(false, true)) {
            internalExecutor.block(
                new Consumer<Executor.Thread>() {
                  @Override
                  public void accept(final Executor.Thread thread) {
                    ShutdownHooks.removeHook(shutdownHookId);
                    watcher.close(thread);
                    directoryTree.close(thread);
                  }
                });
            internalExecutor.close();
          }
        }
      };
  private final int shutdownHookId;

  FileTreeRepositoryImpl(
      final FileCacheDirectoryTree<T> directoryTree,
      final FileCachePathWatcher<T> watcher,
      final Executor executor) {
    assert (executor != null);
    this.shutdownHookId = ShutdownHooks.addHook(1, closeRunnable);
    this.internalExecutor = executor;
    this.directoryTree = directoryTree;
    this.watcher = watcher;
  }

  /** Cleans up the path watcher and clears the directory cache. */
  @Override
  public void close() {
    closeRunnable.run();
  }

  @Override
  public int addObserver(final Observer<FileTreeDataViews.Entry<T>> observer) {
    return addCacheObserver(
        new CacheObserver<T>() {
          @Override
          public void onCreate(Entry<T> newEntry) {
            observer.onNext(newEntry);
          }

          @Override
          public void onDelete(Entry<T> oldEntry) {
            observer.onNext(oldEntry);
          }

          @Override
          public void onUpdate(Entry<T> oldEntry, Entry<T> newEntry) {
            observer.onNext(newEntry);
          }

          @Override
          public void onError(IOException exception) {
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
    return internalExecutor
        .block(
            new Function<Executor.Thread, List<FileTreeDataViews.Entry<T>>>() {
              @Override
              public List<FileTreeDataViews.Entry<T>> apply(final Executor.Thread thread) {
                return directoryTree.listEntries(path, maxDepth, filter);
              }
            })
        .get();
  }

  @Override
  public Either<IOException, Boolean> register(final Path path, final int maxDepth) {
    return internalExecutor
        .block(
            new Function<Executor.Thread, Boolean>() {
              @Override
              public Boolean apply(final Executor.Thread thread) {
                return watcher.register(path, maxDepth, thread);
              }
            })
        .castLeft(IOException.class, false);
  }

  @Override
  public void unregister(final Path path) {
    internalExecutor.block(
        new Consumer<Executor.Thread>() {
          @Override
          public void accept(final Executor.Thread thread) {
            watcher.unregister(path, thread);
          }
        });
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
    private final Kind kind;
    private final TypedPath typedPath;

    Callback(final TypedPath typedPath, final Kind kind) {
      this.kind = kind;
      this.typedPath = typedPath;
    }

    @Override
    public int compareTo(final Callback that) {
      final int kindComparision = this.kind.compareTo(that.kind);
      return kindComparision == 0 ? this.typedPath.compareTo(that.typedPath) : kindComparision;
    }
  }
}
