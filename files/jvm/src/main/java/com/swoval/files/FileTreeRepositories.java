package com.swoval.files;

import com.swoval.files.FileTreeDataViews.Converter;
import com.swoval.files.FileTreeViews.Observer;
import com.swoval.files.PathWatchers.Event;
import java.io.IOException;

/** Provides factory methods for generating instances of {@link FileTreeRepository}. */
public class FileTreeRepositories {
  private FileTreeRepositories() {}
  /**
   * Create a file tree repository.
   *
   * @param followLinks toggles whether or not to follow symbolic links. When true, any symbolic
   *     links that point to a regular file will trigger an event when the target file is modified.
   *     For any symbolic links that point to a directory, the children of the target directory will
   *     be included (up to the max depth parameter specified by {@link
   *     FileTreeRepository#register}) and will trigger an event when any of the included children
   *     are modified. When false, symbolic links are not followed and only events for the symbolic
   *     link itself are reported.
   * @param converter converts a path to the cached value type T
   * @param <T> the value type of the cache entries
   * @return a file tree repository.
   * @throws InterruptedException if the path watcher can't be started.
   * @throws IOException if an instance of {@link java.nio.file.WatchService} cannot be created.
   */
  public static <T> FileTreeRepository<T> get(
      final boolean followLinks, final Converter<T> converter)
      throws InterruptedException, IOException {
    final SymlinkWatcher symlinkWatcher =
        followLinks
            ? new SymlinkWatcher(PathWatchers.get(false, new DirectoryRegistryImpl()))
            : null;
    final Executor callbackExecutor = Executor.make("FileTreeRepository-callback-executor");
    final FileCacheDirectoryTree<T> tree =
        new FileCacheDirectoryTree<>(converter, callbackExecutor, symlinkWatcher);
    final PathWatcher<PathWatchers.Event> pathWatcher =
        PathWatchers.get(followLinks, tree.readOnlyDirectoryRegistry());
    pathWatcher.addObserver(
        new Observer<Event>() {
          @Override
          public void onError(final Throwable t) {}

          @Override
          public void onNext(final Event event) {
            tree.handleEvent(event);
          }
        });
    final FileCachePathWatcher<T> watcher = new FileCachePathWatcher<>(tree, pathWatcher);
    return new FileTreeRepositoryImpl<>(tree, watcher);
  }
}
