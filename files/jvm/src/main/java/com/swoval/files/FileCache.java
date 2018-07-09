package com.swoval.files;

import com.swoval.files.Directory.EntryFilter;
import com.swoval.files.Directory.Observer;
import com.swoval.files.Directory.OnChange;
import com.swoval.functional.Either;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Provides an in memory cache of portions of the file system. Directories are added to the cache
 * using the {@link com.swoval.files.FileCache#register} method. Once a Path is added the cache, its
 * contents may be retrieved using the {@link com.swoval.files.FileCache#list} method. The cache
 * stores the path information in {@link com.swoval.files.Directory.Entry} instances.
 *
 * <p>A default implementation is provided by {@link com.swoval.files.FileCaches#get}. The user may
 * cache arbitrary information in the cache by customizing the {@link Directory.Converter} that is
 * passed into the factory {@link FileCaches#get}.
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
 * @param <T> the type of data stored in the {@link Directory.Entry} instances for the cache
 */
public interface FileCache<T> extends DataRepository<T>, PathWatcher, AutoCloseable {

  /**
   * Add observer of file events.
   *
   * @param observer The new observer
   * @return handle that can be used to remove the callback using {@link #removeObserver(int)}
   */
  int addObserver(final Observer<T> observer);

  /**
   * Add callback to fire when a file event is detected by the monitor.
   *
   * @param onChange The callback to fire on file events
   * @return handle that can be used to remove the callback using {@link #removeObserver(int)}
   */
  int addCallback(final OnChange<T> onChange);

  /**
   * Stop firing the previously registered callback where {@code handle} is returned by {@link
   * #addObserver(Directory.Observer)}.
   *
   * @param handle A handle to the observer added by {@link #addObserver(Directory.Observer)}
   */
  void removeObserver(final int handle);

  /**
   * Unregister a path from the cache. This removes the path from monitoring and from the cache so
   * long as the path isn't covered by another registered path. For example, if the path /foo was
   * previously registered, after removal, no changes to /foo or files in /foo should be detected by
   * the cache. Moreover, calling {@link com.swoval.files.FileCache#list} for /foo should return an
   * empty list. If, however, we register both /foo recursively and /foo/bar (recursively or not),
   * after unregistering /foo/bar, changes to /foo/bar should continue to be detected and /foo/bar
   * should be included in the list returned by {@link com.swoval.files.FileCache#list}.
   *
   * @param path the path to unregister
   */
  void unregister(final Path path);
}
