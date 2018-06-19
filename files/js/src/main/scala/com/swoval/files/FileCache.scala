package com.swoval.files

import com.swoval.files.DirectoryWatcher.DEFAULT_FACTORY
import com.swoval.files.DirectoryWatcher.Event.Overflow
import com.swoval.files.EntryFilters.AllPass
import com.swoval.files.Directory.Converter
import com.swoval.files.Directory.Observer
import com.swoval.files.Directory.OnChange
import com.swoval.files.Directory.OnError
import com.swoval.files.DirectoryWatcher.Event
import com.swoval.functional.Consumer
import com.swoval.functional.Either
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.ArrayList
import java.util.Collections
import java.util.Comparator
import java.util.HashMap
import java.util.HashSet
import java.util.Iterator
import java.util.List
import java.util.Map
import java.util.Map.Entry
import java.util.Set
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicBoolean
import Option._
import FileCache._
import FileCacheImpl._

object FileCache {

  /**
   * Create a file cache
   *
   * @param converter Converts a path to the cached value type T
   * @param options Options for the cache.
   * @tparam T The value type of the cache entries
   * @return A file cache
   */
  def apply[T](converter: Converter[T], options: FileCache.Option*): FileCache[T] =
    new FileCacheImpl(converter, DEFAULT_FACTORY, null, options: _*)

  /**
   * Create a file cache using a specific DirectoryWatcher created by the provided factory
   *
   * @param converter Converts a path to the cached value type T
   * @param factory A factory to create a directory watcher
   * @param options Options for the cache
   * @tparam T The value type of the cache entries
   * @return A file cache
   */
  def apply[T](converter: Converter[T],
               factory: DirectoryWatcher.Factory,
               options: FileCache.Option*): FileCache[T] =
    new FileCacheImpl(converter, factory, null, options: _*)

  /**
   * Create a file cache with an Observer of events
   *
   * @param converter Converts a path to the cached value type T
   * @param observer Observer of events for this cache
   * @param options Options for the cache
   * @tparam T The value type of the cache entries
   * @return A file cache
   */
  def apply[T](converter: Converter[T],
               observer: Observer[T],
               options: FileCache.Option*): FileCache[T] = {
    val res: FileCache[T] =
      new FileCacheImpl[T](converter, DEFAULT_FACTORY, null, options: _*)
    res.addObserver(observer)
    res
  }

  /**
   * Create a file cache with an Observer of events
   *
   * @param converter Converts a path to the cached value type T
   * @param factory A factory to create a directory watcher
   * @param observer Observer of events for this cache
   * @param options Options for the cache
   * @tparam T The value type of the cache entries
   * @return A file cache
   */
  def apply[T](converter: Converter[T],
               factory: DirectoryWatcher.Factory,
               observer: Observer[T],
               options: FileCache.Option*): FileCache[T] = {
    val res: FileCache[T] =
      new FileCacheImpl[T](converter, factory, null, options: _*)
    res.addObserver(observer)
    res
  }

  object Option {

    /**
     * When the FileCache encounters a symbolic link with a directory as target, treat the symbolic
     * link like a directory. Note that it is possible to create a loop if two directories mutually
     * link to each other symbolically. When this happens, the FileCache will throw a [[java.nio.file.FileSystemLoopException]] when attempting to register one of these directories
     * or if the link that completes the loop is added to a registered directory.
     */
    val NOFOLLOW_LINKS: FileCache.Option = new Option()

  }

  /**
 Options for the implementation of a [[FileCache]]
   */
  class Option()

}

