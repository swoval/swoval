package com.swoval.files

import com.swoval.files.EntryFilters.AllPass
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayList
import java.util.Collection
import java.util.HashMap
import java.util.Iterator
import java.util.List
import java.util.concurrent.atomic.AtomicReference
import Directory._

object Directory {

  private val PATH_CONVERTER: Converter[Path] = new Converter[Path]() {
    override def apply(path: Path): Path = path
  }

  /**
   * Converts a Path into an arbitrary value to be cached
   *
   * @tparam R The generic type generated from the path
   */
  trait Converter[R] {

    def apply(path: Path): R

  }

  private class MapByName[T] extends HashMap[String, T] {

    def getByName(path: Path): T = get(path.getFileName.toString)

    def removeByName(path: Path): T = remove(path.getFileName.toString)

  }

  /**
   * Make a new recursive Directory with no cache value associated with the path
   *
   * @param path The path to monitor
   * @return A directory whose entries just contain the path itself
   */
  def of(path: Path): Directory[Path] = of(path, true)

  /**
   * Make a new Directory with no cache value associated with the path
   *
   * @param path The path to monitor
   * @param depth Sets how the limit for how deep to traverse the children of this directory
   * @return A directory whose entries just contain the path itself
   */
  def of(path: Path, depth: Int): Directory[Path] =
    new Directory(path, PATH_CONVERTER, depth).init()

  /**
   * Make a new Directory with no cache value associated with the path
   *
   * @param path The path to monitor
   * @param recursive Toggles whether or not to cache the children of subdirectories
   * @return A directory whose entries just contain the path itself
   */
  def of(path: Path, recursive: Boolean): Directory[Path] =
    new Directory(path, PATH_CONVERTER, if (recursive) java.lang.Integer.MAX_VALUE else 0).init()

  /**
   * Make a new Directory with a cache entries created by {@code converter}
   *
   * @param path The path to cache
   * @param converter Function to create the cache value for each path
   * @tparam T The cache value type
   * @return A directory with entries of type T
   */
  def cached[T](path: Path, converter: Converter[T]): Directory[T] =
    new Directory(path, converter, java.lang.Integer.MAX_VALUE).init()

  /**
   * Make a new Directory with a cache entries created by {@code converter}
   *
   * @param path The path to cache
   * @param converter Function to create the cache value for each path
   * @param recursive How many levels of children to accept for this directory
   * @tparam T The cache value type
   * @return A directory with entries of type T
   */
  def cached[T](path: Path, converter: Converter[T], recursive: Boolean): Directory[T] =
    new Directory(path, converter, if (recursive) java.lang.Integer.MAX_VALUE else 0).init()

  /**
   * Make a new Directory with a cache entries created by {@code converter}
   *
   * @param path The path to cache
   * @param converter Function to create the cache value for each path
   * @param depth How many levels of children to accept for this directory
   * @tparam T The cache value type
   * @return A directory with entries of type T
   */
  def cached[T](path: Path, converter: Converter[T], depth: Int): Directory[T] =
    new Directory(path, converter, depth).init()

  /**
   * Container class for [[Directory]] entries. Contains both the path to which the path
   * corresponds along with a data value.
   *
   * @tparam T The value wrapped in the Entry
   */
  class Entry[T](val path: Path, val value: T, val isDirectory: Boolean) {

    /**
     * Create a new Entry using the FileSystem to check if the Entry is for a directory
     *
     * @param path The path to which this entry corresponds
     * @param value The {@code path} derived value for this entry
     */
    def this(path: Path, value: T) = this(path, value, Files.isDirectory(path))

    def exists(): Boolean = Files.exists(path)

    /**
     * Resolve a Entry for a relative {@code path}
     *
     * @param other The path to resolve {@code path} against
     * @return A Entry where the {@code path</code> has been resolved against <code>other}
     */
    def resolvedFrom(other: Path): Entry[T] =
      new Entry(other.resolve(path), this.value, this.isDirectory)

    /**
     * Resolve a Entry for a relative {@code path</code> where <code>isDirectory} is known in
     * advance
     *
     * @param other The path to resolve {@code path} against
     * @param isDirectory Indicates whether the path is a directory
     * @return A Entry where the {@code path</code> has been resolved against <code>other}
     */
    def resolvedFrom(other: Path, isDirectory: Boolean): Entry[T] =
      new Entry(other.resolve(path), this.value, isDirectory)

