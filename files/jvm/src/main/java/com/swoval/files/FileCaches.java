package com.swoval.files;

import static com.swoval.files.PathWatcher.DEFAULT_FACTORY;

import com.swoval.files.Directory.Converter;
import com.swoval.files.Directory.Observer;
import java.io.IOException;

public class FileCaches {
  /**
   * Create a file cache
   *
   * @param converter Converts a path to the cached value type T
   * @param options Options for the cache.
   * @param <T> The value type of the cache entries
   * @return A file cache
   * @throws IOException if the {@link PathWatcher} cannot be initialized
   * @throws InterruptedException if the {@link PathWatcher} cannot be initialized
   */
  public static <T> FileCache<T> get(
      final Converter<T> converter, final Option... options)
      throws IOException, InterruptedException {
    return new FileCacheImpl<>(converter, DEFAULT_FACTORY, null, options);
  }

  /**
   * Create a file cache using a specific PathWatcher created by the provided factory
   *
   * @param converter Converts a path to the cached value type T
   * @param factory A factory to create a path watcher
   * @param options Options for the cache
   * @param <T> The value type of the cache entries
   * @return A file cache
   * @throws IOException if the {@link PathWatcher} cannot be initialized
   * @throws InterruptedException if the {@link PathWatcher} cannot be initialized
   */
  public static <T> FileCache<T> get(
      final Converter<T> converter, final PathWatchers.Factory factory, final Option... options)
      throws IOException, InterruptedException {
    return new FileCacheImpl<>(converter, factory, null, options);
  }

  /**
   * Create a file cache with an Observer of events
   *
   * @param converter Converts a path to the cached value type T
   * @param observer Observer of events for this cache
   * @param options Options for the cache
   * @param <T> The value type of the cache entries
   * @return A file cache
   * @throws IOException if the {@link PathWatcher} cannot be initialized
   * @throws InterruptedException if the {@link PathWatcher} cannot be initialized
   */
  public static <T> FileCache<T> get(
      final Converter<T> converter, final Observer<T> observer, final Option... options)
      throws IOException, InterruptedException {
    FileCache<T> res = new FileCacheImpl<>(converter, DEFAULT_FACTORY, null, options);
    res.addObserver(observer);
    return res;
  }

  /**
   * Create a file cache with an Observer of events
   *
   * @param converter Converts a path to the cached value type T
   * @param factory A factory to create a path watcher
   * @param observer Observer of events for this cache
   * @param options Options for the cache
   * @param <T> The value type of the cache entries
   * @return A file cache
   * @throws IOException if the {@link PathWatcher} cannot be initialized
   * @throws InterruptedException if the {@link PathWatcher} cannot be initialized
   */
  public static <T> FileCache<T> get(
      final Converter<T> converter,
      final PathWatcher.Factory factory,
      final Observer<T> observer,
      final Option... options)
      throws IOException, InterruptedException {
    FileCache<T> res = new FileCacheImpl<>(converter, factory, null, options);
    res.addObserver(observer);
    return res;
  }

  /** Options for the implementation of a {@link FileCache} */
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
    public static final FileCaches.Option NOFOLLOW_LINKS = new Option();
  }
}
