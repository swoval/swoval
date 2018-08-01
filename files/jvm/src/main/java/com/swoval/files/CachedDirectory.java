package com.swoval.files;

import com.swoval.files.FileTreeDataViews.Entry;
import com.swoval.functional.Filter;
import java.nio.file.Path;
import java.util.List;

interface CachedDirectory<T>
    extends UpdatableFileTreeDataView<T>, DirectoryDataView<T>, AutoCloseable {

  /**
   * List the children of the path specified by {@link CachedDirectory#getPath()}, excluding the
   * {@link FileTreeDataViews.Entry entry} for the path itself. When the maxDepth parameter is
   * <code>-1
   * </code>, return just the entry for the path itself.
   *
   * @param maxDepth the maximum depth of children (see {@link DirectoryView#getMaxDepth()})
   * @param filter only include entries matching this filter
   * @return a list containing all of the entries included by the filter up to the max depth.
   */
  @Override
  List<Entry<T>> listEntries(final int maxDepth, final Filter<? super Entry<T>> filter);

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
   */
  @Override
  List<Entry<T>> listEntries(
      final Path path, final int maxDepth, final Filter<? super Entry<T>> filter);

  /**
   * List all of the files in the root directory, returning only those files that are accepted by
   * the provided filter. Unlike {@link FileTreeView}, this implementation cannot throw an
   * IOException because list should be using the cache and not performing IO.
   *
   * @param maxDepth the maximum depth of subdirectories to query
   * @param filter include only paths accepted by the filter
   * @return a List of {@link java.nio.file.Path} instances accepted by the filter.
   */
  @Override
  List<TypedPath> list(final int maxDepth, final Filter<? super TypedPath> filter);

  /**
   * List all of the files for the {@code path}, returning only those files that are accepted by the
   * provided filter. Unlike {@link FileTreeView}, this implementation cannot throw an IOException
   * because list should be using the cache and not performing IO.
   *
   * @param path the root path to list
   * @param maxDepth the maximum depth of subdirectories to query
   * @param filter include only paths accepted by the filter
   * @return a List of {@link java.nio.file.Path} instances accepted by the filter.
   */
  @Override
  List<TypedPath> list(final Path path, final int maxDepth, final Filter<? super TypedPath> filter);

  /**
   * Returns the {@link FileTreeDataViews.Entry} associated with the path specified by {@link
   * CachedDirectory#getPath()}.
   *
   * @return the entry
   */
  Entry<T> getEntry();

  /** Catch any exceptions in close. */
  @Override
  void close();
}
