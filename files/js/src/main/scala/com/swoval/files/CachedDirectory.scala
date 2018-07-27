// Do not edit this file manually. It is autogenerated.

package com.swoval.files

import com.swoval.files.FileTreeDataViews.Entry
import com.swoval.functional.Filter
import java.nio.file.Path
import java.util.List

trait CachedDirectory[T <: AnyRef]
    extends UpdatableFileTreeDataView[T]
    with DirectoryView
    with AutoCloseable {

  /**
   * List the children of the path specified by [[CachedDirectory.getPath]], excluding the
   * [[FileTreeDataViews.Entry entry]] for the path itself. When the maxDepth parameter is <code>-1
   * </code>, return just the entry for the path itself.
   *
   * @param maxDepth the maximum depth of children (see [[DirectoryView.getMaxDepth]])
   * @param filter only include entries matching this filter
   * @return a list containing all of the entries included by the filter up to the max depth.
   */
  def listEntries(maxDepth: Int, filter: Filter[_ >: Entry[T]]): List[Entry[T]]

  /**
   * List all of the files in the root directory, returning only those files that are accepted by
   * the provided filter. Unlike [[FileTreeView]], this implementation cannot throw an
   * IOException because list should be using the cache and not performing IO.
   *
   * @param maxDepth the maximum depth of subdirectories to query
   * @param filter include only paths accepted by the filter
   * @return a List of [[java.nio.file.Path]] instances accepted by the filter.
   */
  override def list(maxDepth: Int, filter: Filter[_ >: TypedPath]): List[TypedPath]

  /**
   * List all of the files for the {@code path}, returning only those files that are accepted by the
   * provided filter. Unlike [[FileTreeView]], this implementation cannot throw an IOException
   * because list should be using the cache and not performing IO.
   *
   * @param path the root path to list
   * @param maxDepth the maximum depth of subdirectories to query
   * @param filter include only paths accepted by the filter
   * @return a List of [[java.nio.file.Path]] instances accepted by the filter.
   */
  override def list(path: Path, maxDepth: Int, filter: Filter[_ >: TypedPath]): List[TypedPath]

  /**
   * Returns the [[FileTreeDataViews.Entry]] associated with the path specified by [[CachedDirectory.getPath]].
   *
   * @return the entry
   */
  def getEntry(): Entry[T]

  /**
 Catch any exceptions in close.
   */
  override def close(): Unit

}
