package com.swoval.files;

import com.swoval.functional.Filter;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystemLoopException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

final class SimpleFileTreeView implements FileTreeView {
  /*
   * These constants must be kept in sync with the native quick list implementation
   */
  static final int UNKNOWN = Entries.UNKNOWN;
  static final int DIRECTORY = Entries.DIRECTORY;
  static final int FILE = Entries.FILE;
  static final int LINK = Entries.LINK;
  static final int NONEXISTENT = Entries.NONEXISTENT;
  private final DirectoryLister directoryLister;
  private final boolean followLinks;

  SimpleFileTreeView(final DirectoryLister directoryLister, final boolean followLinks) {
    this.directoryLister = directoryLister;
    this.followLinks = followLinks;
  }

  @Override
  public List<TypedPath> list(
      final Path path, final int maxDepth, final Filter<? super TypedPath> filter)
      throws IOException {
    final List<TypedPath> result = new ArrayList<>();
    final Set<Path> visited = (followLinks && maxDepth > 0) ? new HashSet<Path>() : null;
    listDirImpl(path, 1, maxDepth, result, filter, visited);
    if (visited != null) visited.clear();
    return result;
  }

  @Override
  public void close() {}

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

  private int getSymbolicLinkTargetKind(final Path path, final boolean followLinks)
      throws IOException {
    if (followLinks) {
      try {
        final BasicFileAttributes attrs = NioWrappers.readAttributes(path);
        return LINK | (attrs.isDirectory() ? DIRECTORY : attrs.isRegularFile() ? FILE : UNKNOWN);
      } catch (final NoSuchFileException e) {
        return NONEXISTENT;
      }
    } else {
      return LINK;
    }
  }

  private void listDirImpl(
      final Path dir,
      final int depth,
      final int maxDepth,
      final List<TypedPath> result,
      final Filter<? super TypedPath> filter,
      final Set<Path> visited)
      throws IOException {
    if (visited != null) visited.add(dir);
    final SimpleFileTreeView.ListResults listResults =
        directoryLister.apply(dir.toAbsolutePath().toString(), followLinks);
    final Iterator<String> it = listResults.getDirectories().iterator();
    while (it.hasNext()) {
      final String part = it.next();
      if (!part.equals(".") && !part.equals("..")) {
        final Path path = Paths.get(dir + File.separator + part);
        final TypedPath file = TypedPaths.get(path, DIRECTORY);
        if (filter.accept(file)) {
          result.add(file);
          if (depth < maxDepth) {
            listDirImpl(path, depth + 1, maxDepth, result, filter, visited);
          }
        }
      }
    }
    final Iterator<String> fileIt = listResults.getFiles().iterator();
    while (fileIt.hasNext()) {
      final TypedPath typedPath =
          TypedPaths.get(Paths.get(dir + File.separator + fileIt.next()), FILE);
      if (filter.accept(typedPath)) {
        result.add(typedPath);
      }
    }
    final Iterator<String> symlinkIt = listResults.getSymlinks().iterator();
    while (symlinkIt.hasNext()) {
      final Path fileName = Paths.get(dir + File.separator + symlinkIt.next());
      final TypedPath typedPath =
          TypedPaths.get(fileName, getSymbolicLinkTargetKind(fileName, followLinks));
      if (filter.accept(typedPath)) {
        result.add(typedPath);
        if (typedPath.isDirectory() && depth < maxDepth && visited != null) {
          if (visited.add(typedPath.getPath().toRealPath())) {
            listDirImpl(fileName, depth + 1, maxDepth, result, filter, visited);
          } else {
            throw new FileSystemLoopException(fileName.toString());
          }
        }
      }
    }
  }
}
