// Do not edit this file manually. It is autogenerated.

package com.swoval.files

import com.swoval.files.PathWatchers.Event.Kind.Create
import com.swoval.files.PathWatchers.Event.Kind.Delete
import com.swoval.files.PathWatchers.Event.Kind.Error
import com.swoval.files.PathWatchers.Event.Kind.Modify
import com.swoval.files.PathWatchers.Event.Kind.Overflow
import com.swoval.functional.Filters.AllPass
import com.swoval.files.FileTreeDataViews.CacheObserver
import com.swoval.files.FileTreeDataViews.Converter
import com.swoval.files.FileTreeDataViews.Entry
import com.swoval.files.FileTreeDataViews.ObservableCache
import com.swoval.files.FileTreeRepositoryImpl.Callback
import com.swoval.files.FileTreeViews.Observer
import com.swoval.files.FileTreeViews.Updates
import com.swoval.files.PathWatchers.Event
import com.swoval.files.PathWatchers.Event.Kind
import com.swoval.functional.Filter
import java.io.IOException
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.ArrayList
import java.util.Collections
import java.util.Comparator
import java.util.HashMap
import java.util.HashSet
import java.util.Iterator
import java.util.List
import java.util.Map
import java.util.Set
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

class FileCacheDirectories[T <: AnyRef](lock: ReentrantLock)
    extends LockableMap[Path, CachedDirectory[T]](new HashMap[Path, CachedDirectory[T]](), lock)

class FileCachePendingFiles(reentrantLock: ReentrantLock) extends Lockable(reentrantLock) {

  private val pendingFiles: Set[Path] = new HashSet()

  def clear(): Unit = {
    if (lock()) {
      try pendingFiles.clear()
      finally unlock()
    }
  }

  def add(path: Path): Boolean =
    if (lock()) {
      try pendingFiles.add(path)
      finally unlock()
    } else {
      false
    }

  def remove(path: Path): Boolean =
    if (lock()) {
      try pendingFiles.remove(path)
      finally unlock()
    } else {
      false
    }

}

