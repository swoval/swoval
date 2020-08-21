package com.swoval.files

import java.io.IOException
import java.nio.file.Path

import com.swoval.files.FileTreeDataViews.{ Converter, Entry }
import com.swoval.files.FileTreeDataViews.CacheObserver
import com.swoval.files.PathWatchers.Event
import com.swoval.files.TestHelpers._
import com.swoval.files.test._
import com.swoval.files.test.platform.Bool
import com.swoval.functional.{ Filter, Filters, Either => SEither }
import com.swoval.logging.Logger
import utest._

import scala.collection.JavaConverters._

trait FileCacheTest extends TestSuite { self: TestSuite =>
  val factory: (DirectoryRegistry, TestLogger) => PathWatcher[Event]
  def identity: Converter[Path] = (_: TypedPath).getPath

  def simpleCache(
      f: Entry[Path] => Unit
  )(implicit testLogger: TestLogger): FileTreeRepository[Path] =
    FileCacheTest.get(true, identity, getObserver(f), factory)

  def lastModifiedCache(
      f: Entry[LastModified] => Unit
  )(implicit testLogger: TestLogger): FileTreeRepository[LastModified] =
    FileCacheTest.get(true, LastModified(_: TypedPath), getObserver(f), factory)

  def lastModifiedCache(followLinks: Boolean)(
      onCreate: Entry[LastModified] => Unit,
      onUpdate: (Entry[LastModified], Entry[LastModified]) => Unit,
      onDelete: Entry[LastModified] => Unit
  )(implicit testLogger: TestLogger): FileTreeRepository[LastModified] =
    FileCacheTest.get(
      followLinks,
      LastModified(_: TypedPath),
      getObserver(onCreate, onUpdate, onDelete),
      factory
    )
}

object FileCacheTest {
  def getCached[T <: AnyRef](
      followLinks: Boolean,
      converter: Converter[T],
      cacheObserver: CacheObserver[T]
  )(implicit testLogger: TestLogger): FileTreeRepository[T] = {
    val res = FileTreeRepositories.get(converter, followLinks, false, testLogger)
    res.addCacheObserver(cacheObserver)
    res
  }
  class LoopCacheObserver(val latch: CountDownLatch) extends FileTreeDataViews.CacheObserver[Path] {
    override def onCreate(newEntry: Entry[Path]): Unit = {}
    override def onDelete(oldEntry: Entry[Path]): Unit = {}
    override def onUpdate(oldEntry: Entry[Path], newEntry: Entry[Path]): Unit = {}
    override def onError(exception: IOException): Unit = latch.countDown()
  }

  implicit class FileCacheOps[T <: AnyRef](val fileCache: FileTreeRepository[T]) extends AnyVal {
    def ls(dir: Path): Seq[Entry[T]] =
      fileCache.listEntries(dir, Int.MaxValue, Filters.AllPass).asScala
    def ls(dir: Path, recursive: Boolean): Seq[Entry[T]] =
      fileCache.listEntries(dir, if (recursive) Int.MaxValue else 0, Filters.AllPass).asScala
    def ls[R >: Entry[T]](dir: Path, recursive: Boolean, filter: Filter[R]): Seq[Entry[T]] =
      fileCache.listEntries(dir, if (recursive) Int.MaxValue else 0, filter).asScala

    def reg(dir: Path, recursive: Boolean = true): SEither[IOException, Bool] = {
      val res = fileCache.register(dir, recursive)
      assert(res.getOrElse[Bool](false))
      res
    }
  }

  /**
   * Create a file cache with a CacheObserver of events.
   *
   * @param converter     converts a path to the cached value type T
   * @param cacheObserver an cacheObserver of events for this cache
   * @return a file cache.
   */
  private[files] def get[T <: AnyRef](
      followLinks: Boolean,
      converter: FileTreeDataViews.Converter[T],
      cacheObserver: FileTreeDataViews.CacheObserver[T],
      watcherFactory: (DirectoryRegistry, TestLogger) => PathWatcher[PathWatchers.Event]
  )(implicit testLogger: TestLogger): FileTreeRepository[T] = {
    val symlinkWatcher =
      if (followLinks)
        new SymlinkWatcher(watcherFactory(new DirectoryRegistryImpl, testLogger), testLogger)
      else null
    val callbackExecutor = Executor.make("FileTreeRepository-callback-executor")
    val tree =
      new FileCacheDirectoryTree(converter, callbackExecutor, symlinkWatcher, false, testLogger)
    val pathWatcher = watcherFactory(tree.readOnlyDirectoryRegistry, testLogger)
    pathWatcher.addObserver((e: PathWatchers.Event) => tree.handleEvent(e))
    val watcher = new FileCachePathWatcher(tree, pathWatcher)
    val res = new FileTreeRepositoryImpl(tree, watcher, testLogger)
    res.addCacheObserver(cacheObserver)
    res
  }
}

trait DefaultFileCacheTest { self: FileCacheTest =>
  val factory = (directoryRegistry: DirectoryRegistry, testLogger: TestLogger) =>
    PathWatchers.get(false, directoryRegistry, testLogger)
}
trait NioFileCacheTest { self: FileCacheTest =>
  val factory = (directoryRegistry: DirectoryRegistry, testLogger: TestLogger) =>
    PlatformWatcher.make(false, directoryRegistry, testLogger)
}
