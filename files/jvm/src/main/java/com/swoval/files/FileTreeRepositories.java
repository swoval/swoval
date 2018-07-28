package com.swoval.files;

import com.swoval.files.Executor.ThreadHandle;
import com.swoval.files.FileTreeDataViews.Converter;
import com.swoval.files.FileTreeDataViews.OnError;
import com.swoval.files.FileTreeViews.Observer;
import com.swoval.files.PathWatchers.Event;
import com.swoval.functional.Consumer;
import java.io.IOException;

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
            copy);
    final FileCacheDirectoryTree<T> tree =
        new FileCacheDirectoryTree<>(
            converter, Executor.make("FileTreeRepository-callback-executor"), copy, symlinkWatcher);
    final PathWatcher<PathWatchers.Event> pathWatcher =
        PathWatchers.get(copy, tree.readOnlyDirectoryRegistry());
    pathWatcher.addObserver(
        new Observer<Event>() {
          @Override
          public void onError(final Throwable t) {}

          @Override
          public void onNext(final Event event) {
            copy.run(
                new Consumer<ThreadHandle>() {
                  @Override
                  public void accept(final ThreadHandle threadHandle) {
                    tree.handleEvent(event, threadHandle);
                  }
                });
          }
        });
    final FileCachePathWatcher<T> watcher = new FileCachePathWatcher<>(tree, pathWatcher);
    return new FileTreeRepositoryImpl<>(tree, watcher, executor);
  }
}
