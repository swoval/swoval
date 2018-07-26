package com.swoval.files;

import com.swoval.files.DataViews.Entry;
import java.nio.file.Path;
import java.util.List;

interface UpdatableFileTreeDataView<T> extends FileTreeDataView<T> {
  /**
   * Updates the CachedDirectory entry for a particular typed path.
   *
   * @param typedPath the path to update
   * @return a list of updates for the path. When the path is new, the updates have the
   *     oldCachedPath field set to null and will contain all of the children of the new path when
   *     it is a directory. For an existing path, the List contains a single Updates that contains
   *     the previous and new {@link Entry}.
   */
  FileTreeViews.Updates<T> update(final TypedPath typedPath, final Executor.Thread thread);

  /**
   * Remove a path from the directory.
   *
   * @param path the path to remove
   * @return a List containing the Entry instances for the removed path. The result also contains
   *     the cache entries for any children of the path when the path is a non-empty directory.
   */
  List<Entry<T>> remove(final Path path, final Executor.Thread thread);
}