    override def equals(other: Any): Boolean = other match {
      case other: Directory.Entry[_] => {
        val that: Entry[_] = other
        this.path == that.path && this.value == that.value
      }
      case _ => false

    }

    override def hashCode(): Int = path.hashCode ^ value.hashCode

    override def toString(): String = "Entry(" + path + ", " + value + ")"

  }

  /**
   * Filter [[Directory.Entry]] elements
   *
   * @tparam T The data value type for the [[Directory.Entry]]
   */
  trait EntryFilter[T] {

    def accept(entry: Entry[_ <: T]): Boolean

  }

  /**
   * Callback to fire when a file in a monitored directory is created or deleted
   *
   * @tparam T The cached value associated with the path
   */
  trait OnChange[T] {

    def apply(entry: Entry[T]): Unit

  }

  /**
   * Callback to fire when a file in a monitor is updated
   *
   * @tparam T The cached value associated with the path
   */
  trait OnUpdate[T] {

    def apply(oldEntry: Entry[T], newEntry: Entry[T]): Unit

  }

  /**
   * Provides callbacks to run when different types of file events are detected by the cache
   *
   * @tparam T The type for the [[Directory.Entry]] data
   */
  trait Observer[T] {

    def onCreate(newEntry: Entry[T]): Unit

    def onDelete(oldEntry: Entry[T]): Unit

    def onUpdate(oldEntry: Entry[T], newEntry: Entry[T]): Unit

  }

}

/**
 * Provides a mutable in-memory cache of files and subdirectories with basic CRUD functionality. The
 * Directory can be fully recursive as the subdirectories are themselves stored as recursive (when
 * the Directory is initialized without the recursive toggle, the subdirectories are stored as
 * [[Directory.Entry]] instances.The primary use case is the implementation of [[FileCache]]. Directly handling Directory instances is discouraged because it is inherently mutable
 * so it's better to let the FileCache manage it and query the cache rather than Directory directly.
 *
 * @tparam T The cache value type
 */