class FileCacheDirectoryTree[T <: AnyRef](private val converter: Converter[T],
                                          private val callbackExecutor: Executor,
                                          val symlinkWatcher: SymlinkWatcher,
                                          private val rescanOnDirectoryUpdate: Boolean)
    extends ObservableCache[T]
    with FileTreeDataView[T] {

  private val directoryRegistry: DirectoryRegistry =
    new DirectoryRegistryImpl()

  private val filter: Filter[TypedPath] =
    DirectoryRegistries.toTypedPathFilter(directoryRegistry)

  private val observers: CacheObservers[T] = new CacheObservers()

  private val followLinks: Boolean = symlinkWatcher != null

  private val closed: AtomicBoolean = new AtomicBoolean(false)

  private val logger: DebugLogger = Loggers.getDebug

  if (symlinkWatcher != null) {
    symlinkWatcher.addObserver(new Observer[Event]() {
      override def onError(t: Throwable): Unit = {
        t.printStackTrace(System.err)
      }

      override def onNext(event: Event): Unit = {
        handleEvent(event)
      }
    })
  }

  val reentrantLock: ReentrantLock = new ReentrantLock()

  private val directories: FileCacheDirectories[T] = new FileCacheDirectories(reentrantLock)

  private val pendingFiles: FileCachePendingFiles = new FileCachePendingFiles(reentrantLock)

  private val READ_ONLY_DIRECTORY_REGISTRY: DirectoryRegistry =
    new DirectoryRegistry() {
      override def close(): Unit = {}

      override def addDirectory(path: Path, maxDepth: Int): Boolean = false

      override def maxDepthFor(path: Path): Int =
        directoryRegistry.maxDepthFor(path)

      override def registered(): Map[Path, Integer] =
        directoryRegistry.registered()

      override def removeDirectory(path: Path): Unit = {}

      override def acceptPrefix(path: Path): Boolean =
        directoryRegistry.acceptPrefix(path)

      override def accept(path: Path): Boolean = directoryRegistry.accept(path)
    }

  def readOnlyDirectoryRegistry(): DirectoryRegistry =
    READ_ONLY_DIRECTORY_REGISTRY

  def unregister(path: Path): Unit = {
    val absolutePath: Path =
      if (path.isAbsolute) path else path.toAbsolutePath()
    if (directories.lock()) {
      try {
        directoryRegistry.removeDirectory(absolutePath)
        if (!directoryRegistry.accept(absolutePath)) {
          val dir: CachedDirectory[T] = find(absolutePath)
          if (dir != null) {
            if (dir.getPath == absolutePath) {
              directories.remove(absolutePath)
            } else {
              dir.remove(absolutePath)
            }
          }
        }
      } finally directories.unlock()
    }
    if (logger.shouldLog())
      logger.debug("FileCacheDirectoryTree unregistered " + path)
  }

  private def find(path: Path): CachedDirectory[T] = {
    var foundDir: CachedDirectory[T] = null
    val dirs: List[CachedDirectory[T]] = directories.values
    Collections.sort(
      dirs,
      new Comparator[CachedDirectory[T]]() {
        override def compare(left: CachedDirectory[T], right: CachedDirectory[T]): Int = // Descending order so that we find the most specific path
          right.getPath.compareTo(left.getPath)
      }
    )
    val it: Iterator[CachedDirectory[T]] = dirs.iterator()
    while (it.hasNext && foundDir == null) {
      val dir: CachedDirectory[T] = it.next()
      if (path.startsWith(dir.getPath)) {
        if (dir.getMaxDepth == java.lang.Integer.MAX_VALUE || path == dir.getPath) {
          foundDir = dir
        } else {
          val depth: Int = dir.getPath.relativize(path).getNameCount - 1
          if (depth <= dir.getMaxDepth) {
            foundDir = dir
          }
        }
      }
    }
    foundDir
  }

  private def runCallbacks(callbacks: List[Callback]): Unit = {
    if (!callbacks.isEmpty && !closed.get) {
      callbackExecutor.run(new Runnable() {
        override def run(): Unit = {
          Collections.sort(callbacks)
          val it: Iterator[Callback] = callbacks.iterator()
          while (it.hasNext) it.next().run()
        }
      })
    }
  }

  def handleEvent(event: Event): Unit = {
    if (logger.shouldLog())
      logger.debug("FileCacheDirectoryTree received event " + event)
    val typedPath: TypedPath = event.getTypedPath
    val symlinks: List[TypedPath] = new ArrayList[TypedPath]()
    val callbacks: List[Callback] = new ArrayList[Callback]()
    if (!closed.get && directories.lock()) {
      try {
        val path: Path = typedPath.getPath
        if (typedPath.exists()) {
          val dir: CachedDirectory[T] = find(typedPath.getPath)
          if (dir != null) {
            try {
              val updatePath: TypedPath =
                if ((followLinks || !typedPath.isSymbolicLink)) typedPath
                else TypedPaths.get(typedPath.getPath, Entries.LINK)
              val rescan: Boolean = rescanOnDirectoryUpdate || event.getKind == Overflow
              dir
                .update(updatePath, rescan)
                .observe(callbackObserver(callbacks, symlinks))
            } catch {
              case e: IOException => handleDelete(path, callbacks, symlinks)

            }
          } else if (pendingFiles.remove(path)) {
            try {
              var cachedDirectory: CachedDirectory[T] = null
              try cachedDirectory = newCachedDirectory(path, directoryRegistry.maxDepthFor(path))
              catch {
                case nde: NotDirectoryException =>
                  cachedDirectory = newCachedDirectory(path, -1)

              }
              val previous: CachedDirectory[T] =
                directories.put(path, cachedDirectory)
              if (previous != null) previous.close()
              addCallback(callbacks,
                          symlinks,
                          cachedDirectory.getEntry,
                          null,
                          cachedDirectory.getEntry,
                          Create,
                          null)
              val it: Iterator[FileTreeDataViews.Entry[T]] = cachedDirectory
                .listEntries(cachedDirectory.getMaxDepth, AllPass)
                .iterator()
              while (it.hasNext) {
                val entry: FileTreeDataViews.Entry[T] = it.next()
                addCallback(callbacks, symlinks, entry, null, entry, Create, null)
              }
            } catch {
              case e: IOException => pendingFiles.add(path)

            }
          }
        } else {
          handleDelete(path, callbacks, symlinks)
        }
      } finally directories.unlock()
      val it: Iterator[TypedPath] = symlinks.iterator()
      while (it.hasNext) {
        val tp: TypedPath = it.next()
        val path: Path = tp.getPath
        if (symlinkWatcher != null) {
          if (tp.exists()) {
            try symlinkWatcher.addSymlink(path, directoryRegistry.maxDepthFor(path))
            catch {
              case e: IOException => observers.onError(e)

            }
          } else {
            symlinkWatcher.remove(path)
          }
        }
      }
      runCallbacks(callbacks)
    }
  }

  private def handleDelete(path: Path,
                           callbacks: List[Callback],
                           symlinks: List[TypedPath]): Unit = {
    val removeIterators: List[Iterator[FileTreeDataViews.Entry[T]]] =
      new ArrayList[Iterator[FileTreeDataViews.Entry[T]]]()
    val directoryIterator: Iterator[CachedDirectory[T]] =
      new ArrayList(directories.values).iterator()
    while (directoryIterator.hasNext) {
      val dir: CachedDirectory[T] = directoryIterator.next()
      if (path.startsWith(dir.getPath)) {
        val updates: List[FileTreeDataViews.Entry[T]] =
          if (path == dir.getPath)
            dir.listEntries(java.lang.Integer.MAX_VALUE, AllPass)
          else new ArrayList[FileTreeDataViews.Entry[T]]()
        updates.addAll(dir.remove(path))
        val it: Iterator[Path] =
          directoryRegistry.registered().keySet.iterator()
        while (it.hasNext) if (it.next() == path) {
          pendingFiles.add(path)
        }
        if (dir.getPath == path) {
          directories.remove(path)
          updates.add(dir.getEntry)
        }
        removeIterators.add(updates.iterator())
      }
    }
    val it: Iterator[Iterator[FileTreeDataViews.Entry[T]]] =
      removeIterators.iterator()
    while (it.hasNext) {
      val removeIterator: Iterator[FileTreeDataViews.Entry[T]] = it.next()
      while (removeIterator.hasNext) {
        val entry: FileTreeDataViews.Entry[T] =
          Entries.setExists(removeIterator.next(), false)
        if (symlinkWatcher != null && entry.getTypedPath.isSymbolicLink)
          symlinkWatcher.remove(entry.getTypedPath.getPath)
        addCallback(callbacks, symlinks, entry, entry, null, Delete, null)
      }
    }
  }

  override def close(): Unit = {
    if (closed.compareAndSet(false, true) && directories.lock()) {
      try {
        callbackExecutor.close()
        if (symlinkWatcher != null) symlinkWatcher.close()
        directories.clear()
        observers.close()
        directoryRegistry.close()
        pendingFiles.clear()
      } finally directories.unlock()
    }
    if (logger.shouldLog())
      logger.debug("FileCacheDirectoryTree " + this + " was closed")
  }

  def register(path: Path,
               maxDepth: Int,
               watcher: PathWatcher[PathWatchers.Event]): CachedDirectory[T] = {
    val absolutePath: Path =
      if (path.isAbsolute) path else path.toAbsolutePath()
    if (directoryRegistry.addDirectory(absolutePath, maxDepth) &&
        directories.lock()) {
      try {
        watcher.register(absolutePath, maxDepth)
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
          if (absolutePath.startsWith(dir.getPath)) {
            val depth: Int = dir.getPath
              .relativize(absolutePath)
              .getNameCount - 1
            if (dir.getMaxDepth == java.lang.Integer.MAX_VALUE || dir.getMaxDepth - depth > maxDepth) {
              existing = dir
            }
          }
        }
        var dir: CachedDirectory[T] = null
        if (existing == null) {
          try {
            try dir = newCachedDirectory(absolutePath, maxDepth)
            catch {
              case e: NotDirectoryException =>
                dir = newCachedDirectory(absolutePath, -1)

            }
            directories.put(absolutePath, dir)
          } catch {
            case e: NoSuchFileException => {
              pendingFiles.add(absolutePath)
              dir = newCachedDirectory(absolutePath, -1)
            }

          }
        } else {
          existing.update(TypedPaths.get(absolutePath))
          dir = existing
        }
        cleanupDirectories(absolutePath, maxDepth)
        if (logger.shouldLog())
          logger.debug(
            "FileCacheDirectoryTree registered " + path + " with max depth " +
              maxDepth)
        dir
      } finally directories.unlock()
    } else {
      null
    }
  }

  private def cleanupDirectories(path: Path, maxDepth: Int): Unit = {
    val it: Iterator[CachedDirectory[T]] = directories.values.iterator()
    val toRemove: List[Path] = new ArrayList[Path]()
    while (it.hasNext) {
      val dir: CachedDirectory[T] = it.next()
      if (dir.getPath.startsWith(path) && dir.getPath != path) {
        if (maxDepth == java.lang.Integer.MAX_VALUE) {
          toRemove.add(dir.getPath)
        } else {
          val depth: Int = path.relativize(dir.getPath).getNameCount
          if (maxDepth - depth >= dir.getMaxDepth) {
            toRemove.add(dir.getPath)
          }
        }
      }
    }
    val removeIterator: Iterator[Path] = toRemove.iterator()
    while (removeIterator.hasNext) directories.remove(removeIterator.next())
  }

  private def addCallback(callbacks: List[Callback],
                          symlinks: List[TypedPath],
                          entry: FileTreeDataViews.Entry[T],
                          oldEntry: FileTreeDataViews.Entry[T],
                          newEntry: FileTreeDataViews.Entry[T],
                          kind: Kind,
                          ioException: IOException): Unit = {
    val typedPath: TypedPath = if (entry == null) null else entry.getTypedPath
    if (typedPath != null && typedPath.isSymbolicLink && followLinks) {
      symlinks.add(typedPath)
    }
    callbacks.add(new Callback(if (typedPath == null) Paths.get("") else typedPath.getPath) {
      override def run(): Unit = {
        try if (ioException != null) {
          observers.onError(ioException)
        } else if (kind == Create) {
          observers.onCreate(newEntry)
        } else if (kind == Delete) {
          observers.onDelete(Entries.setExists(oldEntry, false))
        } else if (kind == Modify) {
          observers.onUpdate(oldEntry, newEntry)
        } catch {
          case e: Exception => e.printStackTrace()

        }
      }
    })
  }

  override def addObserver(observer: Observer[_ >: Entry[T]]): Int =
    observers.addObserver(observer)

  override def removeObserver(handle: Int): Unit = {
    observers.removeObserver(handle)
  }

  override def addCacheObserver(observer: CacheObserver[T]): Int =
    observers.addCacheObserver(observer)

  override def listEntries(path: Path,
                           maxDepth: Int,
                           filter: Filter[_ >: Entry[T]]): List[Entry[T]] =
    if (directories.lock()) {
      try {
        val dir: CachedDirectory[T] = find(path)
        if (dir == null) {
          Collections.emptyList()
        } else {
          if (dir.getPath == path && dir.getMaxDepth == -1) {
            val result: List[FileTreeDataViews.Entry[T]] =
              new ArrayList[FileTreeDataViews.Entry[T]]()
            result.add(dir.getEntry)
            result
          } else {
            val depth: Int = directoryRegistry.maxDepthFor(path)
            dir.listEntries(path, if (depth < maxDepth) depth else maxDepth, filter)
          }
        }
      } finally directories.unlock()
    } else {
      Collections.emptyList()
    }

  private def callbackObserver(callbacks: List[Callback],
                               symlinks: List[TypedPath]): CacheObserver[T] =
    new CacheObserver[T]() {
      override def onCreate(newEntry: FileTreeDataViews.Entry[T]): Unit = {
        addCallback(callbacks, symlinks, newEntry, null, newEntry, Create, null)
      }

      override def onDelete(oldEntry: FileTreeDataViews.Entry[T]): Unit = {
        addCallback(callbacks, symlinks, oldEntry, oldEntry, null, Delete, null)
      }

      override def onUpdate(oldEntry: FileTreeDataViews.Entry[T],
                            newEntry: FileTreeDataViews.Entry[T]): Unit = {
        addCallback(callbacks, symlinks, oldEntry, oldEntry, newEntry, Modify, null)
      }

      override def onError(exception: IOException): Unit = {
        addCallback(callbacks, symlinks, null, null, null, Error, exception)
      }
    }

  override def list(path: Path, maxDepth: Int, filter: Filter[_ >: TypedPath]): List[TypedPath] =
    if (directories.lock()) {
      try {
        val dir: CachedDirectory[T] = find(path)
        if (dir == null) {
          Collections.emptyList()
        } else {
          if (dir.getPath == path && dir.getMaxDepth == -1) {
            val result: List[TypedPath] = new ArrayList[TypedPath]()
            result.add(TypedPaths.getDelegate(dir.getPath, dir.getTypedPath))
            result
          } else {
            dir.list(path, maxDepth, filter)
          }
        }
      } finally directories.unlock()
    } else {
      Collections.emptyList()
    }

  private def newCachedDirectory(path: Path, depth: Int): CachedDirectory[T] =
    new CachedDirectoryImpl(TypedPaths.get(path), converter, depth, filter, followLinks).init()

}
