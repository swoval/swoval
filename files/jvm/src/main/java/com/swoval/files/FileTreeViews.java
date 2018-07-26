package com.swoval.files;

import com.swoval.files.DataViews.Converter;
import com.swoval.files.DataViews.Entry;
import com.swoval.functional.Filter;
import com.swoval.functional.Filters;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public class FileTreeViews {
  static {
    final DirectoryLister[] listers = DirectoryListers.init();
    nativeDirectoryLister = listers[0];
    DirectoryLister nioLister = new NioDirectoryLister();
    nioDirectoryLister = nioLister;
    DirectoryLister defaultLister =
        listers[1] != null ? listers[1] : listers[0] != null ? listers[0] : nioLister;
    defaultDirectoryLister = defaultLister;
    defaultFileTreeView = new SimpleFileTreeView(defaultLister, false);
  }

  private static final DirectoryLister nioDirectoryLister;
  private static final DirectoryLister nativeDirectoryLister;
  private static final DirectoryLister defaultDirectoryLister;
  private static final FileTreeView defaultFileTreeView;

  private static final Converter<Path> PATH_CONVERTER =
      new Converter<Path>() {
        @Override
        public Path apply(final TypedPath typedPath) {
          return typedPath.getPath();
        }
      };

  private FileTreeViews() {}

  /**
   * Make a new CachedDirectory with no cache value associated with the path.
   *
   * @param path the path to monitor
   * @param depth sets how the limit for how deep to traverse the children of this directory
   * @param followLinks sets whether or not to treat symbolic links whose targets as directories or
   *     files
   * @return a directory whose entries just contain the path itself.
   * @throws IOException when an error is encountered traversing the directory.
   */
  public static DirectoryView of(final Path path, final int depth, final boolean followLinks)
      throws IOException {
    return new CachedDirectoryImpl<>(
            path, path, PATH_CONVERTER, depth, Filters.AllPass, getDefault(followLinks))
        .init();
  }

  /**
   * Make a new CachedDirectory with a cache entries created by {@code converter}.
   *
   * @param path the path to cache
   * @param converter a function to create the cache value for each path
   * @param depth determines how many levels of children of subdirectories to include in the results
   * @param <T> the cache value type
   * @return a directory with entries of type T.
   * @throws IOException when an error is encountered traversing the directory.
   */
  public static <T> CachedDirectory<T> cached(
      final Path path, final Converter<T> converter, final int depth, final boolean followLinks) throws IOException {
    return new CachedDirectoryImpl<>(
            path, path, converter, depth, Filters.AllPass, getDefault(followLinks))
        .init();
  }

  /**
   * Returns an instance of {@link FileTreeView} that uses only apis available in java.nio.file.
   * This may be used on platforms for which there is no native implementation of {@link
   * FileTreeView}.
   *
   * @param followLinks toggles whether or not to follow the targets of symbolic links to
   *     directories.
   * @return an instance of {@link FileTreeView}.
   */
  public static FileTreeView getNio(final boolean followLinks) {
    return new SimpleFileTreeView(nioDirectoryLister, followLinks);
  }

  /**
   * Returns an instance of {@link FileTreeView} that uses native jni functions to improve
   * performance compared to the {@link FileTreeView} returned by {@link
   * FileTreeViews#getNio(boolean)}.
   *
   * @param followLinks toggles whether or not to follow the targets of symbolic links to
   *     directories.
   * @return an instance of {@link FileTreeView}.
   */
  public static FileTreeView getNative(final boolean followLinks) {
    return new SimpleFileTreeView(nativeDirectoryLister, followLinks);
  }

  /**
   * Returns the default {@link FileTreeView} for the runtime platform. If a native implementation
   * is present, it will be used. Otherwise, it will fall back to the java.nio.file based
   * implementation.
   *
   * @param followLinks toggles whether or not to follow the targets of symbolic links to
   *     directories.
   * @return an instance of {@link FileTreeView}.
   */
  static FileTreeView getDefault(final boolean followLinks) {
    return new SimpleFileTreeView(defaultDirectoryLister, followLinks);
  }

  public static List<TypedPath> list(
      final Path path, final int maxDepth, final Filter<? super TypedPath> filter)
      throws IOException {
    return defaultFileTreeView.list(path, maxDepth, filter);
  }

  public interface Observer<T> {
    void onError(final Throwable t);

    void onNext(final T t);
  }
  /**
   * Provides callbacks to run when different types of file events are detected by the cache.
   *
   * @param <T> the type for the {@link DataViews.Entry} data
   */
  public interface CacheObserver<T> {

    /**
     * Callback to fire when a new path is created.
     *
     * @param newEntry the {@link DataViews.Entry} for the newly created file
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

  public interface Observable<T> {

    /**
     * Add an observer of events.
     *
     * @param observer the observer to add
     * @return the handle to the observer.
     */
    int addObserver(final Observer<T> observer);

    /**
     * Remove an observer.
     *
     * @param handle the handle that was returned by addObserver
     */
    void removeObserver(final int handle);
  }

  public interface ObservableCache<T> extends Observable<Entry<T>> {
    /**
     * Add an observer of cache events.
     *
     * @param observer the observer to add
     * @return the handle to the observer.
     */
    int addCacheObserver(final CacheObserver<T> observer);
  }

  static class Updates<T> implements CacheObserver<T> {

    private final List<Entry<T>> creations = new ArrayList<>();
    private final List<Entry<T>> deletions = new ArrayList<>();
    private final List<Entry<T>[]> updates = new ArrayList<>();

    public void observe(final CacheObserver<T> cacheObserver) {
      Collections.sort(creations);
      final Iterator<Entry<T>> creationIterator = creations.iterator();
      while (creationIterator.hasNext()) {
        cacheObserver.onCreate(creationIterator.next());
      }
      final Iterator<Entry<T>[]> updateIterator = updates.iterator();
      while (updateIterator.hasNext()) {
        final Entry<T>[] entries = updateIterator.next();
        cacheObserver.onUpdate(entries[0], entries[1]);
      }
      final Iterator<Entry<T>> deletionIterator = deletions.iterator();
      while (deletionIterator.hasNext()) {
        cacheObserver.onDelete(Entries.setExists(deletionIterator.next(), false));
      }
    }

    @Override
    public void onCreate(Entry<T> newEntry) {
      creations.add(newEntry);
    }

    @Override
    public void onDelete(Entry<T> oldEntry) {
      deletions.add(oldEntry);
    }

    @SuppressWarnings("unchecked")
    @Override
    public void onUpdate(Entry<T> oldEntry, Entry<T> newEntry) {
      updates.add(new Entry[] {oldEntry, newEntry});
    }

    @Override
    public void onError(IOException exception) {}
  }
}