class Directory[T] private (val path: Path, private val converter: Converter[T], d: Int)
    extends AutoCloseable {

  private val depth: Int = if (d > 0) d else 0

  private val _cacheEntry: AtomicReference[Entry[T]] = new AtomicReference(
    new Entry(path, converter.apply(path)))

  private val lock: AnyRef = new AnyRef()

  private val subdirectories: MapByName[Directory[T]] = new MapByName()

  private val files: MapByName[Entry[T]] = new MapByName()

  override def close(): Unit = {
    this.lock.synchronized {
      val it: Iterator[Directory[T]] = subdirectories.values.iterator()
      while (it.hasNext) it.next().close()
      subdirectories.clear()
      files.clear()
    }
  }

  /**
   * The cache entry for the underlying path of this directory
   *
   * @return The Entry for the directory itself
   */
  def entry(): Entry[T] = _cacheEntry.get

  /**
   * List all of the files for the {@code path}
   *
   * @param recursive Toggles whether or not to include children of subdirectories in the results
   * @param filter Include only entries accepted by the filter
   * @return a List of Entry instances accepted by the filter
   */
  def list(recursive: Boolean, filter: EntryFilter[_ >: T]): List[Entry[T]] = {
    val result: List[Entry[T]] = new ArrayList[Entry[T]]()
    listImpl(recursive, filter, result)
    result
  }

  /**
   * List all of the files for the {@code path</code> that are accepted by the <code>filter}.
   *
   * @param path The path to list. If this is a file, returns a list containing the Entry for the
   *     file or an empty list if the file is not monitored by the path.
   * @param recursive Toggles whether or not to include children of subdirectories in the results
   * @param filter Include only paths accepted by this
   * @return a List of Entry instances accepted by the filter. The list will be empty if the path is
   *     not a subdirectory of this Directory or if it is a subdirectory, but the Directory was
   *     created without the recursive flag.
   */
  def list(path: Path, recursive: Boolean, filter: EntryFilter[_ >: T]): List[Entry[T]] = {
    val findResult: FindResult = find(path)
    if (findResult != null) {
      val dir: Directory[T] = findResult.directory
      if (dir != null) {
        dir.list(recursive, filter)
      } else {
        val entry: Entry[T] = findResult.entry
        val result: List[Entry[T]] = new ArrayList[Entry[T]]()
        if (entry != null && filter.accept(entry)) result.add(entry)
        result
      }
    } else {
      new ArrayList()
    }
  }

  /**
   * Update the Directory entry for a particular path.
   *
   * @param path The path to addUpdate
   * @param isFile Indicates whether {@code path} is a regular file
   * @return A list of updates for the path. When the path is new, the updates have the
   *     oldCachedPath field set to null and will contain all of the children of the new path when
   *     it is a directory. For an existing path, the List contains a single Update that contains
   *     the previous and new [[Directory.Entry]]
   */
  def addUpdate(path: Path, isFile: Boolean): List[Array[Entry[T]]] =
    if (!path.isAbsolute) updateImpl(FileOps.parts(path), isFile)
    else if ((path.startsWith(this.path)))
      updateImpl(FileOps.parts(this.path.relativize(path)), isFile)
    else new ArrayList[Array[Entry[T]]]()

  /**
   * Remove a path from the directory
   *
   * @param path The path to remove
   * @return List containing the Entry instances for the removed path. Also contains the cache
   *     entries for any children of the path when the path is a non-empty directory
   */
  def remove(path: Path): List[Entry[T]] =
    if (path.isAbsolute && path.startsWith(this.path)) {
      removeImpl(FileOps.parts(this.path.relativize(path)))
    } else {
      new ArrayList()
    }

  def recursive(): Boolean = depth == java.lang.Integer.MAX_VALUE

  override def toString(): String =
    "Directory(" + path + ", depth = " + depth + ")"

  private def subdirectoryDepth(): Int =
    if (depth == java.lang.Integer.MAX_VALUE) depth
    else if (depth > 0) depth - 1
    else 0

  private def addDirectory(currentDir: Directory[T],
                           path: Path,
                           updates: List[Array[Entry[T]]]): Unit = {
    val dir: Directory[T] = cached(path, converter, subdirectoryDepth())
    currentDir.subdirectories.put(path.getFileName.toString, dir)
    addUpdate(updates, dir.entry())
    val it: Iterator[Entry[T]] = dir.list(true, AllPass).iterator()
    while (it.hasNext) addUpdate(updates, it.next())
  }

  private def addUpdate(list: List[Array[Entry[T]]],
                        oldEntry: Entry[T],
                        newEntry: Entry[T]): Unit = {
    list.add(if (oldEntry == null) Array(newEntry) else Array(oldEntry, newEntry))
  }

  private def addUpdate(list: List[Array[Entry[T]]], entry: Entry[T]): Unit = {
    addUpdate(list, null, entry)
  }

  private def updateImpl(parts: List[Path], isFile: Boolean): List[Array[Entry[T]]] = {
    val it: Iterator[Path] = parts.iterator()
    var currentDir: Directory[T] = this
    val result: List[Array[Entry[T]]] = new ArrayList[Array[Entry[T]]]()
    while (it.hasNext && currentDir != null) {
      val p: Path = it.next()
      if (p.toString.isEmpty) result
      val resolved: Path = currentDir.path.resolve(p)
      if (!it.hasNext) {
// We will always return from this block
        currentDir.lock.synchronized {
          if (isFile || currentDir.depth == 0) {
            val oldEntry: Entry[T] = currentDir.files.getByName(p)
            val newEntry: Entry[T] =
              new Entry[T](p, converter.apply(resolved), false)
            currentDir.files.put(p.toString, newEntry)
            val oldResolvedEntry: Entry[T] =
              if (oldEntry == null) null
              else oldEntry.resolvedFrom(currentDir.path)
            addUpdate(result, oldResolvedEntry, newEntry.resolvedFrom(currentDir.path))
            result
          } else {
            val dir: Directory[T] = currentDir.subdirectories.getByName(p)
            if (dir == null) {
              addDirectory(currentDir, resolved, result)
              result
            } else {
              val oldEntry: Entry[T] = dir.entry()
              dir._cacheEntry.set(new Entry(dir.path, converter.apply(dir.path), true))
              addUpdate(result, oldEntry.resolvedFrom(currentDir.path), dir.entry())
              result
            }
          }
        }
      } else {
        currentDir.lock.synchronized {
          val dir: Directory[T] = currentDir.subdirectories.getByName(p)
          if (dir == null && currentDir.depth > 0) {
            addDirectory(currentDir, currentDir.path.resolve(p), result)
          }
          currentDir = dir
        }
      }
    }
    result
  }

  private def findImpl(parts: List[Path]): FindResult = {
    val it: Iterator[Path] = parts.iterator()
    var currentDir: Directory[T] = this
    var result: FindResult = null
    while (it.hasNext && currentDir != null && result == null) {
      val p: Path = it.next()
      if (!it.hasNext) {
        currentDir.lock.synchronized {
          val subdir: Directory[T] = currentDir.subdirectories.getByName(p)
          if (subdir != null) {
            result = right(subdir)
          } else {
            val file: Entry[T] = currentDir.files.getByName(p)
            if (file != null)
              result = left(file.resolvedFrom(currentDir.path, false))
          }
        }
      } else {
        currentDir.lock.synchronized {
          currentDir = currentDir.subdirectories.getByName(p)
        }
      }
    }
    result
  }

  private def find(path: Path): FindResult =
    if (path == this.path) {
      right(this)
    } else if (!path.isAbsolute) {
      findImpl(FileOps.parts(path))
    } else if (path.startsWith(this.path)) {
      findImpl(FileOps.parts(this.path.relativize(path)))
    } else {
      null
    }

  private def listImpl(recursive: Boolean,
                       filter: EntryFilter[_ >: T],
                       result: List[Entry[T]]): Unit = {
    var files: Collection[Entry[T]] = null
    var subdirectories: Collection[Directory[T]] = null
    this.lock.synchronized {
      files = this.files.values
      subdirectories = this.subdirectories.values
    }
    val filesIterator: Iterator[Entry[T]] = files.iterator()
    while (filesIterator.hasNext) {
      val resolved: Entry[T] =
        filesIterator.next().resolvedFrom(this.path, false)
      if (filter.accept(resolved)) result.add(resolved)
    }
    val subdirIterator: Iterator[Directory[T]] = subdirectories.iterator()
    while (subdirIterator.hasNext) {
      val subdir: Directory[T] = subdirIterator.next()
      val resolved: Entry[T] = subdir.entry().resolvedFrom(this.path, true)
      if (filter.accept(resolved)) result.add(resolved)
      if (recursive) subdir.listImpl(true, filter, result)
    }
  }

  private def removeImpl(parts: List[Path]): List[Entry[T]] = {
    val result: List[Entry[T]] = new ArrayList[Entry[T]]()
    val it: Iterator[Path] = parts.iterator()
    var currentDir: Directory[T] = this
    while (it.hasNext && currentDir != null) {
      val p: Path = it.next()
      if (!it.hasNext) {
        currentDir.lock.synchronized {
          val file: Entry[T] = currentDir.files.removeByName(p)
          if (file != null) {
            result.add(file.resolvedFrom(currentDir.path, true))
          } else {
            val dir: Directory[T] = currentDir.subdirectories.removeByName(p)
            if (dir != null) {
              result.addAll(dir.list(true, AllPass))
              result.add(dir.entry())
            }
          }
        }
      } else {
        currentDir.lock.synchronized {
          currentDir = currentDir.subdirectories.getByName(p)
        }
      }
    }
    result
  }

  private def init(): Directory[T] = {
    if (Files.exists(path)) {
      lock.synchronized {
        val it: Iterator[File] = FileOps.list(path, false).iterator()
        while (it.hasNext) {
          val file: File = it.next()
          val p: Path = file.toPath()
          val key: Path = path.relativize(p).getFileName
          if (file.isDirectory) {
            if (depth > 0) {
              subdirectories.put(key.toString, cached(p, converter, subdirectoryDepth()))
            } else {
              files.put(key.toString, new Entry(key, converter.apply(p), true))
            }
          } else {
            files.put(key.toString, new Entry(key, converter.apply(p), false))
          }
        }
      }
    }
    this
  }

  private class FindResult(val entry: Entry[T], val directory: Directory[T])

  private def left(entry: Entry[T]): FindResult = new FindResult(entry, null)

  private def right(directory: Directory[T]): FindResult =
    new FindResult(null, directory)

}
