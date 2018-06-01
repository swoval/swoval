package com.swoval.files;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Provides utilities for listing files and getting subpaths */
class FileOps {
  public static final FileFilter AllPass =
      new FileFilter() {
        @Override
        public boolean accept(File file) {
          return true;
        }

        @Override
        public String toString() {
          return "AllPass";
        }
      };

  /**
   * Returns the files in a directory.
   *
   * @param path The directory to list
   * @param recursive Include paths in subdirectories when set to true
   * @return Array of paths
   */
  public static List<File> list(final Path path, final boolean recursive) throws IOException {
    return list(path, recursive, AllPass);
  }

  /**
   * Returns the files in a directory.
   *
   * @param path The directory to list
   * @param recursive Include paths in subdirectories when set to true
   * @param filter Include only paths accepted by the filter
   * @return Array of Paths
   */
  public static List<File> list(final Path path, final boolean recursive, final FileFilter filter) throws IOException {
    final List<File> result = new ArrayList<>();
    final Iterator<QuickFile> it =
        QuickList.list(path, recursive ? Integer.MAX_VALUE : 1, true).iterator();
    while (it.hasNext()) {
      final QuickFile quickFile = it.next();
      if (filter.accept(quickFile.asFile())) {
        result.add(quickFile.toFile());
      }
    }
    return result;
  }

  /**
   * Returns the name components of a path in an array.
   *
   * @param path The path from which we extract the parts.
   * @return Empty array if path is an empty relative path, otherwise return the name parts.
   */
  public static List<Path> parts(final Path path) {
    final Iterator<Path> it = path.iterator();
    final List<Path> result = new ArrayList<>();
    while (it.hasNext()) result.add(it.next());
    return result;
  }
}
