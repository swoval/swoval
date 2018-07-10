package com.swoval.files;

import com.swoval.functional.Filter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * A repository for a directory. The {@link FileTreeView#list(Path, int, Filter)} method will only
 * return non-empty results for paths that are children of the root directory, specified by {@link
 * DirectoryView#getPath}.
 */
public interface DirectoryView extends FileTreeView {
  /**
   * Return the path of the root directory.
   *
   * @return the path of the root directory.
   */
  Path getPath();
  /**
   * List all of the files in the root directory, returning only those files that are accepted by
   * the provided filter.
   *
   * @param maxDepth the maximum depth of subdirectories to query
   * @param filter include only paths accepted by the filter
   * @return a List of {@link java.nio.file.Path} instances accepted by the filter.
   * @throws IOException if there is an error listing the directory.
   */
  List<TypedPath> list(final int maxDepth, final Filter<? super TypedPath> filter)
      throws IOException;

  /**
   * Returns the maximum depth of children of subdirectories to include below the path specified by
   * {@link DirectoryView#getPath()}. For example, when the value is <code>-1</code>, then the
   * DirectoryView should include only itself. When the value is <code>0</code>, it should include
   * all of the subdirectories and files in the path. When the value is <code>1</code>, it should
   * include all of the subdirectories and files in the path and all of the subdirectories and files
   * in the immediate subdirectories of the path, but not the children of these nested
   * subdirectories. When the value is <code>Integer.MAX_VALUE</code>, all children of the path are
   * included.
   *
   * @return the maximum depth of subdirectory children to include.
   */
  int getMaxDepth();
}
