package com.swoval.files;

import com.swoval.files.Directory.Observer;
import com.swoval.files.Directory.OnChange;
import com.swoval.functional.Either;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Provides an in memory cache of portions of the file system. Directories are added to the cache
 * using the {@link FileCache#register(Path, boolean)} method. Once a Path is added the cache, its
 * contents may be retrieved using the {@link FileCache#list(Path, boolean, Directory.EntryFilter)}
 * method. The cache stores the path information in {@link Directory.Entry} instances.
 *
 * <p>A default implementation is provided by {@link FileCaches#get}. The user may cache arbitrary
 * information in the cache by customizing the {@link Directory.Converter} that is passed into the
 * factory {@link FileCaches#get}.
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
 * @param <T> The type of data stored in the {@link Directory.Entry} instances for the cache
 */
public interface FileCache<T> extends AutoCloseable {

  /**
   * Add observer of file events
   *
   * @param observer The new observer
   * @return handle that can be used to remove the callback using {@link #removeObserver(int)}
   */
  int addObserver(final Observer<T> observer);

  /**
   * Add callback to fire when a file event is detected by the monitor
   *
   * @param onChange The callback to fire on file events
   * @return handle that can be used to remove the callback using {@link #removeObserver(int)}
   */
  int addCallback(final OnChange<T> onChange);

  /**
   * Stop firing the previously registered callback where {@code handle} is returned by {@link
   * #addObserver(Directory.Observer)}
   *
   * @param handle A handle to the observer added by {@link #addObserver(Directory.Observer)}
   */
  void removeObserver(final int handle);

  /**
   * Lists the cache elements in the particular path
   *
   * @param path The path to list. This may be a file in which case the result list contains only
   *     this path or the empty list if the path is not monitored by the cache.
   * @param maxDepth The maximum depth of children of the parent to traverse in the tree.
   * @param filter Only include cache entries that are accepted by the filter.
   * @return The list of cache elements. This will be empty if the path is not monitored in a
   *     monitored path. If the path is a file and the file is monitored by the cache, the returned
   *     list will contain just the cache entry for the path.
   */
  List<Directory.Entry<T>> list(
      final Path path, final int maxDepth, final Directory.EntryFilter<? super T> filter);

  /**
   * Lists the cache elements in the particular path
   *
   * @param path The path to list. This may be a file in which case the result list contains only
   *     this path or the empty list if the path is not monitored by the cache.
   * @param recursive Toggles whether or not to include paths in subdirectories. Even when the cache
   *     is recursively monitoring the input path, it will not return cache entries for children if
   *     this flag is false.
   * @param filter Only include cache entries that are accepted by the filter.
   * @return The list of cache elements. This will be empty if the path is not monitored in a
   *     monitored path. If the path is a file and the file is monitored by the cache, the returned
   *     list will contain just the cache entry for the path.
   */
  List<Directory.Entry<T>> list(
      final Path path, final boolean recursive, final Directory.EntryFilter<? super T> filter);

  /**
   * Lists the cache elements in the particular path without any filtering
   *
   * @param path The path to list. This may be a file in which case the result list contains only
   *     this path or the empty list if the path is not monitored by the cache.
   *     <p>is recursively monitoring the input path, it will not return cache entries for children
   *     if this flag is false.
   * @param maxDepth The maximum depth of children of the parent to traverse in the tree.
   * @return The list of cache elements. This will be empty if the path is not monitored in a
   *     monitored path. If the path is a file and the file is monitored by the cache, the returned
   *     list will contain just the cache entry for the path.
   */
  @SuppressWarnings("unused")
  List<Directory.Entry<T>> list(final Path path, final int maxDepth);

  /**
   * Lists the cache elements in the particular path without any filtering
   *
   * @param path The path to list. This may be a file in which case the result list contains only
   *     this path or the empty list if the path is not monitored by the cache.
   *     <p>is recursively monitoring the input path, it will not return cache entries for children
   *     if this flag is false.
   * @param recursive Toggles whether or not to traverse the children of the path
   * @return The list of cache elements. This will be empty if the path is not monitored in a
   *     monitored path. If the path is a file and the file is monitored by the cache, the returned
   *     list will contain just the cache entry for the path.
   */
  @SuppressWarnings("unused")
  List<Directory.Entry<T>> list(final Path path, final boolean recursive);

  /**
   * Lists the cache elements in the particular path recursively and with no filter.
   *
   * @param path The path to list. This may be a file in which case the result list contains only
   *     this path or the empty list if the path is not monitored by the cache.
   * @return The list of cache elements. This will be empty if the path is not monitored in a
   *     monitored path. If the path is a file and the file is monitored by the cache, the returned
   *     list will contain just the cache entry for the path.
   */
  @SuppressWarnings("unused")
  List<Directory.Entry<T>> list(final Path path);

  /**
   * Register the path for monitoring.
   *
   * @param path The path to monitor
   * @param maxDepth The maximum depth of subdirectories to include
   * @return an instance of {@link com.swoval.functional.Either} that contains a boolean flag
   *     indicating whether registration succeeds. If an IOException is thrown registering the path,
   *     it is returned as a {@link com.swoval.functional.Either.Left}. This method should be
   *     idempotent and returns false if the call was a no-op.
   */
  Either<IOException, Boolean> register(final Path path, final int maxDepth);

  /**
   * Register the path for monitoring.
   *
   * @param path The path to monitor
   * @param recursive Recursively monitor the path if true
   * @return an instance of {@link com.swoval.functional.Either} that contains a boolean flag
   *     indicating whether registration succeeds. If an IOException is thrown registering the path,
   *     it is returned as a {@link com.swoval.functional.Either.Left}. This method should be
   *     idempotent and returns false if the call was a no-op.
   */
  Either<IOException, Boolean> register(final Path path, final boolean recursive);

  /**
   * Register the path for monitoring recursively.
   *
   * @param path The path to monitor
   * @return an instance of {@link com.swoval.functional.Either} that contains a boolean flag
   *     indicating whether registration succeeds. If an IOException is thrown registering the path,
   *     it is returned as a {@link com.swoval.functional.Either.Left}. This method should be
   *     idempotent and returns false if the call was a no-op.
   */
  Either<IOException, Boolean> register(final Path path);

  /**
   * Unregister a path from the cache. This removes the path from monitoring and from the cache so
   * long as the path isn't covered by another registered path. For example, if the path /foo was
   * previously registered, after removal, no changes to /foo or files in /foo should be detected by
   * the cache. Moreover, calling {@link com.swoval.files.FileCache#list(Path)} for /foo should
   * return an empty list. If, however, we register both /foo recursively and /foo/bar (recursively
   * or not), after unregistering /foo/bar, changes to /foo/bar should continue to be detected and
   * /foo/bar should be included in the list returned by {@link
   * com.swoval.files.FileCache#list(Path)}.
   *
   * @param path The path to unregister
   */
  void unregister(final Path path);
}
