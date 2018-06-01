package com.swoval.files

import com.swoval.files.DirectoryWatcher.DEFAULT_FACTORY
import com.swoval.files.DirectoryWatcher.Event.Overflow
import com.swoval.files.EntryFilters.AllPass
import com.swoval.files.Directory.Converter
import com.swoval.files.Directory.Observer
import com.swoval.files.Directory.OnChange
import com.swoval.files.DirectoryWatcher.Callback
import com.swoval.files.DirectoryWatcher.Factory
import java.io.File
import java.io.FileFilter
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet
import java.util.Iterator
import java.util.List
import java.util.Map
import java.util.Map.Entry
import java.util.Set
import java.util.concurrent.locks.ReentrantLock
import FileCache._
import FileCacheImpl._

object FileCache {

  /**
   * Create a file cache
   *
   * @param converter Converts a path to the cached value type T
   * @tparam T The value type of the cache entries
   * @return A file cache
   */
  def apply[T](converter: Converter[T]): FileCache[T] =
    apply(converter, DEFAULT_FACTORY)

  /**
   * Create a file cache using a specific DirectoryWatcher created by the provided factory
   *
   * @param converter Converts a path to the cached value type T
   * @param factory A factory to create a directory watcher
   * @tparam T The value type of the cache entries
   * @return A file cache
   */
  def apply[T](converter: Converter[T], factory: DirectoryWatcher.Factory): FileCache[T] =
    new FileCacheImpl(converter, factory)

  /**
   * Create a file cache with an Observer of events
   *
   * @param converter Converts a path to the cached value type T
   * @param observer Observer of events for this cache
   * @tparam T The value type of the cache entries
   * @return A file cache
   */
  def apply[T](converter: Converter[T], observer: Observer[T]): FileCache[T] =
    apply(converter, observer, DEFAULT_FACTORY)

  /**
   * Create a file cache with an Observer of events
   *
   * @param converter Converts a path to the cached value type T
   * @param observer Observer of events for this cache
   * @param factory A factory to create a directory watcher
   * @tparam T The value type of the cache entries
   * @return A file cache
   */
  def apply[T](converter: Converter[T],
               observer: Observer[T],
               factory: DirectoryWatcher.Factory): FileCache[T] = {
    val res: FileCache[T] = new FileCacheImpl[T](converter, factory)
    res.addObserver(observer)
    res
  }

}

/**
 * Provides an in memory cache of portions of the file system. Directories are added to the cache
 * using the [[FileCache.register]] method. Once a directory is added the cache,
 * its contents may be retrieved using the [[FileCache#list(Path, boolean,
 * Directory.EntryFilter)]] method. The cache stores the path information in [[Directory.Entry]]
 * instances.
 *
 * <p>A default implementation is provided by [[FileCache#apply(Directory.Converter,
 * Directory.Observer)]]. The user may cache arbitrary information in the cache by customizing the
 * [[Directory.Converter]] that is passed into the factory [[FileCache.apply]].
 *
 * @tparam T The type of data stored in the [[Directory.Entry]] instances for the cache
 */
abstract class FileCache[T] extends AutoCloseable {

  val observers: Observers[T] = new Observers()

  /**
   * Add observer of file events
   *
   * @param observer The new observer
   * @return handle that can be used to remove the callback using [[removeObserver]]
   */
  def addObserver(observer: Observer[T]): Int = observers.addObserver(observer)

  /**
   * Add callback to fire when a file event is detected by the monitor
   *
   * @param onChange The callback to fire on file events
   * @return handle that can be used to remove the callback using [[removeObserver]]
   */
  def addCallback(onChange: OnChange[T]): Int =
    addObserver(new Observer[T]() {
      override def onCreate(newEntry: Directory.Entry[T]): Unit = {
        onChange.apply(newEntry)
      }

      override def onDelete(oldEntry: Directory.Entry[T]): Unit = {
        onChange.apply(oldEntry)
      }

      override def onUpdate(oldEntry: Directory.Entry[T], newEntry: Directory.Entry[T]): Unit = {
        onChange.apply(newEntry)
      }
    })

