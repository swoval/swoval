package com.swoval.files;

import com.swoval.files.FileTreeDataViews.CacheObserver;
import com.swoval.files.FileTreeDataViews.Converter;
import com.swoval.files.FileTreeDataViews.Converters;
import com.swoval.files.FileTreeDataViews.Entry;
import com.swoval.files.FileTreeViews.Observer;
import com.swoval.files.PathWatchers.Event;
import com.swoval.functional.Either;
import com.swoval.functional.Filter;
import com.swoval.functional.IOFunction;
import com.swoval.logging.Logger;
import com.swoval.logging.Loggers;
import com.swoval.logging.Loggers.Level;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Provides factory methods for generating instances of {@link FileTreeRepository}. */
public class FileTreeRepositories {
  private FileTreeRepositories() {}
  /**
   * Create a default file tree repository. This method does not specify whether or not it follows
   * symlinks. If this matters prefer {@link FileTreeRepositories#noFollowSymlinks(Converter)} or
   * {@link FileTreeRepositories#followSymlinks(Converter)}.
   *
   * @param converter converts a path to the cached value type T
   * @param logger the logger
   * @param <T> the value type of the cache entries
   * @return a file tree repository.
   * @throws InterruptedException if the path watcher can't be started.
   * @throws IOException if an instance of {@link java.nio.file.WatchService} cannot be created.
   */
  public static <T> FileTreeRepository<T> getDefault(
      final Converter<T> converter, final Logger logger) throws InterruptedException, IOException {
    return impl(true, converter, logger, PATH_WATCHER_FACTORY);
  }

  /**
   * Create a default file tree repository. This method does not specify whether or not it follows
   * symlinks. If this matters prefer {@link FileTreeRepositories#noFollowSymlinks(Converter)} or
   * {@link FileTreeRepositories#followSymlinks(Converter)}.
   *
   * @param converter converts a path to the cached value type T
   * @param <T> the value type of the cache entries
   * @return a file tree repository.
   * @throws InterruptedException if the path watcher can't be started.
   * @throws IOException if an instance of {@link java.nio.file.WatchService} cannot be created.
   */
  public static <T> FileTreeRepository<T> getDefault(final Converter<T> converter)
      throws InterruptedException, IOException {
    return getDefault(converter, Loggers.getLogger());
  }

  /**
   * Create a default file tree repository. This method does not specify whether or not it follows
   * symlinks. If this matters prefer {@link FileTreeRepositories#noFollowSymlinks(Converter)} or
   * {@link FileTreeRepositories#followSymlinks(Converter)}.
   *
   * @return a file tree repository.
   * @throws InterruptedException if the path watcher can't be started.
   * @throws IOException if an instance of {@link java.nio.file.WatchService} cannot be created.
   */
  public static FileTreeRepository<TypedPath> getDefault()
      throws InterruptedException, IOException {
    return getDefault(Converters.IDENTITY, Loggers.getLogger());
  }

  /**
   * Create a file tree repository that follows symlinks.
   *
   * @param converter converts a path to the cached value type T
   * @param logger the logger
   * @param <T> the value type of the cache entries
   * @return a file tree repository.
   * @throws InterruptedException if the path watcher can't be started.
   * @throws IOException if an instance of {@link java.nio.file.WatchService} cannot be created.
   */
  public static <T> FollowSymlinks<T> followSymlinks(
      final Converter<T> converter, final Logger logger) throws InterruptedException, IOException {
    return new FollowWrapper<>(impl(true, converter, logger, PATH_WATCHER_FACTORY));
  }
  /**
   * Create a file tree repository that follows symlinks.
   *
   * @param converter converts a path to the cached value type T
   * @param <T> the value type of the cache entries
   * @return a file tree repository.
   * @throws InterruptedException if the path watcher can't be started.
   * @throws IOException if an instance of {@link java.nio.file.WatchService} cannot be created.
   */
  public static <T> FileTreeRepository<T> followSymlinks(final Converter<T> converter)
      throws InterruptedException, IOException {
    return followSymlinks(converter, Loggers.getLogger());
  }
  /**
   * Create a file tree repository that follows symlinks.
   *
   * @return a file tree repository.
   * @throws InterruptedException if the path watcher can't be started.
   * @throws IOException if an instance of {@link java.nio.file.WatchService} cannot be created.
   */
  public static FileTreeRepository<TypedPath> followSymlinks()
      throws InterruptedException, IOException {
    return followSymlinks(Converters.IDENTITY, Loggers.getLogger());
  }

