package com.swoval.files;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Implementation class for {@link QuickList#list}
 */
public interface QuickLister {
  List<QuickFile> list(final Path path, final int maxDepth, final boolean followLinks)
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

  protected abstract ListResults listDir(final String dir, final boolean followLinks)
      throws IOException;

  public static class ListResults {
    private final List<String> directories = new ArrayList<>();
    private final List<String> files = new ArrayList<>();

    public List<String> getDirectories() {
      return directories;
    }

    public List<String> getFiles() {
      return files;
    }

    public void addDir(final String dir) {
      directories.add(dir);
    }

    public void addFile(final String file) {
      files.add(file);
    }
  }

  public final List<QuickFile> list(final Path path, final int maxDepth, final boolean followLinks)
      throws IOException {
    final List<QuickFile> result = new ArrayList<>();
    listDirImpl(path.toString(), 1, maxDepth, followLinks, result);
    return result;
  }

  private void listDirImpl(
      final String dir,
      final int depth,
      final int maxDepth,
      final boolean followLinks,
      final List<QuickFile> result)
      throws IOException {
    final QuickListerImpl.ListResults listResults = listDir(dir, followLinks);
    final Iterator<String> it = listResults.getDirectories().iterator();
    while (it.hasNext()) {
      final String part = it.next();
      if (!part.equals(".") && !part.equals("..")) {
        final String name = dir + File.separator + part;
        result.add(new QuickFileImpl(name, DIRECTORY));
        if (depth < maxDepth) {
          listDirImpl(name, depth + 1, maxDepth, followLinks, result);
        }
      }
    }
    final Iterator<String> fileIt = listResults.getFiles().iterator();
    while (fileIt.hasNext()) {
      result.add(new QuickFileImpl(dir + File.separator + fileIt.next(), FILE));
    }
  }
}
