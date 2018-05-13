package com.swoval.files;

import java.io.File;
import java.io.FileFilter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Provides utilities for listing files and getting subpaths */
class FileOps {
  public static final FileFilter AllPass = new FileFilter() {
    @Override
    public boolean accept(File file) {
      return true;
    }
  };

  /**
   * Returns the files in a directory.
   *
   * @param path The directory to list
   * @param recursive Include paths in subdirectories when set to true
   * @return Array of paths
   */
  public static List<Path> list(final Path path, final boolean recursive) {
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
  public static List<Path> list(final Path path, final boolean recursive, final FileFilter filter) {
    final List<Path> res = new ArrayList<>();
    listImpl(path.toFile(), recursive, filter, res);
    return res;
  }

  private static void listImpl(
      final File file, final boolean recursive, final FileFilter filter, final List<Path> result) {
    File[] files = file.listFiles(filter);
    if (files != null) {
      int i = 0;
      while (i < files.length) {
        final File f = files[i];
        result.add(f.toPath());
        if (f.isDirectory() && recursive) listImpl(f, recursive, filter, result);
        i += 1;
      }
    }
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
