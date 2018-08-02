package com.swoval.files;

import com.swoval.files.FileTreeDataViews.Entry;
import com.swoval.functional.Filter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public interface DirectoryDataView<T> extends FileTreeDataView<T>, DirectoryView {

  /**
   * Returns the cache entry associated with the directory returned by {@link
   * DirectoryView#getPath()} }.
   *
   * @return the cache entry.
   */
  Entry<T> getEntry();
  /**
   * List all of the files for the {@code path</code> that are accepted by the <code>filter}.
   *
   * @param maxDepth the maximum depth of subdirectories to return
   * @param filter include only paths accepted by this
   * @return a List of Entry instances accepted by the filter. The list will be empty if the path is
   *     not a subdirectory of this CachedDirectory or if it is a subdirectory, but the
   *     CachedDirectory was created without the recursive flag.
   * @throws IOException if the path cannot be listed.
   */
  List<Entry<T>> listEntries(final int maxDepth, final Filter<? super Entry<T>> filter)
      throws IOException;

  /**
   * List all of the files for the {@code path}, returning only those files that are accepted by the
   * provided filter.
   *
   * @param maxDepth the maximum depth of subdirectories to query
   * @param filter include only paths accepted by the filter
   * @return a List of {@link java.nio.file.Path} instances accepted by the filter.
   * @throws IOException if the path cannot be listed.
   */
  List<TypedPath> list(final int maxDepth, final Filter<? super TypedPath> filter)
      throws IOException;
  /**
   * List all of the files for the {@code path</code> that are accepted by the <code>filter}.
   *
   * @param path the path to list. If this is a file, returns a list containing the Entry for the
   *     file or an empty list if the file is not monitored by the path.
   * @param maxDepth the maximum depth of subdirectories to return
   * @param filter include only paths accepted by this
   * @return a List of Entry instances accepted by the filter. The list will be empty if the path is
   *     not a subdirectory of this CachedDirectory or if it is a subdirectory, but the
   *     CachedDirectory was created without the recursive flag.
   * @throws IOException if the path cannot be listed.
   */
  List<Entry<T>> listEntries(
      final Path path, final int maxDepth, final Filter<? super Entry<T>> filter)
      throws IOException;

  /**
   * List all of the files for the {@code path}, returning only those files that are accepted by the
   * provided filter.
   *
   * @param path the root path to list
   * @param maxDepth the maximum depth of subdirectories to query
   * @param filter include only paths accepted by the filter
   * @return a List of {@link java.nio.file.Path} instances accepted by the filter.
   * @throws IOException if the path cannot be listed.
   */
  List<TypedPath> list(final Path path, final int maxDepth, final Filter<? super TypedPath> filter)
      throws IOException;
}
