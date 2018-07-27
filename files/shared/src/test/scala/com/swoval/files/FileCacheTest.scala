package com.swoval.files

import java.io.IOException
import java.nio.file.Path

import FileCacheTest._
import com.swoval.files.DataViews.{ Converter, Entry }
import com.swoval.files.FileTreeViews.{ CacheObserver, Observer }
import com.swoval.files.PathWatchers.{ Event, Factory }
import com.swoval.files.test._
import com.swoval.files.test.platform.Bool
import com.swoval.functional.{ Consumer, Filter, Filters, Either => SEither }
import utest._

import scala.collection.JavaConverters._

trait FileCacheTest { self: TestSuite =>
  val factory: (Executor, DirectoryRegistry) => PathWatcher[Event]
  def identity: Converter[Path] = _.getPath

  def simpleCache(f: Entry[Path] => Unit): FileTreeRepository[Path] =
    FileCacheTest.get(identity, getObserver(f), factory)

  def lastModifiedCache(f: Entry[LastModified] => Unit): FileTreeRepository[LastModified] =
    FileCacheTest.get(LastModified(_), getObserver(f), factory)

  def lastModifiedCache(onCreate: Entry[LastModified] => Unit,
                        onUpdate: (Entry[LastModified], Entry[LastModified]) => Unit,
                        onDelete: Entry[LastModified] => Unit): FileTreeRepository[LastModified] =
    FileCacheTest.get(LastModified(_), getObserver(onCreate, onUpdate, onDelete), factory)
}

object FileCacheTest {
  def getCached[T](converter: Converter[T],
                   cacheObserver: CacheObserver[T]): FileTreeRepository[T] = {
    val res = FileTreeRepositories.get(converter)
    res.addCacheObserver(cacheObserver)
    res
  }
  class LoopCacheObserver(val latch: CountDownLatch) extends FileTreeViews.CacheObserver[Path] {
    override def onCreate(newEntry: Entry[Path]): Unit = {}
    override def onDelete(oldEntry: Entry[Path]): Unit = {}
    override def onUpdate(oldEntry: Entry[Path], newEntry: Entry[Path]): Unit = {}
    override def onError(exception: IOException): Unit = latch.countDown()
  }

  implicit class FactoryOps(
      val f: (Consumer[PathWatchers.Event], Executor, DirectoryRegistry) => PathWatcher[Event])
      extends PathWatchers.Factory {
    override def create(consumer: BiConsumer[PathWatchers.Event, Executor#Thread],
                        executor: Executor,
                        registry: DirectoryRegistry): PathWatcher[Event] =
      f((e: PathWatchers.Event) => consumer.accept(e, null), executor, registry)
  }

  implicit class FileCacheOps[T <: AnyRef](val fileCache: FileTreeRepository[T]) extends AnyVal {
    def ls(dir: Path,
           recursive: Boolean = true,
           filter: Filter[_ >: Entry[T]] = Filters.AllPass): Seq[Entry[T]] =
      fileCache.listEntries(dir, if (recursive) Integer.MAX_VALUE else 0, filter).asScala

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
  private[files] def get[T](
      converter: DataViews.Converter[T],
      cacheObserver: FileTreeViews.CacheObserver[T],
      watcherFactory: (Executor, DirectoryRegistry) => PathWatcher[PathWatchers.Event])
    : FileTreeRepository[T] = {
    val executor = Executor.make("FileTreeRepository-internal-executor");
    val copy = executor.copy()
    val symlinkWatcher =
      new SymlinkWatcher(watcherFactory(copy, new DirectoryRegistryImpl),
                         (_: IOException) => {},
                         copy)
    val tree = new FileCacheDirectoryTree(converter,
                                          Executor.make("FileTreeRepository-callback-executor"),
                                          symlinkWatcher)
    val pathWatcher = watcherFactory(copy, tree.readOnlyDirectoryRegistry)
    pathWatcher.addObserver((e: PathWatchers.Event) =>
      copy.run((t: Executor#Thread) => tree.handleEvent(e, t)))
    val watcher = new FileCachePathWatcher(tree, pathWatcher)
    new FileTreeRepositoryImpl(tree, watcher, executor)
  }
}

trait DefaultFileCacheTest { self: FileCacheTest =>
  val factory =
    (executor: Executor, directoryRegistry: DirectoryRegistry) =>
      PathWatchers.get(executor, directoryRegistry)
}
trait NioFileCacheTest { self: FileCacheTest =>
  val factory =
    (executor: Executor, directoryRegistry: DirectoryRegistry) =>
      PlatformWatcher.make(executor, directoryRegistry)
}
