package com.swoval.files;

import com.swoval.files.DataViews.Converter;
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
  public static <T> FileTreeRepository<T> get(final Converter<T> converter) {
    final Executor executor = Executor.make("FileTreeRepository-internal-executor");
    final FileCacheDirectoryTree<T> tree =
        new FileCacheDirectoryTree<>(
            converter, Executor.make("FileTreeRepository-callback-executor"), executor.copy());
    final PathWatcher<Event> pathWatcher =
        DEFAULT_PATH_WATCHER_FACTORY.create(
            new EventHandler(tree), executor.copy(), tree.readOnlyDirectoryRegistry());
    final FileCachePathWatcher<T> watcher = new FileCachePathWatcher<>(tree, pathWatcher);
    return new FileTreeRepositoryImpl<>(tree, watcher, executor);
  }
  static class EventHandler implements BiConsumer<Event, Executor.Thread> {
    private final FileCacheDirectoryTree<?> tree;

    EventHandler(final FileCacheDirectoryTree<?> tree) {
      this.tree = tree;
    }

    @Override
    public void accept(final Event event, final Executor.Thread thread) {
      final Event newEvent = new Event(TypedPaths.get(event.getPath()), event.getKind());
      tree.handleEvent(newEvent, thread);
    }
  }

  static TotalFunction<Consumer<Event>, PathWatcher<PathWatchers.Event>> DEFAULT_SYMLINK_FACTORY =
      new TotalFunction<Consumer<Event>, PathWatcher<PathWatchers.Event>>() {
        @Override
        public PathWatcher<PathWatchers.Event> apply(final Consumer<Event> consumer) {
          try {
            return PathWatchers.get(consumer);
          } catch (final Exception e) {
            throw new RuntimeException(e);
          }
        }
      };

  static Factory DEFAULT_PATH_WATCHER_FACTORY =
      new Factory() {
        @Override
        public PathWatcher<Event> create(
            final BiConsumer<Event, Executor.Thread> consumer,
            final Executor executor,
            final DirectoryRegistry registry) {
          try {
            return PathWatchers.get(consumer, executor, registry);
          } catch (final Exception e) {
            throw new RuntimeException(e);
          }
        }
      };
}