  /**
   * Stop firing the previously registered callback where {@code handle} is returned by [[addObserver]]
   *
   * @param handle A handle to the observer added by [[addObserver]]
   */
  def removeObserver(handle: Int): Unit = {
    observers.removeObserver(handle)
  }

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
  def list(path: Path,
           recursive: Boolean,
           filter: Directory.EntryFilter[_ >: T]): List[Directory.Entry[T]]

  /**
   * Lists the cache elements in the particular path without any filtering
   *
   * @param path The path to list. This may be a file in which case the result list contains only
   *     this path or the empty list if the path is not monitored by the cache.
   *     <p>is recursively monitoring the input path, it will not return cache entries for children
   *     if this flag is false.
   * @return The list of cache elements. This will be empty if the path is not monitored in a
   *     monitored path. If the path is a file and the file is monitored by the cache, the returned
   *     list will contain just the cache entry for the path.
   */
  def list(path: Path, recursive: Boolean): List[Directory.Entry[T]] =
    list(path, recursive, AllPass)

  /**
   * Lists the cache elements in the particular path recursively and with no filter.
   *
   * @param path The path to list. This may be a file in which case the result list contains only
   *     this path or the empty list if the path is not monitored by the cache.
   * @return The list of cache elements. This will be empty if the path is not monitored in a
   *     monitored path. If the path is a file and the file is monitored by the cache, the returned
   *     list will contain just the cache entry for the path.
   */
  def list(path: Path): List[Directory.Entry[T]] = list(path, true, AllPass)

  /**
   * Register the directory for monitoring.
   *
   * @param path The path to monitor
   * @param recursive Recursively monitor the path if true
   * @return The registered directory if it hasn't previously been registered, null otherwise
   */
  def register(path: Path, recursive: Boolean): Directory[T]

  /**
   * Register the directory for monitoring recursively.
   *
   * @param path The path to monitor
   * @return The registered directory if it hasn't previously been registered, null otherwise
   */
  def register(path: Path): Directory[T] = register(path, true)

  /**
 Handle all exceptions in close.
   */
  override def close(): Unit = {}

}

private[files] object FileCacheImpl {

  private class Pair[A, B](val first: A, val second: B) {

    override def toString(): String = "Pair(" + first + ", " + second + ")"

  }

}

