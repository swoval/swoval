package com.swoval.files;

import com.swoval.files.FileTreeViews.Observable;
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
   * Make a new {@link DirectoryView} that caches the file tree but has no data value associated
   * with each value.
   *
   * @param path the path to monitor
   * @param converter computes the data value for each path found in the directory
   * @param depth sets how the limit for how deep to traverse the children of this directory
   * @param followLinks sets whether or not to treat symbolic links whose targets as directories or
   *     files
   * @param <T> the data type for this view
   * @return a directory whose entries just contain the path itself.
   * @throws IOException when an error is encountered traversing the directory.
   */
  public static <T> DirectoryDataView<T> cached(
      final Path path, final Converter<T> converter, final int depth, final boolean followLinks)
      throws IOException {
    return new CachedDirectoryImpl<>(
            TypedPaths.get(path),
            converter,
            depth,
            Filters.AllPass,
            FileTreeViews.getDefault(followLinks, false))
        .init();
  }

  /**
   * Container class for {@link CachedDirectoryImpl} entries. Contains both the path to which the
   * path corresponds along with a data value.
   *
   * @param <T> The value wrapped in the Entry
   */
  public interface Entry<T> extends Comparable<Entry<T>> {

    /**
     * Returns the {@link TypedPath} associated with this entry.
     *
     * @return the {@link TypedPath}.
     */
    TypedPath getTypedPath();
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
     * Convert the typedPath to a value.
     *
     * @param typedPath the typedPath to convert
     * @return the converted value
     * @throws IOException when the value can't be computed
     */
    R apply(final TypedPath typedPath) throws IOException;
  }

  /**
   * Provides callbacks to run when different types of file events are detected by the cache.
   *
   * @param <T> the type for the {@link Entry} data
   */
  public interface CacheObserver<T> {

    /**
     * Callback to fire when a new path is created.
     *
     * @param newEntry the {@link Entry} for the newly created file
     */
    void onCreate(final Entry<T> newEntry);

    /**
     * Callback to fire when a path is deleted.
     *
     * @param oldEntry the {@link Entry} for the deleted.
     */
    void onDelete(final Entry<T> oldEntry);

    /**
     * Callback to fire when a path is modified.
     *
     * @param oldEntry the {@link Entry} for the updated path
     * @param newEntry the {@link Entry} for the deleted path
     */
    void onUpdate(final Entry<T> oldEntry, final Entry<T> newEntry);

    /**
     * Callback to fire when an error is encountered generating while updating a path.
     *
     * @param exception The exception thrown by the computation
     */
    void onError(final IOException exception);
  }

  /**
   * A file tree cache that can be monitored for events.
   *
   * @param <T> the type of data stored in the cache.
   */
  public interface ObservableCache<T> extends Observable<Entry<T>> {
    /**
     * Add an observer of cache events.
     *
     * @param observer the observer to add
     * @return the handle to the observer.
     */
    int addCacheObserver(final CacheObserver<T> observer);
  }
}
