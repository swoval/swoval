package com.swoval.files;

import com.swoval.files.Executor.Thread;
import com.swoval.files.FileTreeViews.Observer;
import com.swoval.files.PathWatchers.Event;
import com.swoval.functional.Consumer;
import com.swoval.functional.Either;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/** Provides a PathWatcher that is backed by a {@link java.nio.file.WatchService}. */
class NioPathWatcher implements ManagedPathWatcher {
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final Executor internalExecutor;
  private final NioPathWatcherDirectoryTree nioPathWatcherDirectoryTree;
  private final Observers<PathWatchers.Event> observers = new Observers<>();

  /**
   * Instantiate a NioPathWatcher.
   *
   * @param internalExecutor The Executor to internally manage the watcher
   */
  NioPathWatcher(
      final NioPathWatcherDirectoryTree nioPathWatcherDirectoryTree,
      final Executor internalExecutor) {
    this.internalExecutor = internalExecutor;
    this.nioPathWatcherDirectoryTree = nioPathWatcherDirectoryTree;
  }

  @Override
  public void update(final TypedPath typedPath) {
    nioPathWatcherDirectoryTree.update(typedPath);
  }

  /**
   * Register a path to monitor for file events
   *
   * @param path The directory to watch for file events
   * @param maxDepth The maximum maxDepth of subdirectories to watch
   * @return an {@link com.swoval.functional.Either} containing the result of the registration or an
   *     IOException if registration fails. This method should be idempotent and return true the
   *     first time the directory is registered or when the depth is changed. Otherwise it should
   *     return false.
   */
  @Override
  public com.swoval.functional.Either<IOException, Boolean> register(
      final Path path, final int maxDepth) {
    return internalExecutor
        .block(
            new Function<Thread, Boolean>() {
              @Override
              public Boolean apply(final Executor.Thread thread) throws IOException {
                final Either<IOException, Boolean> result =
                    nioPathWatcherDirectoryTree.register(path, maxDepth);
                if (result.isRight()) {
                  return result.get();
                } else {
                  throw Either.leftProjection(result).getValue();
                }
              }
            })
        .castLeft(IOException.class, false);
  }

  /**
   * Stop watching a directory
   *
   * @param path The directory to remove from monitoring
   */
  @Override
  @SuppressWarnings("EmptyCatchBlock")
  public void unregister(final Path path) {
    internalExecutor.block(
        new Consumer<Executor.Thread>() {
          @Override
          public void accept(final Executor.Thread thread) {
            nioPathWatcherDirectoryTree.unregister(path);
          }
        });
  }

  @SuppressWarnings("EmptyCatchBlock")
  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      internalExecutor.block(
          new Consumer<Executor.Thread>() {
            @Override
            public void accept(final Executor.Thread thread) {
              nioPathWatcherDirectoryTree.close();
            }
          });
      internalExecutor.close();
    }
  }

  @Override
  public int addObserver(final Observer<Event> observer) {
    return observers.addObserver(observer);
  }

  @Override
  public void removeObserver(final int handle) {
    observers.removeObserver(handle);
  }
}