private[files] class FileCacheImpl[T](private val converter: Converter[T],
                                      factory: DirectoryWatcher.Factory)
    extends FileCache[T] {

  private val directories: Map[Path, Directory[T]] = new HashMap()

  private val lock: ReentrantLock = new ReentrantLock()

  private val callback: DirectoryWatcher.Callback =
    new DirectoryWatcher.Callback() {
      override def apply(event: DirectoryWatcher.Event): Unit = {
        lock.synchronized {
          val path: Path = event.path
          if (event.kind == Overflow) {
            handleOverflow(path)
          } else {
            handleEvent(path, event.path)
          }
        }
      }
    }

  private val watcher: DirectoryWatcher = factory.create(callback)

  /**
 Cleans up the directory watcher and clears the directory cache.
   */
  override def close(): Unit = {
    watcher.close()
    directories.clear()
  }

  override def list(path: Path,
                    recursive: Boolean,
                    filter: Directory.EntryFilter[_ >: T]): List[Directory.Entry[T]] = {
    val pair: Pair[Directory[T], List[Directory.Entry[T]]] =
      listImpl(path, recursive, filter)
    if (pair == null) new ArrayList[Directory.Entry[T]]() else pair.second
  }

  override def register(path: Path, recursive: Boolean): Directory[T] = {
    var result: Directory[T] = null
    if (Files.exists(path)) {
      watcher.register(path)
      directories.synchronized {
        val it: Iterator[Directory[T]] = directories.values.iterator()
        var existing: Directory[T] = null
        while (it.hasNext && existing == null) {
          val dir: Directory[T] = it.next()
          if (path.startsWith(dir.path) && dir.recursive()) {
            existing = dir
          }
        }
        if (existing == null) {
          result = Directory.cached(path, converter, recursive)
          directories.put(path, result)
        }
      }
    }
    result
  }

  private def listImpl(
      path: Path,
      recursive: Boolean,
      filter: Directory.EntryFilter[_ >: T]): Pair[Directory[T], List[Directory.Entry[T]]] =
    directories.synchronized {
      if (Files.exists(path)) {
        var foundDir: Directory[T] = null
        val it: Iterator[Directory[T]] = directories.values.iterator()
        while (it.hasNext) {
          val dir: Directory[T] = it.next()
          if (path.startsWith(dir.path) &&
              (foundDir == null || dir.path.startsWith(foundDir.path))) {
            foundDir = dir
          }
        }
        if (foundDir != null) {
          new Pair(foundDir, foundDir.list(path, recursive, filter))
        } else {
          null
        }
      } else {
        null
      }
    }

  private def diff(left: Directory[T], right: Directory[T]): Boolean = {
    val oldEntries: List[Directory.Entry[T]] =
      left.list(left.recursive(), AllPass)
    val oldPaths: Set[Path] = new HashSet[Path]()
    val oldEntryIterator: Iterator[Directory.Entry[T]] = oldEntries.iterator()
    while (oldEntryIterator.hasNext) oldPaths.add(oldEntryIterator.next().path)
    val newEntries: List[Directory.Entry[T]] =
      right.list(left.recursive(), AllPass)
    val newPaths: Set[Path] = new HashSet[Path]()
    val newEntryIterator: Iterator[Directory.Entry[T]] = newEntries.iterator()
    while (newEntryIterator.hasNext) newPaths.add(newEntryIterator.next().path)
    var result: Boolean = oldPaths.size != newPaths.size
    val oldIterator: Iterator[Path] = oldPaths.iterator()
    while (oldIterator.hasNext && !result) if (newPaths.add(oldIterator.next()))
      result = true
    val newIterator: Iterator[Path] = newPaths.iterator()
    while (newIterator.hasNext && !result) if (oldPaths.add(newIterator.next()))
      result = true
    result
  }

  private def handleOverflow(path: Path): Boolean = directories.synchronized {
    val directoryIterator: Iterator[Directory[T]] =
      directories.values.iterator()
    val toReplace: List[Directory[T]] = new ArrayList[Directory[T]]()
    val creations: List[Directory.Entry[T]] =
      new ArrayList[Directory.Entry[T]]()
    val updates: List[Array[Directory.Entry[T]]] =
      new ArrayList[Array[Directory.Entry[T]]]()
    val deletions: List[Directory.Entry[T]] =
      new ArrayList[Directory.Entry[T]]()
    while (directoryIterator.hasNext) {
      val currentDir: Directory[T] = directoryIterator.next()
      if (path.startsWith(currentDir.path)) {
        var oldDir: Directory[T] = currentDir
        var newDir: Directory[T] =
          Directory.cached(oldDir.path, converter, oldDir.recursive())
        while (diff(oldDir, newDir)) {
          oldDir = newDir
          newDir = Directory.cached(oldDir.path, converter, oldDir.recursive())
        }
        val oldEntries: Map[Path, Directory.Entry[T]] =
          new HashMap[Path, Directory.Entry[T]]()
        val newEntries: Map[Path, Directory.Entry[T]] =
          new HashMap[Path, Directory.Entry[T]]()
        val oldEntryIterator: Iterator[Directory.Entry[T]] =
          currentDir.list(currentDir.recursive(), AllPass).iterator()
        while (oldEntryIterator.hasNext) {
          val entry: Directory.Entry[T] = oldEntryIterator.next()
          oldEntries.put(entry.path, entry)
        }
        val newEntryIterator: Iterator[Directory.Entry[T]] =
          newDir.list(currentDir.recursive(), AllPass).iterator()
        while (newEntryIterator.hasNext) {
          val entry: Directory.Entry[T] = newEntryIterator.next()
          newEntries.put(entry.path, entry)
        }
        val oldIterator: Iterator[Entry[Path, Directory.Entry[T]]] =
          oldEntries.entrySet().iterator()
        while (oldIterator.hasNext) {
          val mapEntry: Entry[Path, Directory.Entry[T]] = oldIterator.next()
          if (!newEntries.containsKey(mapEntry.getKey)) {
            deletions.add(mapEntry.getValue)
          }
        }
        val newIterator: Iterator[Entry[Path, Directory.Entry[T]]] =
          newEntries.entrySet().iterator()
        while (newIterator.hasNext) {
          val mapEntry: Entry[Path, Directory.Entry[T]] = newIterator.next()
          if (!oldEntries.containsKey(mapEntry.getKey)) {
            creations.add(mapEntry.getValue)
          } else {
            val oldEntry: Directory.Entry[T] = oldEntries.get(mapEntry.getKey)
            if (oldEntry != mapEntry.getValue) {
              updates.add(Array(oldEntry, mapEntry.getValue))
            }
          }
        }
        toReplace.add(newDir)
      }
    }
    val replacements: Iterator[Directory[T]] = toReplace.iterator()
    while (replacements.hasNext) {
      val replacement: Directory[T] = replacements.next()
      directories.put(replacement.path, replacement)
    }
    val creationIterator: Iterator[Directory.Entry[T]] = creations.iterator()
    while (creationIterator.hasNext) observers.onCreate(creationIterator.next())
    val deletionIterator: Iterator[Directory.Entry[T]] = deletions.iterator()
    while (deletionIterator.hasNext) observers.onDelete(deletionIterator.next())
    val updateIterator: Iterator[Array[Directory.Entry[T]]] =
      updates.iterator()
    while (updateIterator.hasNext) {
      val update: Array[Directory.Entry[T]] = updateIterator.next()
      observers.onUpdate(update(0), update(1))
    }
    creations.isEmpty && deletions.isEmpty && updates.isEmpty
  }

  private def handleEvent(path: Path, eventPath: Path): Unit = {
    if (Files.exists(path)) {
      val pair: Pair[Directory[T], List[Directory.Entry[T]]] =
        listImpl(path, false, new Directory.EntryFilter[T]() {
          override def accept(path: Directory.Entry[_ <: T]): Boolean =
            eventPath == path.path
        })
      if (pair != null) {
        val dir: Directory[T] = pair.first
        val paths: List[Directory.Entry[T]] = pair.second
        if (!paths.isEmpty || path != dir.path) {
          val toUpdate: Path = if (paths.isEmpty) path else paths.get(0).path
          val it: Iterator[Array[Directory.Entry[T]]] =
            dir.addUpdate(toUpdate, !Files.isDirectory(path)).iterator()
          while (it.hasNext) {
            val entry: Array[Directory.Entry[T]] = it.next()
            if (entry.length == 2) {
              observers.onUpdate(entry(0), entry(1))
            } else {
              observers.onCreate(entry(0))
            }
          }
        }
      }
    } else {
      directories.synchronized {
        val it: Iterator[Directory[T]] = directories.values.iterator()
        while (it.hasNext) {
          val dir: Directory[T] = it.next()
          if (path.startsWith(dir.path)) {
            val removeIterator: Iterator[Directory.Entry[T]] =
              dir.remove(path).iterator()
            while (removeIterator.hasNext) observers.onDelete(removeIterator.next())
          }
        }
      }
    }
  }

}
