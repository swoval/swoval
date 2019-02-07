package com.swoval.files;

import com.swoval.files.FileTreeDataViews.Entry;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

interface UpdatableFileTreeDataView<T> {
  /**
   * Updates the CachedDirectory entry for a particular typed path.
   *
   * @param typedPath the path to update
   * @return a list of updates for the path. When the path is new, the updates have the
   *     oldCachedPath field set to null and will contain all of the children of the new path when
   *     it is a directory. For an existing path, the List contains a single Updates that contains
   *     the previous and new {@link Entry}.
   */
  FileTreeViews.Updates<T> update(final TypedPath typedPath) throws IOException;

  /**
   * Updates the CachedDirectory entry for a particular typed path.
   *
   * @param typedPath the path to update
   * @param rescanDirectories if true, the contents of a directory will be re-scanned whenever a
   *     directory is updated. This can be very expensive since it must list the entire subtree of
   *     the directory and perform io to compute the cache value. It does make it more likely,
   *     however, that the cache gets out of sync with the file system tree.
   * @return a list of updates for the path. When the path is new, the updates have the
   *     oldCachedPath field set to null and will contain all of the children of the new path when
   *     it is a directory. For an existing path, the List contains a single Updates that contains
   *     the previous and new {@link Entry}.
   */
  FileTreeViews.Updates<T> update(final TypedPath typedPath, final boolean rescanDirectories)
      throws IOException;

  /**
   * Remove a path from the directory.
   *
   * @param path the path to remove
   * @return a List containing the Entry instances for the removed path. The result also contains
   *     the cache entries for any children of the path when the path is a non-empty directory.
   */
  List<Entry<T>> remove(final Path path);
}
