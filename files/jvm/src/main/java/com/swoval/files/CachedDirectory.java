package com.swoval.files;

import com.swoval.files.DataViews.Entry;
import com.swoval.functional.Filter;
import java.nio.file.Path;
import java.util.List;

interface CachedDirectory<T> extends UpdatableDataView<T>, DirectoryView, AutoCloseable {

  /**
   * List the children of the path specified by {@link CachedDirectory#getPath()}, excluding the
   * {@link DataViews.Entry entry} for the path itself. When the maxDepth parameter is <code>-1
   * </code>, return just the entry for the path itself.
   *
   * @param maxDepth the maximum depth of children (see {@link DirectoryView#getMaxDepth()})
   * @param filter only include entries matching this filter
   * @return a list containing all of the entries included by the filter up to the max depth.
   */
  List<Entry<T>> listEntries(final int maxDepth, final Filter<? super Entry<T>> filter);

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
   * Returns the {@link DataViews.Entry} associated with the path specified by {@link
   * CachedDirectory#getPath()}.
   *
   * @return the entry
   */
  Entry<T> getEntry();

  /** Catch any exceptions in close. */
  @Override
  void close();
}
