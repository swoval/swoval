package com.swoval.files;

import static com.swoval.functional.Filters.AllPass;

import com.swoval.files.DataViews.Entry;
import com.swoval.functional.Either;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;

class FileCachePathWatcher<T> {
  private final SymlinkWatcher symlinkWatcher;
  private final ManagedPathWatcher pathWatcher;
  FileCachePathWatcher(final SymlinkWatcher symlinkWatcher, final ManagedPathWatcher pathWatcher) {
    this.symlinkWatcher = symlinkWatcher;
    this.pathWatcher = pathWatcher;
  }

  <T> boolean register(
      final Path path,
      final int maxDepth,
      final FileCacheDirectoryTree<T> tree,
      final Executor.Thread thread) {
    Either<IOException, CachedDirectory<T>> treeResult;
    try {
      treeResult = Either.right(tree.register(path, maxDepth, pathWatcher, thread));
    } catch (final IOException e) {
      treeResult = Either.left(e);
    }
    if (treeResult.isRight() && symlinkWatcher != null) {
      final CachedDirectory<T> dir = treeResult.get();
      if (dir != null) {
        final Iterator<Entry<T>> it = dir.listEntries(dir.getMaxDepth(), AllPass).iterator();
        while (it.hasNext()) {
          final DataViews.Entry entry = it.next();
          if (entry.isSymbolicLink()) {
            final int depth = path.relativize(entry.getPath()).getNameCount();
            symlinkWatcher.addSymlink(
                entry.getPath(), maxDepth == Integer.MAX_VALUE ? maxDepth : maxDepth - depth);
          }
        }
      }
    }
    return treeResult.isRight();
  }

  <T> void unregister(
      final Path path, final FileCacheDirectoryTree<T> tree, final Executor.Thread thread) {
    tree.unregister(path, thread);
    pathWatcher.unregister(path);
  }

  public void close(final Executor.Thread thread) {
    pathWatcher.close();
    symlinkWatcher.close();
  }
}
