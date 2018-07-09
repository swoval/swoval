package com.swoval.files;

import com.swoval.functional.Filter;
import java.nio.file.Path;
import java.util.List;

/**
 * A repository for a directory. The {@link com.swoval.files.Repository#list(Path, int, Filter)}
 * method will only return non-empty results for paths that are children of the root directory,
 * specified by {@link com.swoval.files.DirectoryRepository#getPath}.
 */
public interface DirectoryRepository extends Repository {
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
   */
  List<Path> list(final int maxDepth, final Filter<? super Path> filter);
}