  /**
   * Create a default file tree repository. This method does not specify whether or not it follows
   * symlinks so if this matters prefer TODO link.
   *
   * @param converter converts a path to the cached value type T
   * @param logger the logger
   * @param <T> the value type of the cache entries
   * @return a file tree repository.
   * @throws InterruptedException if the path watcher can't be started.
   * @throws IOException if an instance of {@link java.nio.file.WatchService} cannot be created.
   */
  public static <T> NoFollowSymlinks<T> noFollowSymlinks(
      final Converter<T> converter, final Logger logger) throws InterruptedException, IOException {
    return new NoFollowWrapper<>(impl(false, converter, logger, PATH_WATCHER_FACTORY));
  }
  /**
   * Create a default file tree repository. This method does not specify whether or not it follows
   * symlinks so if this matters prefer TODO link.
   *
   * @param converter converts a path to the cached value type T
   * @param <T> the value type of the cache entries
   * @return a file tree repository.
   * @throws InterruptedException if the path watcher can't be started.
   * @throws IOException if an instance of {@link java.nio.file.WatchService} cannot be created.
   */
  public static <T> NoFollowSymlinks<T> noFollowSymlinks(final Converter<T> converter)
      throws InterruptedException, IOException {
    return noFollowSymlinks(converter, Loggers.getLogger());
  }

  static <T> FileTreeRepository<T> impl(
      final boolean followLinks,
      final Converter<T> converter,
      final Logger logger,
      final IOFunction<Logger, PathWatcher<Event>> newPathWatcher)
      throws InterruptedException, IOException {
    try {
      final SymlinkWatcher symlinkWatcher =
          followLinks ? new SymlinkWatcher(newPathWatcher.apply(logger), logger) : null;
      final Executor callbackExecutor =
          Executor.make("FileTreeRepository-callback-executor", logger);
      final FileCacheDirectoryTree<T> tree =
          new FileCacheDirectoryTree<>(converter, callbackExecutor, symlinkWatcher, false, logger);
      final PathWatcher<PathWatchers.Event> pathWatcher = newPathWatcher.apply(logger);
      pathWatcher.addObserver(fileTreeObserver(tree, logger));
      final FileCachePathWatcher<T> watcher = new FileCachePathWatcher<>(tree, pathWatcher);
      return new FollowWrapper<T>(new FileTreeRepositoryImpl<T>(tree, watcher));
    } catch (final Interrupted e) {
      throw e.cause;
    }
  }

  private static Observer<Event> fileTreeObserver(
      final FileCacheDirectoryTree<?> tree, final Logger logger) {
    return new Observer<Event>() {
      @Override
      public void onError(final Throwable t) {
        if (Loggers.shouldLog(logger, Level.ERROR)) {
          logger.error("Error while monitoring the file system " + t);
        }
      }

      @Override
      public void onNext(final Event event) {
        tree.handleEvent(event);
      }
    };
  }

  private static class Wrapper<T> implements FileTreeRepository<T> {
    private final FileTreeRepository<T> delegate;

    Wrapper(final FileTreeRepository<T> delegate) {
      this.delegate = delegate;
    }

    @Override
    public Either<IOException, Boolean> register(Path path, int maxDepth) {
      return delegate.register(path, maxDepth);
    }

    @Override
    public void unregister(Path path) {
      delegate.unregister(path);
    }

    @Override
    public List<Entry<T>> listEntries(Path path, int maxDepth, Filter<? super Entry<T>> filter)
        throws IOException {
      return delegate.listEntries(path, maxDepth, filter);
    }

    @Override
    public List<TypedPath> list(Path path, int maxDepth, Filter<? super TypedPath> filter)
        throws IOException {
      return delegate.list(path, maxDepth, filter);
    }

    @Override
    public void close() {
      delegate.close();
    }

    @Override
    public int addObserver(Observer<? super Entry<T>> observer) {
      return delegate.addObserver(observer);
    }

    @Override
    public void removeObserver(int handle) {
      delegate.removeObserver(handle);
    }

    @Override
    public int addCacheObserver(CacheObserver<T> observer) {
      return delegate.addCacheObserver(observer);
    }
  }

  private static final class NoFollowWrapper<T> extends Wrapper<T> implements NoFollowSymlinks<T> {
    NoFollowWrapper(final FileTreeRepository<T> delegate) {
      super(delegate);
    }

    @Override
    public String toString() {
      return "NoFollowSymlinksFileTreeRepository@" + System.identityHashCode(this);
    }
  }

  private static final class FollowWrapper<T> extends Wrapper<T> implements FollowSymlinks<T> {
    FollowWrapper(final FileTreeRepository<T> delegate) {
      super(delegate);
    }

    @Override
    public String toString() {
      return "SymlinkFollowingFileTreeRepository@" + System.identityHashCode(this);
    }
  }

  private static class Interrupted extends RuntimeException {
    final InterruptedException cause;

    Interrupted(final InterruptedException cause) {
      this.cause = cause;
    }
  }

  private static final IOFunction<Logger, PathWatcher<Event>> PATH_WATCHER_FACTORY =
      new IOFunction<Logger, PathWatcher<Event>>() {
        @Override
        public PathWatcher<Event> apply(final Logger logger) throws IOException {
          try {
            return PathWatchers.noFollowSymlinks(logger);
          } catch (final InterruptedException e) {
            throw new Interrupted(e);
          }
        }
      };

  public interface FollowSymlinks<T> extends FileTreeRepository<T> {}

  public interface NoFollowSymlinks<T> extends FileTreeRepository<T> {}
}
