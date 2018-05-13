package com.swoval.files

import com.swoval.files.EntryFilters.AllPass
import com.swoval.files.Directory.Converter
import com.swoval.files.Directory.Entry
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayList
import java.util.HashMap
import java.util.Iterator
import java.util.List
import java.util.Map
import FileCache._
import FileCacheImpl._

object FileCache {

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

  /**
   * Create a file cache
   *
   * @param converter Converts a path to the cached value type T
   * @tparam T The value type of the cache entries
   * @return A file cache
   */
  def apply[T](converter: Converter[T]): FileCache[T] =
    new FileCacheImpl(converter)

  /**
   * Create a file cache with an Observer of events
   *
   * @param converter Converts a path to the cached value type T
   * @param observer Observer of events for this cache
   * @tparam T The value type of the cache entries
   * @return A file cache
   */
  def apply[T](converter: Converter[T], observer: Observer[T]): FileCache[T] = {
    val res: FileCache[T] = new FileCacheImpl[T](converter)
    res.addObserver(observer)
    res
  }

}

/**
 * Provides an in memory cache of portions of the file system. Directories are added to the cache
 * using the [[FileCache.register]] method. Once a directory is added the cache,
 * its contents may be retrieved using the [[FileCache.list]]
 * method. The cache stores the path information in [[Directory.Entry]] instances.
 *
 * <p>A default implementation is provided by [[FileCache#apply(Directory.Converter,
 * Observer)]]. The user may cache arbitrary information in the cache by customizing the [[Directory.Converter]] that is passed into the factory [[FileCache#apply(Directory.Converter,
 * FileCache.Observer)]].
 *
 * @tparam T The type of data stored in the [[Directory.Entry]] instances for the cache
 */
abstract class FileCache[T] extends AutoCloseable {

  protected val observers: Observers[T] = new Observers()

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
      override def onCreate(newEntry: Entry[T]): Unit = {
        onChange.apply(newEntry)
      }

      override def onDelete(oldEntry: Entry[T]): Unit = {
        onChange.apply(oldEntry)
      }

      override def onUpdate(oldEntry: Entry[T], newEntry: Entry[T]): Unit = {
        onChange.apply(newEntry)
      }
    })

  /**
   * Stop firing the previously registered callback where <code>handle</code> is returned by [[addObserver]]
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
  def list(path: Path, recursive: Boolean, filter: EntryFilter[_ >: T]): List[Entry[T]]

  /**
   * Lists the cache elements in the particular path without any filtering
   *
   * @param path The path to list. This may be a file in which case the result list contains only
   *     this path or the empty list if the path is not monitored by the cache.
   * @param recursive Toggles whether or not to include paths in subdirectories. Even when the cache
   *     is recursively monitoring the input path, it will not return cache entries for children if
   *     this flag is false.
   * @return The list of cache elements. This will be empty if the path is not monitored in a
   *     monitored path. If the path is a file and the file is monitored by the cache, the returned
   *     list will contain just the cache entry for the path.
   */
  def list(path: Path, recursive: Boolean): List[Entry[T]] =
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
  def list(path: Path): List[Entry[T]] = list(path, true, AllPass)

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

private[files] class FileCacheImpl[T](private val converter: Converter[T]) extends FileCache[T] {

  private val directories: Map[Path, Directory[T]] = new HashMap()

  private val callback: DirectoryWatcher.Callback =
    new DirectoryWatcher.Callback() {
      override def apply(event: DirectoryWatcher.Event): Unit = {
        val path: Path = event.path
        if (Files.exists(path)) {
          val pair: Pair[Directory[T], List[Entry[T]]] =
            listImpl(path, false, new EntryFilter[T]() {
              override def accept(path: Entry[_ <: T]): Boolean =
                event.path == path.path
            })
          if (pair != null) {
            val dir: Directory[T] = pair.first
            val paths: List[Entry[T]] = pair.second
            val toUpdate: Path =
              if (paths.isEmpty) path
              else if ((path != dir.path)) paths.get(0).path
              else null
            if (toUpdate != null) {
              val it: Iterator[Array[Entry[T]]] =
                dir.addUpdate(toUpdate, !Files.isDirectory(path)).iterator()
              while (it.hasNext) {
                val entry: Array[Entry[T]] = it.next()
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
                val removeIterator: Iterator[Entry[T]] =
                  dir.remove(path).iterator()
                while (removeIterator.hasNext) observers.onDelete(removeIterator.next())
              }
            }
          }
        }
      }
    }

  private val watcher: DirectoryWatcher =
    DirectoryWatcher.defaultWatcher(callback)

  private def listImpl(path: Path,
                       recursive: Boolean,
                       filter: EntryFilter[_ >: T]): Pair[Directory[T], List[Entry[T]]] =
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
          new Pair[Directory[T], List[Entry[T]]](foundDir, foundDir.list(path, recursive, filter))
        } else {
          null
        }
      } else {
        null
      }
    }

  /**
 Cleans up the directory watcher and clears the directory cache.
   */
  override def close(): Unit = {
    watcher.close()
    directories.clear()
  }

  override def list(path: Path, recursive: Boolean, filter: EntryFilter[_ >: T]): List[Entry[T]] = {
    val pair: Pair[Directory[T], List[Entry[T]]] =
      listImpl(path, recursive, filter)
    if (pair == null) new ArrayList[Entry[T]]() else pair.second
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
          if (path.startsWith(dir.path) && dir.recursive) {
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

}