/**
 * Provides an in memory cache of portions of the file system. Directories are added to the cache
 * using the [[FileCache.register]] method. Once a directory is added the cache,
 * its contents may be retrieved using the [[FileCache#list(Path, boolean,
 * Directory.EntryFilter)]] method. The cache stores the path information in [[Directory.Entry]]
 * instances.
 *
 * <p>A default implementation is provided by [[FileCache.apply]]. The user may cache arbitrary
 * information in the cache by customizing the [[Directory.Converter]] that is passed into the
 * factory [[FileCache.apply]].
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

      override def onError(path: Path, exception: IOException): Unit = {}
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
   * @param maxDepth The maximum depth of children of the parent to traverse in the tree.
   * @param filter Only include cache entries that are accepted by the filter.
   * @return The list of cache elements. This will be empty if the path is not monitored in a
   *     monitored path. If the path is a file and the file is monitored by the cache, the returned
   *     list will contain just the cache entry for the path.
   */
  def list(path: Path,
           maxDepth: Int,
           filter: Directory.EntryFilter[_ >: T]): List[Directory.Entry[T]]

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
           filter: Directory.EntryFilter[_ >: T]): List[Directory.Entry[T]] =
    list(path, if (recursive) java.lang.Integer.MAX_VALUE else 0, filter)

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
  def list(path: Path, maxDepth: Int): List[Directory.Entry[T]] =
    list(path, maxDepth, AllPass)

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
  def list(path: Path): List[Directory.Entry[T]] =
    list(path, java.lang.Integer.MAX_VALUE, AllPass)

  /**
   * Register the directory for monitoring.
   *
   * @param path The path to monitor
   * @param maxDepth The maximum depth of subdirectories to include
   * @return an instance of [[com.swoval.functional.Either]] that contains a boolean flag
   *     indicating whether registration succeeds. If an IOException is thrown registering the
   *     directory, it is returned as a [[com.swoval.functional.Either.Left]]. This method
   *     should be idempotent and returns false if the call was a no-op.
   */
  def register(path: Path, maxDepth: Int): Either[IOException, Boolean]

  /**
   * Register the directory for monitoring.
   *
   * @param path The path to monitor
   * @param recursive Recursively monitor the path if true
   * @return an instance of [[com.swoval.functional.Either]] that contains a boolean flag
   *     indicating whether registration succeeds. If an IOException is thrown registering the
   *     directory, it is returned as a [[com.swoval.functional.Either.Left]]. This method
   *     should be idempotent and returns false if the call was a no-op.
   */
  def register(path: Path, recursive: Boolean): Either[IOException, Boolean] =
    register(path, if (recursive) java.lang.Integer.MAX_VALUE else 0)

  /**
   * Register the directory for monitoring recursively.
   *
   * @param path The path to monitor
   * @return an instance of [[com.swoval.functional.Either]] that contains a boolean flag
   *     indicating whether registration succeeds. If an IOException is thrown registering the
   *     directory, it is returned as a [[com.swoval.functional.Either.Left]]. This method
   *     should be idempotent and returns false if the call was a no-op.
   */
  def register(path: Path): Either[IOException, Boolean] =
    register(path, java.lang.Integer.MAX_VALUE)

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
                                      factory: DirectoryWatcher.Factory,
                                      executor: Executor,
                                      options: FileCache.Option*)
    extends FileCache[T] {

  private val directories: Map[Path, Directory[T]] = new HashMap()

  private val closed: AtomicBoolean = new AtomicBoolean(false)

  private val internalExecutor: Executor =
    if (executor == null)
      Executor.make("com.swoval.files.FileCache-callback-internalExecutor")
    else executor

  private val callbackExecutor: Executor =
    Executor.make("com.swoval.files.FileCache-callback-executor")

  private val symlinkWatcher: SymlinkWatcher =
    if (!ArrayOps.contains(options, FileCache.Option.NOFOLLOW_LINKS))
      new SymlinkWatcher(
        new Consumer[Path]() {
          override def accept(path: Path): Unit = {
            handleEvent(path)
          }
        },
        factory,
        new OnError() {
          override def apply(symlink: Path, exception: IOException): Unit = {
            observers.onError(symlink, exception)
          }
        },
        this.internalExecutor.copy()
      )
    else null

  private def callback(executor: Executor): Consumer[Event] =
    new Consumer[Event]() {
      override def accept(event: DirectoryWatcher.Event): Unit = {
        executor.run(new Runnable() {
          override def run(): Unit = {
            val path: Path = event.path
            if (event.kind == Overflow) {
              handleOverflow(path)
            } else {
              handleEvent(path)
            }
          }
        })
      }
    }

  private val watcher: DirectoryWatcher =
    factory.create(callback(this.internalExecutor.copy()), this.internalExecutor.copy())

  /**
 Cleans up the directory watcher and clears the directory cache.
   */
  override def close(): Unit = {
    if (closed.compareAndSet(false, true)) {
      if (symlinkWatcher != null) symlinkWatcher.close()
      watcher.close()
      val directoryIterator: Iterator[Directory[T]] =
        directories.values.iterator()
      while (directoryIterator.hasNext) directoryIterator.next().close()
      directories.clear()
      internalExecutor.close()
    }
  }

  override def list(path: Path,
                    maxDepth: Int,
                    filter: Directory.EntryFilter[_ >: T]): List[Directory.Entry[T]] = {
    val pair: Pair[Directory[T], List[Directory.Entry[T]]] =
      listImpl(path, maxDepth, filter)
    if (pair == null) new ArrayList[Directory.Entry[T]]() else pair.second
  }

  override def register(path: Path, maxDepth: Int): Either[IOException, Boolean] = {
    var result: Either[IOException, Boolean] = watcher.register(path, maxDepth)
    if (result.isRight) {
      result = internalExecutor
        .block(new Callable[Boolean]() {
          override def call(): Boolean = doReg(path, maxDepth)
        })
        .castLeft(classOf[IOException])
    }
    result
  }

  private def doReg(path: Path, maxDepth: Int): Boolean = {
    var result: Boolean = false
    val dirs: List[Directory[T]] =
      new ArrayList[Directory[T]](directories.values)
    Collections.sort(dirs, new Comparator[Directory[T]]() {
      override def compare(left: Directory[T], right: Directory[T]): Int =
        left.path.compareTo(right.path)
    })
    val it: Iterator[Directory[T]] = dirs.iterator()
    var existing: Directory[T] = null
    while (it.hasNext && existing == null) {
      val dir: Directory[T] = it.next()
      if (path.startsWith(dir.path)) {
        val depth: Int =
          if (path == dir.path) 0
          else (dir.path.relativize(path).getNameCount - 1)
        if (dir.getDepth == java.lang.Integer.MAX_VALUE || maxDepth < dir.getDepth - depth) {
          existing = dir
        } else if (depth <= dir.getDepth) {
          result = true
          dir.close()
          try {
            existing = Directory.cached(dir.path,
                                        converter,
                                        if (maxDepth < java.lang.Integer.MAX_VALUE - depth - 1)
                                          maxDepth + depth + 1
                                        else java.lang.Integer.MAX_VALUE)
            directories.put(dir.path, existing)
          } catch {
            case e: IOException => existing = null

          }
        }
      }
    }
    if (existing == null) {
      val dir: Directory[T] = Directory.cached(path, converter, maxDepth)
      directories.put(path, dir)
      val entryIterator: Iterator[Directory.Entry[T]] =
        dir.list(true, EntryFilters.AllPass).iterator()
      if (symlinkWatcher != null) {
        while (entryIterator.hasNext) {
          val entry: Directory.Entry[T] = entryIterator.next()
          if (entry.isSymbolicLink) {
            symlinkWatcher.addSymlink(entry.path, entry.isDirectory, maxDepth - 1)
          }
        }
      }
      result = true
    }
    result
  }

  private def listImpl(
      path: Path,
      maxDepth: Int,
      filter: Directory.EntryFilter[_ >: T]): Pair[Directory[T], List[Directory.Entry[T]]] = {
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
      new Pair(foundDir, foundDir.list(path, maxDepth, filter))
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

  private def cachedOrNull(path: Path, maxDepth: Int): Directory[T] = {
    var res: Directory[T] = null
    try res = Directory.cached(path, converter, maxDepth)
    catch {
      case e: IOException => {}

    }
    res
  }

  private def handleOverflow(path: Path): Unit = {
    if (!closed.get) {
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
          var newDir: Directory[T] = cachedOrNull(oldDir.path, oldDir.getDepth)
          while (newDir == null || diff(oldDir, newDir)) {
            if (newDir != null) oldDir = newDir
            newDir = cachedOrNull(oldDir.path, oldDir.getDepth)
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
            val oldEntry: Directory.Entry[T] = oldEntries.get(mapEntry.getKey)
            if (oldEntry == null) {
              creations.add(mapEntry.getValue)
            } else if (oldEntry != mapEntry.getValue) {
              updates.add(Array(oldEntry, mapEntry.getValue))
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
      callbackExecutor.run(new Runnable() {
        override def run(): Unit = {
          val creationIterator: Iterator[Directory.Entry[T]] =
            creations.iterator()
          while (creationIterator.hasNext) observers.onCreate(creationIterator.next())
          val deletionIterator: Iterator[Directory.Entry[T]] =
            deletions.iterator()
          while (deletionIterator.hasNext) observers.onDelete(deletionIterator.next())
          val updateIterator: Iterator[Array[Directory.Entry[T]]] =
            updates.iterator()
          while (updateIterator.hasNext) {
            val update: Array[Directory.Entry[T]] = updateIterator.next()
            observers.onUpdate(update(0), update(1))
          }
        }
      })
    }
  }

  private def handleEvent(path: Path): Unit = {
    if (!closed.get) {
      var attrs: BasicFileAttributes = null
      val callbacks: List[Runnable] = new ArrayList[Runnable]()
      try attrs =
        Files.readAttributes(path, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
      catch {
        case e: IOException => {}

      }
      if (attrs != null) {
        val pair: Pair[Directory[T], List[Directory.Entry[T]]] =
          listImpl(path, 0, new Directory.EntryFilter[T]() {
            override def accept(entry: Directory.Entry[_ <: T]): Boolean =
              path == entry.path
          })
        if (pair != null) {
          val dir: Directory[T] = pair.first
          val paths: List[Directory.Entry[T]] = pair.second
          if (!paths.isEmpty || path != dir.path) {
            val toUpdate: Path = if (paths.isEmpty) path else paths.get(0).path
            try {
              if (attrs.isSymbolicLink && symlinkWatcher != null)
                symlinkWatcher.addSymlink(path,
                                          Files.isDirectory(path),
                                          if (dir.getDepth == java.lang.Integer.MAX_VALUE)
                                            java.lang.Integer.MAX_VALUE
                                          else dir.getDepth - 1)
              val updates: Directory.Updates[T] =
                dir.update(toUpdate, Directory.Entry.getKind(toUpdate, attrs))
              updates.observe(callbackObserver(callbacks))
            } catch {
              case e: IOException =>
                callbacks.add(new Runnable() {
                  override def run(): Unit = {
                    observers.onError(path, e)
                  }
                })

            }
          }
        }
      } else {
        val removeIterators: List[Iterator[Directory.Entry[T]]] =
          new ArrayList[Iterator[Directory.Entry[T]]]()
        val directoryIterator: Iterator[Directory[T]] =
          directories.values.iterator()
        while (directoryIterator.hasNext) {
          val dir: Directory[T] = directoryIterator.next()
          if (path.startsWith(dir.path)) {
            removeIterators.add(dir.remove(path).iterator())
          }
        }
        val it: Iterator[Iterator[Directory.Entry[T]]] =
          removeIterators.iterator()
        while (it.hasNext) {
          val removeIterator: Iterator[Directory.Entry[T]] = it.next()
          while (removeIterator.hasNext) {
            val entry: Directory.Entry[T] = removeIterator.next()
            callbacks.add(new Runnable() {
              override def run(): Unit = {
                observers.onDelete(entry)
              }
            })
            if (symlinkWatcher != null) {
              symlinkWatcher.remove(entry.path)
            }
          }
        }
      }
      if (!callbacks.isEmpty) {
        callbackExecutor.run(new Runnable() {
          override def run(): Unit = {
            val runnableIterator: Iterator[Runnable] = callbacks.iterator()
            while (runnableIterator.hasNext) runnableIterator.next().run()
          }
        })
      }
    }
  }

  private def callbackObserver(callbacks: List[Runnable]): Observer[T] =
    new Observer[T]() {
      override def onCreate(newEntry: Directory.Entry[T]): Unit = {
        callbacks.add(new Runnable() {
          override def run(): Unit = {
            observers.onCreate(newEntry)
          }
        })
      }

      override def onDelete(oldEntry: Directory.Entry[T]): Unit = {
        callbacks.add(new Runnable() {
          override def run(): Unit = {
            observers.onDelete(oldEntry)
          }
        })
      }

      override def onUpdate(oldEntry: Directory.Entry[T], newEntry: Directory.Entry[T]): Unit = {
        callbacks.add(new Runnable() {
          override def run(): Unit = {
            observers.onUpdate(oldEntry, newEntry)
          }
        })
      }

      override def onError(path: Path, exception: IOException): Unit = {
        callbacks.add(new Runnable() {
          override def run(): Unit = {
            observers.onError(path, exception)
          }
        })
      }
    }

}
