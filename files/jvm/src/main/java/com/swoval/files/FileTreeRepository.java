package com.swoval.files;

import com.swoval.files.FileTreeDataViews.Converter;
import com.swoval.files.FileTreeDataViews.Entry;
import com.swoval.files.FileTreeViews.ObservableCache;
import com.swoval.functional.Either;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Provides an in memory cache of portions of the file system. Directories are added to the cache
 * using the {@link FileTreeRepository#register} method. Once a Path is added the cache, its
 * contents may be retrieved using the {@link FileTreeRepository#list} method. The cache stores the
 * path information in {@link Entry} instances.
 *
 * <p>A default implementation is provided by {@link FileTreeRepositories#get}. The user may cache
 * arbitrary information in the cache by customizing the {@link Converter} that is passed into the
 * factory {@link FileTreeRepositories#get}.
 *
 * <p>The cache allows the user to register a regular file, directory or symbolic link. After
 * registration, the cache should monitor the path (and in the case of symbolic links, the target of
 * the link) for updates. Whenever an update is detected, the cache updates its internal
 * representation of the file system. When that is complete, it will notify all of the registered
 * {@link com.swoval.files.Observers} of the change. In general, the update that is sent in the
 * callback will be visible if the user lists the relevant path. It is however, possible that if the
 * file is being updated rapidly that the internal state of the cache may change in between the
 * callback being invoked and the user listing the path. Once the file system activity settles down,
 * the cache should always end up in a consistent state where it mirrors the state of the file
 * system.
 *
 * <p>The semantics of the list method are very similar to the linux `ls` tool. Listing a directory
 * returns all of the subdirectories and files contained in the directory and the empty list if the
 * directory is empty. Listing a file, however, will return the entry for the file if it exists and
 * the empty list otherwise.
 *
 * @param <T> the type of data stored in the {@link Entry} instances for the cache
 */
public interface FileTreeRepository<T>
    extends FileTreeDataView<T>,
        PathWatcher<FileTreeDataViews.Entry<T>>,
        ObservableCache<T>,
        AutoCloseable {

  /**
   * Register a path with the cache. A successful call to this method will both start monitoring of
   * the path add will fill the cache for this path.
   *
   * @param path the directory to watch for file events and to add to the cache
   * @param maxDepth the maximum maxDepth of subdirectories to watch
   * @return an {@link com.swoval.functional.Either} that will return a right value when no
   *     exception is thrown. The right value will be true if the path has not been previously
   *     registered. The {@link com.swoval.functional.Either} will be a left if any IOException is
   *     thrown attempting to register the path.
   */
  Either<IOException, Boolean> register(final Path path, final int maxDepth);
  /**
   * Unregister a path from the cache. This removes the path from monitoring and from the cache so
   * long as the path isn't covered by another registered path. For example, if the path /foo was
   * previously registered, after removal, no changes to /foo or files in /foo should be detected by
   * the cache. Moreover, calling {@link FileTreeRepository#list} for /foo should return an empty
   * list. If, however, we register both /foo recursively and /foo/bar (recursively or not), after
   * unregistering /foo/bar, changes to /foo/bar should continue to be detected and /foo/bar should
   * be included in the list returned by {@link FileTreeRepository#list}.
   *
   * @param path the path to unregister
   */
  void unregister(final Path path);
}
