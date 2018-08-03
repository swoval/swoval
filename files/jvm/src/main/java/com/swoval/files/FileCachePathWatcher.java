package com.swoval.files;

import static com.swoval.functional.Filters.AllPass;

import com.swoval.files.FileTreeDataViews.Entry;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;

class FileCachePathWatcher<T> implements AutoCloseable {
  private final SymlinkWatcher symlinkWatcher;
  private final PathWatcher<PathWatchers.Event> pathWatcher;
  private final FileCacheDirectoryTree<T> tree;

  FileCachePathWatcher(
      final FileCacheDirectoryTree<T> tree, final PathWatcher<PathWatchers.Event> pathWatcher) {
    this.symlinkWatcher = tree.symlinkWatcher;
    this.pathWatcher = pathWatcher;
    this.tree = tree;
  }

  boolean register(final Path path, final int maxDepth) throws IOException {
    final CachedDirectory<T> dir = tree.register(path, maxDepth, pathWatcher);
    if (dir != null && symlinkWatcher != null) {
      if (dir.getEntry().isSymbolicLink()) {
        symlinkWatcher.addSymlink(path, maxDepth);
      }
      final Iterator<Entry<T>> it = dir.listEntries(dir.getMaxDepth(), AllPass).iterator();
      while (it.hasNext()) {
        final FileTreeDataViews.Entry<T> entry = it.next();
        if (entry.isSymbolicLink()) {
          final int depth = path.relativize(entry.getPath()).getNameCount();
          symlinkWatcher.addSymlink(
              entry.getPath(), maxDepth == Integer.MAX_VALUE ? maxDepth : maxDepth - depth);
        }
      }
    }
    return dir != null;
  }

  void unregister(final Path path) {
    tree.unregister(path);
    pathWatcher.unregister(path);
  }

  public void close() {
    pathWatcher.close();
    if (symlinkWatcher != null) symlinkWatcher.close();
  }
}
