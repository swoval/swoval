package com.swoval.files;

import com.swoval.files.FileTreeDataViews.CacheObserver;
import com.swoval.files.FileTreeDataViews.Converter;
import com.swoval.files.FileTreeDataViews.Entry;
import com.swoval.functional.Filter;
import com.swoval.functional.Filters;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Provides static methods returning instances of the various view interfaces defined throughout
 * this package.
 */
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
  public static DirectoryView cached(final Path path, final int depth, final boolean followLinks)
      throws IOException {
    return new CachedDirectoryImpl<>(
            path, path, PATH_CONVERTER, depth, Filters.AllPass, getDefault(followLinks))
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
  @SuppressWarnings("unused")
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
  @SuppressWarnings("unused")
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
  public static FileTreeView getDefault(final boolean followLinks) {
    return new SimpleFileTreeView(defaultDirectoryLister, followLinks);
  }

  /**
   * List the contents of a path.
   *
   * @param path the path to list. If the path is a directory, return the children of this directory
   *     up to the maxDepth. If the path is a regular file and the maxDepth is <code>-1</code>, the
   *     path itself is returned. Otherwise an empty list is returned.
   * @param maxDepth the maximum depth of children to include in the results
   * @param filter only include paths accepted by this filter
   * @return a {@link java.util.List} of {@link TypedPath}
   * @throws IOException if the Path doesn't exist
   */
  public static List<TypedPath> list(
      final Path path, final int maxDepth, final Filter<? super TypedPath> filter)
      throws IOException {
    return defaultFileTreeView.list(path, maxDepth, filter);
  }

  /**
   * Generic Observer for an {@link Observable}.
   *
   * @param <T> the type under observation
   */
  public interface Observer<T> {
    /**
     * Fired if the underlying {@link Observable} encounters an error
     *
     * @param t the error
     */
    void onError(final Throwable t);

    /**
     * Callback that is invoked whenever a change is detected by the {@link Observable}.
     *
     * @param t the changed instance
     */
    void onNext(final T t);
  }

  public interface Observable<T> {

    /**
     * Add an observer of events.
     *
     * @param observer the observer to add
     * @return the handle to the observer.
     */
    int addObserver(final Observer<? super T> observer);

    /**
     * Remove an observer.
     *
     * @param handle the handle that was returned by addObserver
     */
    void removeObserver(final int handle);
  }

  static class Updates<T> implements CacheObserver<T> {

    private final List<Entry<T>> creations = new ArrayList<>();
    private final List<Entry<T>> deletions = new ArrayList<>();
    private final List<Entry<T>[]> updates = new ArrayList<>();

    void observe(final CacheObserver<T> cacheObserver) {
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
    public void onCreate(final Entry<T> newEntry) {
      creations.add(newEntry);
    }

    @Override
    public void onDelete(final Entry<T> oldEntry) {
      deletions.add(oldEntry);
    }

    @SuppressWarnings("unchecked")
    @Override
    public void onUpdate(final Entry<T> oldEntry, final Entry<T> newEntry) {
      updates.add(new Entry[] {oldEntry, newEntry});
    }

    @Override
    public void onError(final IOException exception) {}
  }
}
