package com.swoval.files;

import com.swoval.files.FileTreeDataViews.Converter;
import com.swoval.files.FileTreeViews.Observer;
import com.swoval.files.PathWatchers.Event;
import com.swoval.logging.Logger;
import com.swoval.logging.Loggers;
import java.io.IOException;

/** Provides factory methods for generating instances of {@link FileTreeRepository}. */
public class FileTreeRepositories {
  private FileTreeRepositories() {}
  /**
   * Create a file tree repository.
   *
   * @param converter converts a path to the cached value type T
   * @param followLinks toggles whether or not to follow symbolic links. When true, any symbolic
   *     links that point to a regular file will trigger an event when the target file is modified.
   *     For any symbolic links that point to a directory, the children of the target directory will
   *     be included (up to the max depth parameter specified by {@link
   *     FileTreeRepository#register}) and will trigger an event when any of the included children
   *     are modified. When false, symbolic links are not followed and only events for the symbolic
   *     link itself are reported.
   * @param <T> the value type of the cache entries
   * @return a file tree repository.
   * @throws InterruptedException if the path watcher can't be started.
   * @throws IOException if an instance of {@link java.nio.file.WatchService} cannot be created.
   */
  public static <T> FileTreeRepository<T> get(
      final Converter<T> converter, final boolean followLinks)
      throws InterruptedException, IOException {
    return get(converter, followLinks, false, Loggers.getLogger());
  }
  /**
   * Create a file tree repository.
   *
   * @param converter converts a path to the cached value type T
   * @param followLinks toggles whether or not to follow symbolic links. When true, any symbolic
   *     links that point to a regular file will trigger an event when the target file is modified.
   *     For any symbolic links that point to a directory, the children of the target directory will
   *     be included (up to the max depth parameter specified by {@link
   *     FileTreeRepository#register}) and will trigger an event when any of the included children
   *     are modified. When false, symbolic links are not followed and only events for the symbolic
   *     link itself are reported.
   * @param rescanOnDirectoryUpdates toggles whether or not we rescan a directory's subtree when an
   *     update is detected for that directory. This can be very expensive since it will perform
   *     iops proportional to the number of files in the subtree. It generally should not be
   *     necessary since we are also watching the subtree for events.
   * @param logger logs debug events
   * @param <T> the value type of the cache entries
   * @return a file tree repository.
   * @throws InterruptedException if the path watcher can't be started.
   * @throws IOException if an instance of {@link java.nio.file.WatchService} cannot be created.
   */
  public static <T> FileTreeRepository<T> get(
      final Converter<T> converter,
      final boolean followLinks,
      final boolean rescanOnDirectoryUpdates,
      final Logger logger)
      throws InterruptedException, IOException {
    final SymlinkWatcher symlinkWatcher =
        followLinks
            ? new SymlinkWatcher(
                PathWatchers.get(false, new DirectoryRegistryImpl(), logger), logger)
            : null;
    final Executor callbackExecutor = Executor.make("FileTreeRepository-callback-executor");
    final FileCacheDirectoryTree<T> tree =
        new FileCacheDirectoryTree<>(
            converter, callbackExecutor, symlinkWatcher, rescanOnDirectoryUpdates, logger);
    final PathWatcher<PathWatchers.Event> pathWatcher =
        PathWatchers.get(false, tree.readOnlyDirectoryRegistry(), logger);
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
