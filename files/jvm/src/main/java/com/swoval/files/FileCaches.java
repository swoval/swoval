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
public class FileCaches {
  private FileCaches() {}
  /**
   * Create a file cache with a CacheObserver of events.
   *
   * @param converter converts a path to the cached value type T
   * @param <T> the value type of the cache entries
   * @return a file cache.
   */
  public static <T> FileTreeRepository<T> getCached(final Converter<T> converter) {
    final Executor executor = Executor.make("FileTreeRepository-internal-executor");
    final FileCacheDirectoryTree<T> tree =
        new FileCacheDirectoryTree<>(
            converter, Executor.make("FileTreeRepository-callback-executor"), executor);
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

  /**
   * Create a file cache with a CacheObserver of events.
   *
   * @param converter converts a path to the cached value type T
   * @param <T> the value type of the cache entries
   * @return a file cache.
   */
  public static <T> FileTreeRepository<T> get(final Converter<T> converter)
      throws IOException, InterruptedException {
    final Executor executor = Executor.make("FileTreeRepository-internal-executor");
    final PathWatcher<T> watcher =
        new PathWatcher<T>() {
          final Observers<T> observers = new Observers<>();
          final PathWatcher<PathWatchers.Event> watcher =
              PathWatchers.get(
                  new Consumer<Event>() {
                    @Override
                    public void accept(final Event event) {
                      try {
                        observers.onNext(converter.apply(event));
                      } catch (final IOException e) {
                        observers.onError(e);
                      }
                    }
                  });

          @Override
          public Either<IOException, Boolean> register(Path path, int maxDepth) {
            return watcher.register(path, maxDepth);
          }

          @Override
          public void unregister(Path path) {
            watcher.unregister(path);
          }

          @Override
          public void close() {
            observers.close();
            watcher.close();
          }

          @Override
          public int addObserver(final Observer<T> observer) {
            return observers.addObserver(observer);
          }

          @Override
          public void removeObserver(int handle) {
            observers.removeObserver(handle);
          }
        };
    throw new UnsupportedOperationException();
    //    new MonitoredFileTreeViewImpl<>(watcher, new DataView<T>() {
    //      @Override
    //      public void close() throws Exception {
    //
    //      }
    //
    //      @Override
    //      public List<TypedPath> list(Path path, int maxDepth,
    //          Filter<? super TypedPath> filter) throws IOException {
    //        return null;
    //      }
    //
    //      @Override
    //      public List<Entry<T>> listEntries(Path path, int maxDepth,
    //          Filter<? super Entry<T>> filter) {
    //        return null;
    //      }
    //    });
    //    final BiConsumer<Event, Executor.Thread> eventHandler =
    //        executor.delegate(
    //            new BiConsumer<Event, Executor.Thread>() {
    //              @Override
    //              public void accept(final Event typedPath, final Executor.Thread thread) {
    //                tree.handleEvent(typedPath, thread);
    //              }
    //            });
    //    final SymlinkWatcher symlinkWatcher =
    //        new SymlinkWatcher(
    //            eventHandler,
    //            DEFAULT_SYMLINK_FACTORY,
    //            new OnError() {
    //              @Override
    //              public void apply(final IOException exception) {}
    //            },
    //            executor.copy());
    //    final PathWatcher<Event> pathWatcher =
    //        DEFAULT_PATH_WATCHER_FACTORY.create(
    //            eventHandler, executor.copy(), tree.readOnlyDirectoryRegistry());
    //    final FileCachePathWatcher<T> watcher = new FileCachePathWatcher<>(symlinkWatcher,
    // pathWatcher);
    //    return res;
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
