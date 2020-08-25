package com.swoval.files;

import com.swoval.functional.Filter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Lists all of the children of a particular directory up to a specified depth. It provides an
 * alternative to `java.nio.file.Files.walkFileTree` or `java.nio.file.Files.list` to recursively
 * list the contents of a directory. Implementations are provided in {@link FileTreeViews} including
 * native implementations for most popular platforms that can outperform the java built-ins.
 */
public interface FileTreeView extends AutoCloseable {
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
