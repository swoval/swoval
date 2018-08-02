package com.swoval.files;

import com.swoval.functional.Either;
import com.swoval.functional.Filters;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Provides functional interfaces for processing and managing instances of {@link FileTreeDataView}.
 */
public class FileTreeDataViews {
  private FileTreeDataViews() {}

  /**
   * Make a new CachedDirectory with a cache entries created by {@code converter}.
   *
   * @param path the path to cache
   * @param converter a function to create the cache value for each path
   * @param depth determines how many levels of children of subdirectories to include in the results
   * @param followLinks sets whether or not to treat symbolic links whose targets as directories or
   *     files
   * @param <T> the cache value type
   * @return a directory with entries of type T.
   * @throws IOException when an error is encountered traversing the directory.
   */
  static <T> CachedDirectory<T> cachedUpdatable(
      final Path path, final Converter<T> converter, final int depth, final boolean followLinks)
      throws IOException {
    return new CachedDirectoryImpl<>(
            path, path, converter, depth, Filters.AllPass, FileTreeViews.getDefault(followLinks))
        .init();
  }

  /**
   * Make a new {@link DirectoryView} that caches the file tree but has no data value associated
   * with each value.
   *
   * @param path the path to monitor
   * @param depth sets how the limit for how deep to traverse the children of this directory
   * @param followLinks sets whether or not to treat symbolic links whose targets as directories or
   *     files
   * @return a directory whose entries just contain the path itself.
   * @throws IOException when an error is encountered traversing the directory.
   */
  public static <T> DirectoryDataView<T> cached(
      final Path path, final Converter<T> converter, final int depth, final boolean followLinks)
      throws IOException {
    return cachedUpdatable(path, converter, depth, followLinks);
  }

  /**
   * Container class for {@link CachedDirectoryImpl} entries. Contains both the path to which the
   * path corresponds along with a data value.
   *
   * @param <T> The value wrapped in the Entry
   */
  public interface Entry<T> extends TypedPath, Comparable<Entry<T>> {
    /**
     * Return the value associated with this entry. jjj
     *
     * @return the value associated with this entry.
     */
    Either<IOException, T> getValue();
  }

  /**
   * Converts a Path into an arbitrary value to be cached.
   *
   * @param <R> the generic type generated from the path.
   */
  public interface Converter<R> {

    /**
     * Convert the path to a value.
     *
     * @param path the path to convert
     * @return the converted value
     * @throws IOException when the value can't be computed
     */
    R apply(TypedPath path) throws IOException;
  }
}
