// Do not edit this file manually. It is autogenerated.

package com.swoval.files

import com.swoval.files.FileTreeDataViews.CacheObserver
import com.swoval.files.FileTreeDataViews.Converter
import com.swoval.files.FileTreeDataViews.Entry
import com.swoval.functional.Filter
import com.swoval.functional.Filters
import java.io.IOException
import java.nio.file.Path
import java.util.ArrayList
import java.util.Arrays
import java.util.Iterator
import java.util.List

object FileTreeViews {

  val listers: Array[DirectoryLister] = DirectoryListers.init()

  val nioLister: DirectoryLister = new NioDirectoryLister()

  val defaultLister: DirectoryLister =
    if (listers(1) != null) listers(1)
    else if (listers(0) != null) listers(0)
    else nioLister

  private val nioDirectoryLister: DirectoryLister = nioLister

  private val nativeDirectoryLister: DirectoryLister = listers(0)

  private val defaultDirectoryLister: DirectoryLister = defaultLister

  private val defaultFileTreeView: FileTreeView =
    new SimpleFileTreeView(defaultLister, false)

  private val PATH_CONVERTER: Converter[Path] = new Converter[Path]() {
    override def apply(typedPath: TypedPath): Path = typedPath.getPath
  }

  /**
   * Make a new [[DirectoryView]] that caches the file tree but has no data value associated
   * with each value.
   *
   * @param path the path to monitor
   * @param depth sets how the limit for how deep to traverse the children of this directory
   * @param followLinks sets whether or not to treat symbolic links whose targets as directories or
   *     files
   * @return a directory whose entries just contain the path itself.
   */
  def cached(path: Path, depth: Int, followLinks: Boolean): DirectoryView =
    new CachedDirectoryImpl(
      TypedPaths.get(path),
      PATH_CONVERTER,
      depth,
      Filters.AllPass,
      followLinks
    ).init()

  /**
   * Returns an instance of [[FileTreeView]] that uses only apis available in java.nio.file.
   * This may be used on platforms for which there is no native implementation of [[FileTreeView]].
   *
   * @param followLinks toggles whether or not to follow the targets of symbolic links to
   *     directories.
   * @return an instance of [[FileTreeView]].
   */
  def getNio(followLinks: Boolean): FileTreeView =
    new SimpleFileTreeView(nioDirectoryLister, followLinks)

  /**
   * Returns an instance of [[FileTreeView]] that uses native jni functions to improve
   * performance compared to the [[FileTreeView]] returned by [[FileTreeViews.getNio]].
   *
   * @param followLinks toggles whether or not to follow the targets of symbolic links to
   *     directories.
   * @return an instance of [[FileTreeView]].
   */
  def getNative(followLinks: Boolean): FileTreeView =
    new SimpleFileTreeView(nativeDirectoryLister, followLinks)

  /**
   * Returns the default [[FileTreeView]] for the runtime platform. If a native implementation
   * is present, it will be used. Otherwise, it will fall back to the java.nio.file based
   * implementation.
   *
   * @param followLinks toggles whether or not to follow the targets of symbolic links to
   *     directories.
   * @return an instance of [[FileTreeView]].
   */
  def getDefault(followLinks: Boolean): FileTreeView =
    new SimpleFileTreeView(defaultDirectoryLister, followLinks, false)

  /**
   * Returns the default [[FileTreeView]] for the runtime platform. If a native implementation
   * is present, it will be used. Otherwise, it will fall back to the java.nio.file based
   * implementation.
   *
   * @param followLinks toggles whether or not to follow the targets of symbolic links to
   *     directories.
   * @param ignoreExceptions toggles whether or not to ignore IOExceptions thrown while listing the
   *     directory. If true, some files that are found may be silently dropped if accessing them
   *     caused an exception.
   * @return an instance of [[FileTreeView]].
   */
  def getDefault(followLinks: Boolean, ignoreExceptions: Boolean): FileTreeView =
    new SimpleFileTreeView(defaultDirectoryLister, followLinks, ignoreExceptions)

  /**
   * List the contents of a path.
   *
   * @param path the path to list. If the path is a directory, return the children of this directory
   *     up to the maxDepth. If the path is a regular file and the maxDepth is <code>-1</code>, the
   *     path itself is returned. Otherwise an empty list is returned.
   * @param maxDepth the maximum depth of children to include in the results
   * @param filter only include paths accepted by this filter
   * @return a [[java.util.List]] of [[TypedPath]]
   */
  def list(path: Path, maxDepth: Int, filter: Filter[_ >: TypedPath]): List[TypedPath] =
    defaultFileTreeView.list(path, maxDepth, filter)

  /**
   * Generic Observer for an [[Observable]].
   *
   * @tparam T the type under observation
   */
  trait Observer[T] {

    /**
     * Fired if the underlying [[Observable]] encounters an error
     *
     * @param t the error
     */
    def onError(t: Throwable): Unit

    /**
     * Callback that is invoked whenever a change is detected by the [[Observable]].
     *
     * @param t the changed instance
     */
    def onNext(t: T): Unit

  }

  trait Observable[T] {

    /**
     * Add an observer of events.
     *
     * @param observer the observer to add
     * @return the handle to the observer.
     */
    def addObserver(observer: Observer[_ >: T]): Int

    /**
     * Remove an observer.
     *
     * @param handle the handle that was returned by addObserver
     */
    def removeObserver(handle: Int): Unit

  }

  class Updates[T] extends CacheObserver[T] {

    private val creations: List[Entry[T]] = new ArrayList()

    private val deletions: List[Entry[T]] = new ArrayList()

    private val updates: List[Array[Entry[T]]] = new ArrayList()

    def observe(cacheObserver: CacheObserver[T]): Unit = {
      val creationIterator: Iterator[Entry[T]] = creations.iterator()
      while (creationIterator.hasNext) cacheObserver.onCreate(creationIterator.next())
      val updateIterator: Iterator[Array[Entry[T]]] = updates.iterator()
      while (updateIterator.hasNext) {
        val entries: Array[Entry[T]] = updateIterator.next()
        cacheObserver.onUpdate(entries(0), entries(1))
      }
      val deletionIterator: Iterator[Entry[T]] = deletions.iterator()
      while (deletionIterator.hasNext)
        cacheObserver.onDelete(Entries.setExists(deletionIterator.next(), false))
    }

    override def onCreate(newEntry: Entry[T]): Unit = {
      creations.add(newEntry)
    }

    override def onDelete(oldEntry: Entry[T]): Unit = {
      deletions.add(oldEntry)
    }

    override def onUpdate(oldEntry: Entry[T], newEntry: Entry[T]): Unit = {
      updates.add(Array(oldEntry, newEntry))
    }

    override def onError(exception: IOException): Unit = {}

    override def toString(): String = {
      val updateList: List[List[Entry[T]]] = new ArrayList[List[Entry[T]]]()
      val it: Iterator[Array[Entry[T]]] = updates.iterator()
      while (it.hasNext) updateList.add(Arrays.asList(it.next(): _*))
      "Updates(" + ("creations: " + creations) + (", deletions: " + deletions) +
        (", updates: " + updateList + ")")
    }

  }

}
