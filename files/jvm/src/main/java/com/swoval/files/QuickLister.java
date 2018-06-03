package com.swoval.files;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystemLoopException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Implementation class for {@link QuickList#list} */
public interface QuickLister {
  List<QuickFile> list(final Path path, final int maxDepth, final boolean followLinks)
      throws IOException;

  List<QuickFile> list(
      final Path path,
      final int maxDepth,
      final boolean followLinks,
      final Filter<? super QuickFile> filter)
      throws IOException;
}

abstract class QuickListerImpl implements QuickLister {
  /*
   * These constants must be kept in sync with the native quick list implementation
   */
  static final int UNKNOWN = 0;
  static final int DIRECTORY = 1;
  static final int FILE = 2;
  static final int LINK = 4;
  static final int EOF = 8;
  static final int ENOENT = -1;
  static final int EACCES = -2;
  static final int ENOTDIR = -3;
  static final int ESUCCESS = -4;
  private static final Filter<Object> AllPass =
      new Filter<Object>() {
        @Override
        public boolean accept(Object o) {
          return true;
        }
      };

  protected abstract ListResults listDir(final String dir, final boolean followLinks)
      throws IOException;

  static class ListResults {
    private final List<String> directories = new ArrayList<>();
    private final List<String> files = new ArrayList<>();
    private final List<String> symlinks = new ArrayList<>();

    List<String> getDirectories() {
      return directories;
    }

    List<String> getFiles() {
      return files;
    }

    List<String> getSymlinks() {
      return symlinks;
    }

    void addDir(final String dir) {
      directories.add(dir);
    }

    void addFile(final String file) {
      files.add(file);
    }

    void addSymlink(final String link) {
      symlinks.add(link);
    }

    @Override
    public String toString() {
      return "ListResults(\n  directories = "
          + directories
          + ",\n  files = "
          + files
          + ", \n  symlinks = "
          + symlinks
          + "\n)";
    }
  }

  @Override
  public final List<QuickFile> list(final Path path, final int maxDepth, final boolean followLinks)
      throws IOException {
    return list(path, maxDepth, followLinks, AllPass);
  }

  @Override
  public final List<QuickFile> list(
      final Path path,
      final int maxDepth,
      final boolean followLinks,
      final Filter<? super QuickFile> filter)
      throws IOException {
    final List<QuickFile> result = new ArrayList<>();
    final Set<String> visited = (followLinks && maxDepth > 0) ? new HashSet<String>() : null;
    listDirImpl(path.toString(), 1, maxDepth, followLinks, result, filter, visited);
    return result;
  }

  private void listDirImpl(
      final String dir,
      final int depth,
      final int maxDepth,
      final boolean followLinks,
      final List<QuickFile> result,
      final Filter<? super QuickFile> filter,
      final Set<String> visited)
      throws IOException {
    if (visited != null) visited.add(dir);
    final QuickListerImpl.ListResults listResults = listDir(dir, followLinks);
    final Iterator<String> it = listResults.getDirectories().iterator();
    while (it.hasNext()) {
      final String part = it.next();
      if (!part.equals(".") && !part.equals("..")) {
        final String name = dir + File.separator + part;
        final QuickFileImpl file = new QuickFileImpl(name, DIRECTORY);
        if (filter.accept(file)) {
          result.add(file);
          if (depth < maxDepth) {
            listDirImpl(name, depth + 1, maxDepth, followLinks, result, filter, visited);
          }
        }
      }
    }
    final Iterator<String> fileIt = listResults.getFiles().iterator();
    while (fileIt.hasNext()) {
      final QuickFileImpl file = new QuickFileImpl(dir + File.separator + fileIt.next(), FILE);
      if (filter.accept(file)) {
        result.add(file);
      }
    }
    final Iterator<String> symlinkIt = listResults.getSymlinks().iterator();
    while (symlinkIt.hasNext()) {
      final String fileName = dir + File.separator + symlinkIt.next();
      final BasicFileAttributes attrs =
          followLinks ? Files.readAttributes(Paths.get(fileName), BasicFileAttributes.class) : null;
      final int kind = attrs != null && attrs.isDirectory() ? DIRECTORY | LINK : FILE | LINK;
      final QuickFileImpl file = new QuickFileImpl(fileName, kind);
      if (filter.accept(file)) {
        result.add(file);
        if (((kind & DIRECTORY) != 0) && depth < maxDepth && visited != null) {
          if (visited.add(file.toPath().toRealPath().toString())) {
            listDirImpl(fileName, depth + 1, maxDepth, true, result, filter, visited);
          } else {
            throw new FileSystemLoopException(fileName);
          }
        }
      }
    }
  }
}