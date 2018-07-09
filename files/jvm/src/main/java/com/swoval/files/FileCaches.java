package com.swoval.files;

import static com.swoval.files.PathWatchers.DEFAULT_FACTORY;

import com.swoval.files.Directory.Converter;
import com.swoval.files.Directory.Observer;
import com.swoval.files.PathWatchers.Factory;
import java.io.IOException;

/** Provides factory methods for generating instances of {@link com.swoval.files.FileCache}. */
public class FileCaches {
  /**
   * Create a file cache.
   *
   * @param converter converts a path to the cached value type T
   * @param options options for the cache
   * @param <T> the value type of the cache entries
   * @return a file cache.
   * @throws IOException if the {@link com.swoval.files.PathWatcher} cannot be initialized.
   * @throws InterruptedException if the {@link com.swoval.files.PathWatcher} cannot be initialized.
   */
  public static <T> FileCache<T> get(final Converter<T> converter, final Option... options)
      throws IOException, InterruptedException {
    return new FileCacheImpl<>(converter, DEFAULT_FACTORY, null, options);
  }

  /**
   * Create a file cache with an Observer of events.
   *
   * @param converter converts a path to the cached value type T
   * @param observer observer of events for this cache
   * @param options options for the cache
   * @param <T> the value type of the cache entries
   * @return a file cache.
   * @throws IOException if the {@link PathWatcher} cannot be initialized.
   * @throws InterruptedException if the {@link PathWatcher} cannot be initialized.
   */
  public static <T> FileCache<T> get(
      final Converter<T> converter, final Observer<T> observer, final Option... options)
      throws IOException, InterruptedException {
    FileCache<T> res = new FileCacheImpl<>(converter, DEFAULT_FACTORY, null, options);
    res.addObserver(observer);
    return res;
  }

  /**
   * Create a file cache using a factory to provide an instance of{@link
   * com.swoval.files.PathWatcher}.
   *
   * @param converter converts a path to the cached value type T
   * @param factory creates a {@link com.swoval.files.PathWatcher}
   * @param options options for the cache
   * @param <T> The value type of the cache entries
   * @return A file cache
   * @throws IOException if the {@link PathWatcher} cannot be initialized
   * @throws InterruptedException if the {@link PathWatcher} cannot be initialized
   */
  static <T> FileCache<T> get(
      final Converter<T> converter, final PathWatchers.Factory factory, final Option... options)
      throws IOException, InterruptedException {
    return new FileCacheImpl<>(converter, factory, null, options);
  }

  /**
   * Create a file cache with an Observer of events.
   *
   * @param converter converts a path to the cached value type T
   * @param factory a factory to create a path watcher
   * @param observer an observer of events for this cache
   * @param options options for the cache
   * @param <T> the value type of the cache entries
   * @return a file cache.
   * @throws IOException if the {@link PathWatcher} cannot be initialized.
   * @throws InterruptedException if the {@link PathWatcher} cannot be initialized.
   */
  static <T> FileCache<T> get(
      final Converter<T> converter,
      final Factory factory,
      final Observer<T> observer,
      final Option... options)
      throws IOException, InterruptedException {
    FileCache<T> res = new FileCacheImpl<>(converter, factory, null, options);
    res.addObserver(observer);
    return res;
  }

  /** Options for the implementation of a {@link FileCache}. */
  public static class Option {
    /** This constructor is needed for code gen. Otherwise only the companion is generated */
    Option() {}
    /**
     * When the FileCache encounters a symbolic link with a path as target, treat the symbolic link
     * like a path. Note that it is possible to create a loop if two directories mutually link to
     * each other symbolically. When this happens, the FileCache will throw a {@link
     * java.nio.file.FileSystemLoopException} when attempting to register one of these directories
     * or if the link that completes the loop is added to a registered path.
     */
    static final FileCaches.Option NOFOLLOW_LINKS = new Option();
  }
}
