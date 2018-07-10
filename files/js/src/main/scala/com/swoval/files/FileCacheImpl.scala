// Do not edit this file manually. It is autogenerated.

package com.swoval.files

import java.io.IOException
import java.nio.file.{ NoSuchFileException, NotDirectoryException, Path }
import java.util.{ ArrayList, Collections, Comparator, HashMap, HashSet, Iterator, List, Map, Set }
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicBoolean

import com.swoval.files.CachedDirectories.OnError
import com.swoval.files.DataViews.{ Converter, Entry }
import com.swoval.files.FileTreeViews.{ CacheObserver, Observer, Updates }
import com.swoval.files.PathWatchers.Event.Kind
import com.swoval.files.PathWatchers.Event.Kind.{ Create, Delete, Error, Modify, Overflow }
import com.swoval.files.PathWatchers.{ Event, Factory }
import com.swoval.functional.{ Consumer, Either, Filter }
import com.swoval.functional.Filters.AllPass
import com.swoval.runtime.ShutdownHooks

class FileCacheImpl[T <: AnyRef](private val converter: Converter[T],
                                 factory: Factory,
                                 executor: Executor,
                                 options: FileCaches.Option*)
    extends FileCache[T] {

  ShutdownHooks.addHook(1, new Runnable() {
    override def run(): Unit = {
      close()
    }
  })

  private val observers: Observers[T] = new Observers()

  private val directories: Map[Path, CachedDirectory[T]] = new HashMap()

  private val pendingFiles: Set[Path] = new HashSet()

  private val closed: AtomicBoolean = new AtomicBoolean(false)

  private val internalExecutor: Executor =
    if (executor == null)
      Executor.make("com.swoval.files.FileCache-callback-internalExecutor")
    else executor

  private val callbackExecutor: Executor =
    Executor.make("com.swoval.files.FileCache-callback-executor")

  private val symlinkWatcher: SymlinkWatcher =
    if (!ArrayOps.contains(options, FileCaches.Option.NOFOLLOW_LINKS))
      new SymlinkWatcher(
        new Consumer[Path]() {
          override def accept(path: Path): Unit = {
            handleEvent(TypedPaths.get(path))
          }
        },
        factory,
        new OnError() {
          override def apply(exception: IOException): Unit = {
            observers.onError(exception)
          }
        },
        this.internalExecutor.copy()
      )
    else null

  private val directoryRegistry: DirectoryRegistry = new DirectoryRegistry()

  private def callback(executor: Executor): Consumer[Event] =
    new Consumer[Event]() {
      override def accept(event: Event): Unit = {
        executor.run(new Runnable() {
          override def run(): Unit = {
            if (event.getKind == Overflow) {
              handleOverflow(event.getPath)
            } else {
              handleEvent(event)
            }
          }
        })
      }
    }

  private val watcher: PathWatcher =
    factory.create(callback(internalExecutor.copy()), internalExecutor.copy(), directoryRegistry)

  /**
 Cleans up the path watcher and clears the directory cache.
   */
  override def close(): Unit = {
    if (closed.compareAndSet(false, true)) {
      if (symlinkWatcher != null) symlinkWatcher.close()
      watcher.close()
      val directoryIterator: Iterator[CachedDirectory[T]] =
        directories.values.iterator()
      while (directoryIterator.hasNext) directoryIterator.next().close()
      directories.clear()
      internalExecutor.close()
      callbackExecutor.close()
    }
  }

  override def addObserver(observer: Observer[DataViews.Entry[T]]): Int =
    addCacheObserver(new CacheObserver[T]() {
      override def onCreate(newEntry: Entry[T]): Unit = {
        observer.onNext(newEntry)
      }

      override def onDelete(oldEntry: Entry[T]): Unit = {
        observer.onNext(oldEntry)
      }

      override def onUpdate(oldEntry: Entry[T], newEntry: Entry[T]): Unit = {
        observer.onNext(newEntry)
      }

      override def onError(exception: IOException): Unit = {
        observer.onError(exception)
      }
    })

  override def removeObserver(handle: Int): Unit = {
    observers.removeObserver(handle)
  }

  override def listEntries(path: Path,
                           maxDepth: Int,
                           filter: Filter[_ >: DataViews.Entry[T]]): List[DataViews.Entry[T]] =
    internalExecutor
      .block(new Callable[List[DataViews.Entry[T]]]() {
        override def call(): List[DataViews.Entry[T]] = {
          val dir: CachedDirectory[T] = find(path)
          if (dir == null) {
            new ArrayList()
          } else {
            if (dir.getPath == path && dir.getMaxDepth == -1) {
              val result: List[DataViews.Entry[T]] =
                new ArrayList[DataViews.Entry[T]]()
              result.add(dir.getEntry)
              result
            } else {
              dir.listEntries(path, maxDepth, filter)
            }
          }
        }
      })
      .get

  override def register(path: Path, maxDepth: Int): Either[IOException, Boolean] =
    internalExecutor
      .block(new Callable[Boolean]() {
        override def call(): Boolean = doReg(path, maxDepth)
      })
      .castLeft(classOf[IOException])

  override def unregister(path: Path): Unit = {
    internalExecutor.block(new Runnable() {
      override def run(): Unit = {
        directoryRegistry.removeDirectory(path)
        watcher.unregister(path)
        if (!directoryRegistry.accept(path)) {
          val dir: CachedDirectory[T] = find(path)
          if (dir != null) {
            if (dir.getPath == path) {
              directories.remove(path)
            } else {
              dir.remove(path)
            }
          }
        }
      }
    })
  }

  private def doReg(path: Path, maxDepth: Int): Boolean = {
    var result: Boolean = false
    directoryRegistry.addDirectory(path, maxDepth)
    watcher.register(path, maxDepth)
    val dirs: List[CachedDirectory[T]] =
      new ArrayList[CachedDirectory[T]](directories.values)
    Collections.sort(
      dirs,
      new Comparator[CachedDirectory[T]]() {
        override def compare(left: CachedDirectory[T], right: CachedDirectory[T]): Int =
          left.getPath.compareTo(right.getPath)
      }
    )
    val it: Iterator[CachedDirectory[T]] = dirs.iterator()
    var existing: CachedDirectory[T] = null
    while (it.hasNext && existing == null) {
      val dir: CachedDirectory[T] = it.next()
      if (path.startsWith(dir.getPath)) {
        val depth: Int =
          if (path == dir.getPath) 0
          else (dir.getPath.relativize(path).getNameCount - 1)
        if (dir.getMaxDepth == java.lang.Integer.MAX_VALUE || maxDepth < dir.getMaxDepth - depth) {
          existing = dir
        } else if (depth <= dir.getMaxDepth) {
          result = true
          dir.close()
          try {
            val md: Int =
              if (maxDepth < java.lang.Integer.MAX_VALUE - depth - 1)
                maxDepth + depth + 1
              else java.lang.Integer.MAX_VALUE
            existing = FileTreeViews.cached(dir.getPath, converter, md)
            directories.put(dir.getPath, existing)
          } catch {
            case e: IOException => existing = null

          }
        }
      }
    }
    if (existing == null) {
      try {
        var dir: CachedDirectory[T] = null
        try dir = FileTreeViews.cached(path, converter, maxDepth)
        catch {
          case e: NotDirectoryException =>
            dir = FileTreeViews.cached(path, converter, -1)

        }
        directories.put(path, dir)
        val entryIterator: Iterator[DataViews.Entry[T]] =
          dir.listEntries(dir.getMaxDepth, AllPass).iterator()
        if (symlinkWatcher != null) {
          while (entryIterator.hasNext) {
            val entry: DataViews.Entry[T] = entryIterator.next()
            if (entry.isSymbolicLink) {
              symlinkWatcher.addSymlink(entry.getPath,
                                        if (maxDepth == java.lang.Integer.MAX_VALUE) maxDepth
                                        else maxDepth - 1)
            }
          }
        }
        result = true
      } catch {
        case e: NoSuchFileException => result = pendingFiles.add(path)

      }
    }
    result
  }

  private def find(path: Path): CachedDirectory[T] = {
    var foundDir: CachedDirectory[T] = null
    val it: Iterator[CachedDirectory[T]] = directories.values.iterator()
    while (it.hasNext) {
      val dir: CachedDirectory[T] = it.next()
      if (path.startsWith(dir.getPath) &&
          (foundDir == null || dir.getPath.startsWith(foundDir.getPath))) {
        foundDir = dir
      }
    }
    foundDir
  }

  private def handleOverflow(path: Path): Unit = {
    if (!closed.get) {
      val directoryIterator: Iterator[CachedDirectory[T]] =
        directories.values.iterator()
      val callbacks: List[Callback] = new ArrayList[Callback]()
      while (directoryIterator.hasNext) {
        val dir: CachedDirectory[T] = directoryIterator.next()
        if (path.startsWith(dir.getPath)) {
          val typedPath: TypedPath = TypedPaths.get(path)
          if (typedPath.exists()) {
            try {
              val updates: Updates[T] = dir.update(typedPath)
              updates.observe(new FileTreeViews.CacheObserver[T]() {
                override def onCreate(newEntry: DataViews.Entry[T]): Unit = {
                  watcher.register(newEntry.getPath,
                                   directoryRegistry.maxDepthFor(newEntry.getPath))
                  if (newEntry.isDirectory) handleOverflow(newEntry.getPath)
                  addCallback(callbacks, path, null, newEntry, Create, null)
                }

                override def onDelete(oldEntry: DataViews.Entry[T]): Unit = {
                  if (oldEntry.isDirectory) handleOverflow(oldEntry.getPath)
                  watcher.unregister(oldEntry.getPath)
                  addCallback(callbacks, path, oldEntry, null, Delete, null)
                }

                override def onUpdate(oldEntry: DataViews.Entry[T],
                                      newEntry: DataViews.Entry[T]): Unit = {
                  addCallback(callbacks, path, oldEntry, newEntry, Modify, null)
                }

                override def onError(exception: IOException): Unit = {
                  addCallback(callbacks, null, null, null, Error, exception)
                }
              })
            } catch {
              case e: IOException => {}

            }
          } else {
            val removed: Iterator[DataViews.Entry[T]] =
              dir.remove(path).iterator()
            while (removed.hasNext) {
              val entry: DataViews.Entry[T] = removed.next()
              addCallback(callbacks, entry.getPath, entry, null, Delete, null)
            }
          }
        }
      }
      callbackExecutor.run(new Runnable() {
        override def run(): Unit = {
          Collections.sort(callbacks)
          val it: Iterator[Callback] = callbacks.iterator()
          while (it.hasNext) it.next().run()
        }
      })
    }
  }

  override def list(path: Path, maxDepth: Int, filter: Filter[_ >: TypedPath]): List[TypedPath] =
    new ArrayList[TypedPath](listEntries(path, maxDepth, filter))

  override def addCacheObserver(observer: CacheObserver[T]): Int =
    observers.addObserver(observer)

  private abstract class Callback(private val path: Path, private val kind: Kind)
      extends Runnable
      with Comparable[Callback] {

    override def compareTo(that: Callback): Int = {
      val kindComparision: Int = this.kind.compareTo(that.kind)
      if (kindComparision == 0) this.path.compareTo(that.path)
      else kindComparision
    }

  }

  private def addCallback(callbacks: List[Callback],
                          path: Path,
                          oldEntry: DataViews.Entry[T],
                          newEntry: DataViews.Entry[T],
                          kind: Kind,
                          ioException: IOException): Unit = {
    callbacks.add(new Callback(path, kind) {
      override def run(): Unit = {
        if (ioException != null) {
          observers.onError(ioException)
        } else if (kind == Create) {
          observers.onCreate(newEntry)
        } else if (kind == Delete) {
          observers.onDelete(oldEntry)
        } else if (kind == Modify) {
          observers.onUpdate(oldEntry, newEntry)
        }
      }
    })
  }

  private def handleEvent(typedPath: TypedPath): Unit = {
    if (!closed.get) {
      val path: Path = typedPath.getPath
      val callbacks: List[Callback] = new ArrayList[Callback]()
      if (typedPath.exists()) {
        val dir: CachedDirectory[T] = find(typedPath.getPath)
        if (dir != null) {
          val paths: List[DataViews.Entry[T]] =
            dir.listEntries(typedPath.getPath, 0, new Filter[DataViews.Entry[T]]() {
              override def accept(entry: DataViews.Entry[T]): Boolean =
                path == entry.getPath
            })
          if (!paths.isEmpty || path != dir.getPath) {
            val toUpdate: TypedPath =
              if (paths.isEmpty) typedPath else paths.get(0)
            try {
              if (typedPath.isSymbolicLink && symlinkWatcher != null)
                symlinkWatcher.addSymlink(typedPath.getPath,
                                          if (dir.getMaxDepth == java.lang.Integer.MAX_VALUE)
                                            java.lang.Integer.MAX_VALUE
                                          else dir.getMaxDepth - 1)
              val updates: Updates[T] = dir.update(toUpdate)
              updates.observe(callbackObserver(callbacks))
            } catch {
              case e: IOException =>
                addCallback(callbacks, path, null, null, Error, e)

            }
          }
        } else if (pendingFiles.remove(path)) {
          try {
            var cachedDirectory: CachedDirectory[T] = null
            try cachedDirectory =
              FileTreeViews.cached(path, converter, directoryRegistry.maxDepthFor(path))
            catch {
              case nde: NotDirectoryException =>
                cachedDirectory = FileTreeViews.cached(path, converter, -1)

            }
            directories.put(path, cachedDirectory)
            addCallback(callbacks, path, null, cachedDirectory.getEntry, Create, null)
            val it: Iterator[DataViews.Entry[T]] = cachedDirectory
              .listEntries(cachedDirectory.getMaxDepth, AllPass)
              .iterator()
            while (it.hasNext) {
              val entry: DataViews.Entry[T] = it.next()
              addCallback(callbacks, entry.getPath, null, entry, Create, null)
            }
          } catch {
            case e: IOException => pendingFiles.add(path)

          }
        }
      } else {
        val removeIterators: List[Iterator[DataViews.Entry[T]]] =
          new ArrayList[Iterator[DataViews.Entry[T]]]()
        val directoryIterator: Iterator[CachedDirectory[T]] =
          new ArrayList(directories.values).iterator()
        while (directoryIterator.hasNext) {
          val dir: CachedDirectory[T] = directoryIterator.next()
          if (path.startsWith(dir.getPath)) {
            val updates: List[DataViews.Entry[T]] = dir.remove(path)
            if (dir.getPath == path) {
              pendingFiles.add(path)
              updates.add(dir.getEntry)
              directories.remove(path)
            }
            removeIterators.add(updates.iterator())
          }
        }
        val it: Iterator[Iterator[DataViews.Entry[T]]] =
          removeIterators.iterator()
        while (it.hasNext) {
          val removeIterator: Iterator[DataViews.Entry[T]] = it.next()
          while (removeIterator.hasNext) {
            val entry: DataViews.Entry[T] = removeIterator.next()
            addCallback(callbacks, entry.getPath, entry, null, Delete, null)
            if (symlinkWatcher != null) {
              symlinkWatcher.remove(entry.getPath)
            }
          }
        }
      }
      if (!callbacks.isEmpty) {
        callbackExecutor.run(new Runnable() {
          override def run(): Unit = {
            Collections.sort(callbacks)
            val callbackIterator: Iterator[Callback] = callbacks.iterator()
            while (callbackIterator.hasNext) callbackIterator.next().run()
          }
        })
      }
    }
  }

  private def callbackObserver(callbacks: List[Callback]): FileTreeViews.CacheObserver[T] =
    new FileTreeViews.CacheObserver[T]() {
      override def onCreate(newEntry: DataViews.Entry[T]): Unit = {
        addCallback(callbacks, newEntry.getPath, null, newEntry, Create, null)
      }

      override def onDelete(oldEntry: DataViews.Entry[T]): Unit = {
        addCallback(callbacks, oldEntry.getPath, oldEntry, null, Delete, null)
      }

      override def onUpdate(oldEntry: DataViews.Entry[T], newEntry: DataViews.Entry[T]): Unit = {
        addCallback(callbacks, oldEntry.getPath, oldEntry, newEntry, Modify, null)
      }

      override def onError(exception: IOException): Unit = {
        addCallback(callbacks, null, null, null, Error, exception)
      }
    }

}
