package com.swoval.files;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

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
  public static List<File> list(final Path path, final boolean recursive) {
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
  @SuppressWarnings("EmptyCatchBlock")
  public static List<File> list(final Path path, final boolean recursive, final FileFilter filter) {
    final List<File> res = new ArrayList<>();
    final int maxDepth = recursive ? Integer.MAX_VALUE : 1;
    final Set<FileVisitOption> options = new HashSet<>();
    final FileVisitor<Path> visitor =
        new FileVisitor<Path>() {
          @Override
          public FileVisitResult preVisitDirectory(
              final Path dir, final BasicFileAttributes attrs) {
            if (!dir.equals(path)) {
              final File file =
                  new File(dir.toString()) {
                    @Override
                    public boolean isDirectory() {
                      return true;
                    }
                  };
              if (filter.accept(file)) {
                res.add(file);
              }
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) {
            final File ioFile =
                new File(file.toString()) {
                  @Override
                  public boolean isDirectory() {
                    return attrs.isDirectory();
                  }
                };
            if (filter.accept(ioFile)) {
              res.add(ioFile);
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(final Path file, final IOException exc) {
            return FileVisitResult.SKIP_SUBTREE;
          }

          @Override
          public FileVisitResult postVisitDirectory(final Path dir, final IOException exc) {
            return FileVisitResult.CONTINUE;
          }
        };
    try {
      Files.walkFileTree(path, options, maxDepth, visitor);
    } catch (IOException e) {
    }
    return res;
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
