package com.swoval.files;

import com.swoval.files.DataViews.Entry;
import com.swoval.files.Executor.Thread;
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
  /** The constructor must come first for code-gen */
  FileTreeRepositoryImpl(
      final FileCacheDirectoryTree<T> directoryTree,
      final FileCachePathWatcher<T> watcher,
      final Executor executor) {
    ShutdownHooks.addHook(
        1,
        new Runnable() {
          @Override
          public void run() {
            close();
          }
        });
    assert (executor != null);
    this.internalExecutor = executor;
    this.directoryTree = directoryTree;
    this.watcher = watcher;
  }

  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final Executor internalExecutor;
  private final FileCacheDirectoryTree<T> directoryTree;
  private final FileCachePathWatcher<T> watcher;

  /** Cleans up the path watcher and clears the directory cache. */
  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      internalExecutor.block(
          new Consumer<Executor.Thread>() {
            @Override
            public void accept(final Executor.Thread thread) {
              watcher.close(thread);
              directoryTree.close(thread);
            }
          });
      internalExecutor.close();
    }
  }

  @Override
  public int addObserver(final Observer<DataViews.Entry<T>> observer) {
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
  public List<DataViews.Entry<T>> listEntries(
      final Path path, final int maxDepth, final Filter<? super DataViews.Entry<T>> filter) {
    return internalExecutor
        .block(
            new Function<Executor.Thread, List<DataViews.Entry<T>>>() {
              @Override
              public List<DataViews.Entry<T>> apply(final Executor.Thread thread) {
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
                return watcher.register(path, maxDepth, directoryTree, thread);
              }
            })
        .castLeft(IOException.class, false);
  }

  @Override
  public void unregister(final Path path) {
    internalExecutor.block(
        new Consumer<Thread>() {
          @Override
          public void accept(final Executor.Thread thread) {
            watcher.unregister(path, directoryTree, thread);
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
