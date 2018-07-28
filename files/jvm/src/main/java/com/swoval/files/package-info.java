/**
 * Provides classes for monitoring directories for file system updates. The {@link
 * com.swoval.files.PathWatcher} provides a raw api to monitor directories for file events. The
 * {@link com.swoval.files.FileTreeRepository} generates an in memory cache of a set of directories
 * that can be listed. The cache can store arbitrary user data that is returned to the user whenever
 * the cache is listed or fires a callback (see {@link
 * com.swoval.files.FileTreeViews.CacheObserver}).
 *
 * <p>The implementation of all of the classes in this package uses only apis and code constructs
 * that can be translated by <a href="https://github.com/timowest/scalagen">scalagen</a>. This can
 * cause some of the code to look a bit awkward or not be quite idiomatic java.
 */
package com.swoval.files;
