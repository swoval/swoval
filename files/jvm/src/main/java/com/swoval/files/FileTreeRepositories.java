package com.swoval.files;

import com.swoval.files.DataViews.Converter;
import com.swoval.files.DataViews.OnError;
import com.swoval.files.Executor.Thread;
import com.swoval.files.FileTreeViews.Observer;
import com.swoval.files.PathWatchers.Event;
import com.swoval.files.PathWatchers.Factory;
import com.swoval.functional.Consumer;
import com.swoval.functional.Either;
import java.io.IOException;
import java.nio.file.Path;

/** Provides factory methods for generating instances of {@link FileTreeRepository}. */
public class FileTreeRepositories {
  private FileTreeRepositories() {}
  /**
   * Create a file cache with a CacheObserver of events.
   *
   * @param converter converts a path to the cached value type T
   * @param <T> the value type of the cache entries
   * @return a file cache.
   */
  public static <T> FileTreeRepository<T> get(final Converter<T> converter)
      throws InterruptedException, IOException {
    final Executor executor = Executor.make("FileTreeRepository-internal-executor");
    final Executor copy = executor.copy();
    final SymlinkWatcher symlinkWatcher =
        new SymlinkWatcher(
            PathWatchers.get(copy, new DirectoryRegistryImpl()),
            new OnError() {
              @Override
              public void apply(IOException exception) {}
            },
            executor);
    final FileCacheDirectoryTree<T> tree =
        new FileCacheDirectoryTree<>(
            converter, Executor.make("FileTreeRepository-callback-executor"), symlinkWatcher);
    final PathWatcher<PathWatchers.Event> pathWatcher =
        PathWatchers.get(copy, tree.readOnlyDirectoryRegistry());
    pathWatcher.addObserver(
        new Observer<Event>() {
          @Override
          public void onError(final Throwable t) {}

          @Override
          public void onNext(final Event event) {
            copy.run(
                new Consumer<Executor.Thread>() {
                  @Override
                  public void accept(final Executor.Thread thread) {
                    tree.handleEvent(event, thread);
                  }
                });
          }
        });
    final FileCachePathWatcher<T> watcher = new FileCachePathWatcher<>(tree, pathWatcher);
    return new FileTreeRepositoryImpl<>(tree, watcher, executor);
  }
}
