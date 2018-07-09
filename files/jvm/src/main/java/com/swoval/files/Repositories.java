package com.swoval.files;

import com.swoval.files.Directory.Converter;
import com.swoval.functional.Filters;
import java.io.IOException;
import java.nio.file.Path;

public class Repositories {

  private static final Converter<Path> PATH_CONVERTER =
      new Converter<Path>() {
        @Override
        public Path apply(final Path path) {
          return path;
        }
      };

  private Repositories() {}

  /**
   * Make a new recursive Directory with no cache value associated with the path.
   *
   * @param path the path to monitor
   * @return a directory whose entries just contain the path itself.
   * @throws IOException when an error is encountered traversing the directory.
   */
  public static Directory<Path> of(final Path path) throws IOException {
    return of(path, true);
  }

  /**
   * Make a new Directory with no cache value associated with the path.
   *
   * @param path the path to monitor
   * @param depth sets how the limit for how deep to traverse the children of this directory
   * @return a directory whose entries just contain the path itself.
   * @throws IOException when an error is encountered traversing the directory.
   */
  public static Directory<Path> of(final Path path, final int depth) throws IOException {
    return new Directory<>(path, path, PATH_CONVERTER, depth, Filters.AllPass).init();
  }

  /**
   * Make a new Directory with no cache value associated with the path.
   *
   * @param path the path to monitor
   * @param recursive Toggles whether or not to cache the children of subdirectories
   * @return a directory whose entries just contain the path itself.
   * @throws IOException when an error is encountered traversing the directory.
   */
  public static Directory<Path> of(final Path path, final boolean recursive) throws IOException {
    return new Directory<>(
            path, path, PATH_CONVERTER, recursive ? Integer.MAX_VALUE : 0, Filters.AllPass)
        .init();
  }

  /**
   * Make a new Directory with a cache entries created by {@code converter}.
   *
   * @param path the path to cache
   * @param converter a function to create the cache value for each path
   * @param <T> the cache value type
   * @return a directory with entries of type T.
   * @throws IOException when an error is encountered traversing the directory.
   */
  public static <T> Directory<T> cached(final Path path, final Converter<T> converter)
      throws IOException {
    return new Directory<>(path, path, converter, Integer.MAX_VALUE, Filters.AllPass).init();
  }

  /**
   * Make a new Directory with a cache entries created by {@code converter}.
   *
   * @param path the path to cache
   * @param converter a function to create the cache value for each path
   * @param recursive toggles whether or not to the children of subdirectories
   * @param <T> the cache value type
   * @return a directory with entries of type T.
   * @throws IOException when an error is encountered traversing the directory.
   */
  public static <T> Directory<T> cached(
      final Path path, final Converter<T> converter, final boolean recursive) throws IOException {
    return new Directory<>(
            path, path, converter, recursive ? Integer.MAX_VALUE : 0, Filters.AllPass)
        .init();
  }

  /**
   * Make a new Directory with a cache entries created by {@code converter}.
   *
   * @param path the path to cache
   * @param converter a function to create the cache value for each path
   * @param depth determines how many levels of children of subdirectories to include in the results
   * @param <T> the cache value type
   * @return a directory with entries of type T.
   * @throws IOException when an error is encountered traversing the directory.
   */
  public static <T> Directory<T> cached(
      final Path path, final Converter<T> converter, final int depth) throws IOException {
    return new Directory<>(path, path, converter, depth, Filters.AllPass).init();
  }
}
